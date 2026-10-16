package xyz.azraellab.zapp.core.backup

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import xyz.azraellab.zapp.core.AppConfig
import xyz.azraellab.zapp.core.Preset
import xyz.azraellab.zapp.core.VpnProfile

/** Бэкап: сборка, превью, восстановление, вычищение секретов. */
class BackupTest {

    private fun configWithSecrets() = AppConfig(
        vpn = AppConfig().vpn.copy(
            profiles = listOf(
                VpnProfile(
                    id = "p1",
                    name = "Node A",
                    privateKey = "SECRET_PRIV",
                    presharedKey = "SECRET_PSK",
                    certPassword = "SECRET_CERT",
                    rawConfig = "[Interface]\nPrivateKey=SECRET_PRIV\nAddress=10.0.0.2"
                )
            )
        )
    )

    private val presets = listOf(Preset(id = "pr1", name = "Home"))

    @Test
    fun buildThenParseRoundtrip() {
        val bytes = BackupService.build(AppConfig(), presets, "2.7.20", 1234L)
        val contents = BackupService.parse(bytes)
        assertNotNull(contents)
        assertEquals(1234L, contents.manifest.createdAt)
        assertEquals("2.7.20", contents.manifest.appVersion)
        assertEquals(listOf("Home"), contents.manifest.presetNames)
        assertEquals(presets, contents.presets)
    }

    @Test
    fun manifestListsProfileNames() {
        val bytes = BackupService.build(configWithSecrets(), emptyList(), "v", 1L)
        val contents = BackupService.parse(bytes)
        assertNotNull(contents)
        assertEquals(listOf("Node A"), contents.manifest.profileNames)
    }

    @Test
    fun secretsStrippedFromArchive() {
        val bytes = BackupService.build(configWithSecrets(), emptyList(), "v", 1L)
        val contents = BackupService.parse(bytes)
        assertNotNull(contents)
        val profile = contents.config.vpn.profiles.single()
        assertEquals("", profile.privateKey)
        assertEquals("", profile.presharedKey)
        assertEquals("", profile.certPassword)
        assertEquals(false, "SECRET_PRIV" in profile.rawConfig)
        assertEquals(true, "Address=10.0.0.2" in profile.rawConfig)
    }

    @Test
    fun modeFullKeepsBoth() {
        val contents = BackupService.parse(
            BackupService.build(configWithSecrets(), presets, "v", 1L)
        )!!
        assertNotNull(BackupService.configOf(contents, RestoreMode.FULL))
        assertNotNull(BackupService.presetsOf(contents, RestoreMode.FULL))
    }

    @Test
    fun modeConfigDropsPresets() {
        val contents = BackupService.parse(
            BackupService.build(configWithSecrets(), presets, "v", 1L)
        )!!
        assertNotNull(BackupService.configOf(contents, RestoreMode.CONFIG))
        assertNull(BackupService.presetsOf(contents, RestoreMode.CONFIG))
    }

    @Test
    fun modePresetsDropsConfig() {
        val contents = BackupService.parse(
            BackupService.build(configWithSecrets(), presets, "v", 1L)
        )!!
        assertNull(BackupService.configOf(contents, RestoreMode.PRESETS))
        assertNotNull(BackupService.presetsOf(contents, RestoreMode.PRESETS))
    }

    @Test
    fun garbageIsRejected() {
        assertNull(BackupService.parse("not a zip".toByteArray()))
    }

    @Test
    fun emptyBytesRejected() {
        assertNull(BackupService.parse(ByteArray(0)))
    }

    @Test
    fun foreignZipRejected() {
        // zip без нашего манифеста -- чужой архив, не «повреждённый бэкап»
        val foreign = zipPack(listOf("hello.txt" to "hi"))
        assertNull(BackupService.parse(foreign))
    }

    @Test
    fun wrongKindRejected() {
        val bytes = BackupService.build(AppConfig(), emptyList(), "v", 1L)
        // подмена манифеста на чужой kind внутри валидного zip
        val entries = zipUnpack(bytes)!!.toMutableMap()
        entries[BackupService.MANIFEST] =
            entries[BackupService.MANIFEST]!!.replace("zapp-backup", "other-backup")
        assertNull(BackupService.parse(zipPack(entries.toList())))
    }

    @Test
    fun zipRoundtripUnicode() {
        val packed = zipPack(listOf("имя.txt" to "привет", "b.bin" to "x"))
        val unpacked = zipUnpack(packed)
        assertNotNull(unpacked)
        assertEquals("привет", unpacked["имя.txt"])
        assertEquals("x", unpacked["b.bin"])
    }
}
