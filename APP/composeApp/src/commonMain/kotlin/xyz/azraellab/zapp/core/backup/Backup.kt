package xyz.azraellab.zapp.core.backup

import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import xyz.azraellab.zapp.core.AppConfig
import xyz.azraellab.zapp.core.ConfigCodec
import xyz.azraellab.zapp.core.Preset
import xyz.azraellab.zapp.core.VpnProfile

/** Метка архива. */
@Serializable
data class BackupManifest(
    val kind: String = KIND,
    val format: Int = FORMAT,
    val createdAt: Long = 0,
    val appVersion: String = "",
    /** Имена профилей — для превью. */
    val profileNames: List<String> = emptyList(),
    val presetNames: List<String> = emptyList()
) {
    companion object {
        const val KIND = "zapp-backup"
        const val FORMAT = 1
    }
}

/** Что применять из архива. */
enum class RestoreMode { FULL, CONFIG, PRESETS }

/** Что внутри архива. */
data class BackupContents(
    val manifest: BackupManifest,
    val config: AppConfig,
    val presets: List<Preset>
)

/**
 * Сборка и разбор бэкапа.
 *
 * Ключи WireGuard не входят: секреты живут в системном хранилище,
 * на новом устройстве их вводят заново.
 */
object BackupService {

    const val MANIFEST = "manifest.json"
    const val CONFIG = "config.json"
    const val PRESETS = "presets.json"

    /** Собирает архив. */
    fun build(
        config: AppConfig,
        presets: List<Preset>,
        appVersion: String,
        nowMs: Long
    ): ByteArray {
        val clean = config.copy(
            vpn = config.vpn.copy(profiles = config.vpn.profiles.map { it.withoutSecrets() })
        )
        val manifest = BackupManifest(
            createdAt = nowMs,
            appVersion = appVersion,
            profileNames = clean.vpn.profiles.map { it.name },
            presetNames = presets.map { it.name }
        )
        return zipPack(
            listOf(
                MANIFEST to ConfigCodec.json.encodeToString(
                    BackupManifest.serializer(), manifest
                ),
                CONFIG to ConfigCodec.json.encodeToString(AppConfig.serializer(), clean),
                PRESETS to ConfigCodec.json.encodeToString(
                    ListSerializer(Preset.serializer()), presets
                )
            )
        )
    }

    /** Разбирает архив; null — не наш формат или повреждён. */
    fun parse(bytes: ByteArray): BackupContents? {
        val entries = runCatching { zipUnpack(bytes) }.getOrNull() ?: return null
        val manifest = entries[MANIFEST]
            ?.let {
                runCatching {
                    ConfigCodec.json.decodeFromString(BackupManifest.serializer(), it)
                }.getOrNull()
            } ?: return null
        if (manifest.kind != BackupManifest.KIND) return null
        val config = entries[CONFIG]
            ?.let {
                runCatching {
                    ConfigCodec.json.decodeFromString(AppConfig.serializer(), it)
                }.getOrNull()
            } ?: return null
        val presets = entries[PRESETS]
            ?.let {
                runCatching {
                    ConfigCodec.json.decodeFromString(ListSerializer(Preset.serializer()), it)
                }.getOrNull()
            } ?: return null
        return BackupContents(manifest, config, presets)
    }

    /** null — эту часть не трогаем. */
    fun configOf(contents: BackupContents, mode: RestoreMode): AppConfig? =
        if (mode == RestoreMode.PRESETS) null else contents.config

    /** null — пресеты не трогаем. */
    fun presetsOf(contents: BackupContents, mode: RestoreMode): List<Preset>? =
        if (mode == RestoreMode.CONFIG) null else contents.presets

    /** Профиль без секретов. */
    private fun VpnProfile.withoutSecrets(): VpnProfile {
        val cleaned = copy(privateKey = "", presharedKey = "", certPassword = "")
        if ("PrivateKey=" !in rawConfig) return cleaned
        return cleaned.copy(
            rawConfig = rawConfig.lineSequence()
                .filterNot { it.trimStart().startsWith("PrivateKey=") }
                .joinToString("\n")
        )
    }
}

/** Сборка zip; в общем коду нужен только этот ход. */
expect fun zipPack(entries: List<Pair<String, String>>): ByteArray

/** Распаковка; null — байты не являются zip. */
expect fun zipUnpack(bytes: ByteArray): Map<String, String>?
