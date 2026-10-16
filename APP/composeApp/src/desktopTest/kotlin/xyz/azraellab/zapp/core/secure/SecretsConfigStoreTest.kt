package xyz.azraellab.zapp.core.secure

import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import xyz.azraellab.zapp.core.AppConfig
import xyz.azraellab.zapp.core.ConfigCodec
import xyz.azraellab.zapp.core.ConfigStore
import xyz.azraellab.zapp.core.Preset
import xyz.azraellab.zapp.core.SecretsConfigStore
import xyz.azraellab.zapp.core.VpnConfig
import xyz.azraellab.zapp.core.VpnProfile

/**
 * Слой «секреты отдельно от настроек»: что уходит из JSON в хранилище,
 * что возвращается при чтении и как всё это переживает отказ хранилища.
 */
class SecretsConfigStoreTest {

    private class MemStore : ConfigStore {
        var config: AppConfig? = null
        val presets = linkedMapOf<String, Preset>()
        override fun readConfig(): AppConfig? = config
        override fun writeConfig(config: AppConfig) { this.config = config }
        override fun readPresets(): List<Preset> = presets.values.toList()
        override fun writePreset(preset: Preset) { presets[preset.id] = preset }
        override fun deletePreset(id: String) { presets.remove(id) }
    }

    private class MemStorage(var accept: Boolean = true) : SecureStorage {
        val slots = linkedMapOf<String, String>()
        override val available: Boolean get() = accept
        override fun put(slot: String, value: String): Boolean {
            if (!accept) return false
            slots[slot] = value
            return true
        }
        override fun get(slot: String): String? = slots[slot]
        override fun remove(slot: String) { slots.remove(slot) }
    }

    private val wgProfile = VpnProfile(
        id = "p1",
        privateKey = "PRIV",
        presharedKey = "PSK",
        rawConfig = "[Interface]\nPrivateKey=PRIV\n"
    )

    private fun delegateWith(profile: VpnProfile): MemStore =
        MemStore().apply { config = AppConfig(vpn = VpnConfig(profiles = listOf(profile))) }

    private fun bundleIn(storage: MemStorage, slot: String): KeyBundle =
        ConfigCodec.json.decodeFromString(KeyBundle.serializer(), requireNotNull(storage.slots[slot]))

    @Test
    fun writeMovesSecretsOutOfJson() {
        val delegate = delegateWith(wgProfile)
        val storage = MemStorage()
        val store = SecretsConfigStore(delegate, storage)

        store.writeConfig(requireNotNull(delegate.config))

        val onDisk = requireNotNull(delegate.config).vpn.profiles.single()
        assertEquals("", onDisk.privateKey)
        assertEquals("", onDisk.presharedKey)
        assertEquals("", onDisk.rawConfig, "wg-ini уходит вместе с ключом")
        val bundle = bundleIn(storage, "vpn:p1")
        assertEquals("PRIV", bundle.privateKey)
        assertEquals("PSK", bundle.presharedKey)
        assertContains(bundle.rawConfig, "PrivateKey=PRIV")
    }

    @Test
    fun writeKeepsJsonWhenStorageRejects() {
        val delegate = delegateWith(wgProfile)
        val storage = MemStorage(accept = false)

        SecretsConfigStore(delegate, storage).writeConfig(requireNotNull(delegate.config))

        val kept = requireNotNull(delegate.config).vpn.profiles.single()
        assertEquals("PRIV", kept.privateKey, "отказ хранилища не должен терять ключ")
        assertTrue(storage.slots.isEmpty())
    }

    @Test
    fun readRestoresBundle() {
        val delegate = delegateWith(wgProfile)
        val storage = MemStorage()
        val store = SecretsConfigStore(delegate, storage)
        store.writeConfig(requireNotNull(delegate.config))

        val restored = requireNotNull(store.readConfig()).vpn.profiles.single()
        assertEquals("PRIV", restored.privateKey)
        assertEquals("PSK", restored.presharedKey)
        assertContains(restored.rawConfig, "PrivateKey=PRIV")
    }

    @Test
    fun readWithoutStorageLeavesProfileQuiet() {
        val delegate = MemStore().apply {
            config = AppConfig(vpn = VpnConfig(profiles = listOf(VpnProfile(id = "p1"))))
        }
        val restored = requireNotNull(SecretsConfigStore(delegate, MemStorage()).readConfig())
        assertEquals("", restored.vpn.profiles.single().privateKey)
    }

    @Test
    fun corruptedBundleIsIgnored() {
        val delegate = MemStore().apply {
            config = AppConfig(vpn = VpnConfig(profiles = listOf(VpnProfile(id = "p1"))))
        }
        val storage = MemStorage().apply { slots["vpn:p1"] = "это не json" }
        val restored = requireNotNull(SecretsConfigStore(delegate, storage).readConfig())
        assertEquals("", restored.vpn.profiles.single().privateKey)
    }

    @Test
    fun keysAlreadyInJsonWin() {
        val delegate = delegateWith(wgProfile)
        val storage = MemStorage().apply {
            slots["vpn:p1"] = ConfigCodec.json.encodeToString(
                KeyBundle.serializer(),
                KeyBundle(privateKey = "OLD")
            )
        }
        val restored = requireNotNull(SecretsConfigStore(delegate, storage).readConfig())
        assertEquals("PRIV", restored.vpn.profiles.single().privateKey, "JSON не затирается слотом")
    }

    @Test
    fun rawWithoutKeyMarkerStaysInJson() {
        val profile = VpnProfile(id = "p2", privateKey = "PRIV", rawConfig = "ss://user@host:8388")
        val delegate = delegateWith(profile)
        val storage = MemStorage()

        SecretsConfigStore(delegate, storage).writeConfig(requireNotNull(delegate.config))

        val onDisk = requireNotNull(delegate.config).vpn.profiles.single()
        assertEquals("ss://user@host:8388", onDisk.rawConfig, "без строки PrivateKey= текст не трогаем")
        assertEquals("", bundleIn(storage, "vpn:p2").rawConfig)
    }

    @Test
    fun profileWithoutSecretsUntouched() {
        val delegate = delegateWith(VpnProfile(id = "p3", rawConfig = "ss://x"))
        val storage = MemStorage()

        SecretsConfigStore(delegate, storage).writeConfig(requireNotNull(delegate.config))

        assertTrue(storage.slots.isEmpty())
        assertEquals("ss://x", requireNotNull(delegate.config).vpn.profiles.single().rawConfig)
    }

    @Test
    fun presetsStashAndRestore() {
        val delegate = MemStore()
        val storage = MemStorage()
        val store = SecretsConfigStore(delegate, storage)

        store.writePreset(Preset(id = "pres1", vpn = VpnConfig(profiles = listOf(wgProfile))))
        assertEquals("", requireNotNull(delegate.presets["pres1"]).vpn.profiles.single().privateKey)
        assertTrue(storage.slots.containsKey("vpn:p1"))

        val restored = store.readPresets().single().vpn.profiles.single()
        assertEquals("PRIV", restored.privateKey)
        assertEquals("PSK", restored.presharedKey)
    }

    @Test
    fun deletePresetGoesThrough() {
        val delegate = MemStore().apply { presets["x"] = Preset(id = "x") }
        SecretsConfigStore(delegate, MemStorage()).deletePreset("x")
        assertTrue(delegate.presets.isEmpty())
    }
}
