package xyz.azraellab.zapp.core

import kotlinx.serialization.Serializable
import xyz.azraellab.zapp.core.secure.KeyBundle
import xyz.azraellab.zapp.core.secure.SecureStorage
import xyz.azraellab.zapp.core.secure.platformSecureStorage

/**
 * Хранилище настроек.
 *
 * Объявление в common, реализации платформенные: на Android это файл во
 * внутреннем хранилище приложения, на десктопе -- JSON в каталоге конфигурации.
 * Ключевое требование -- [read] не бросает исключений. Повреждённый файл
 * настроек не должен мешать запуску приложения.
 */
interface ConfigStore {
    fun readConfig(): AppConfig?
    fun writeConfig(config: AppConfig)
    fun readPresets(): List<Preset>
    fun writePreset(preset: Preset)
    fun deletePreset(id: String)
}

/** Создаёт хранилище для текущей платформы. */
expect fun createConfigStore(): ConfigStore

/**
 * Слой «секреты отдельно от настроек»: перед записью секретные поля
 * профилей уходят в [SecureStorage], JSON остаётся без них; чтение
 * собирает профили обратно.
 *
 * Если хранилище недоступно или отказало ([SecureStorage.put] == false),
 * секреты просто остаются в JSON -- сохранение не ломается, поведение
 * совпадает с прежним. Слоты после удаления профиля не чистятся: тот же
 * профиль может лежать в пресете, а потерять ключ из-за удаления настройки
 * хуже, чем оставить пару лишних записей.
 */
class SecretsConfigStore(
    private val delegate: ConfigStore,
    private val storage: SecureStorage = platformSecureStorage()
) : ConfigStore {

    override fun readConfig(): AppConfig? = delegate.readConfig()?.let { config ->
        config.copy(vpn = config.vpn.copy(profiles = config.vpn.profiles.map { restoreProfile(it) }))
    }

    override fun writeConfig(config: AppConfig) {
        delegate.writeConfig(config.copy(vpn = config.vpn.copy(profiles = config.vpn.profiles.map { stashProfile(it) })))
    }

    override fun readPresets(): List<Preset> = delegate.readPresets().map { preset ->
        preset.copy(vpn = preset.vpn.copy(profiles = preset.vpn.profiles.map { restoreProfile(it) }))
    }

    override fun writePreset(preset: Preset) {
        delegate.writePreset(preset.copy(vpn = preset.vpn.copy(profiles = preset.vpn.profiles.map { stashProfile(it) })))
    }

    override fun deletePreset(id: String) = delegate.deletePreset(id)

    /** Уводит секреты профиля в хранилище; не вышло -- профиль возвращается как есть. */
    private fun stashProfile(profile: VpnProfile): VpnProfile {
        if (profile.id.isEmpty()) return profile
        val secret = profile.privateKey.isNotEmpty() ||
            profile.presharedKey.isNotEmpty() ||
            profile.certPassword.isNotEmpty()
        if (!secret) return profile
        // wg-ini импорт хранит ключ и в тексте конфига -- уводим и его.
        val raw = if ("PrivateKey=" in profile.rawConfig) profile.rawConfig else ""
        val encoded = runCatching {
            ConfigCodec.json.encodeToString(
                KeyBundle.serializer(),
                KeyBundle(
                    privateKey = profile.privateKey,
                    presharedKey = profile.presharedKey,
                    certPassword = profile.certPassword,
                    rawConfig = raw
                )
            )
        }.getOrNull() ?: return profile
        if (!storage.put(slotFor(profile.id), encoded)) return profile
        return profile.copy(
            privateKey = "",
            presharedKey = "",
            certPassword = "",
            rawConfig = if (raw.isNotEmpty()) "" else profile.rawConfig
        )
    }

    /** Собирает секреты обратно; повреждённый или отсутствующий слот -- не ошибка. */
    private fun restoreProfile(profile: VpnProfile): VpnProfile {
        if (profile.id.isEmpty()) return profile
        val empty = profile.privateKey.isEmpty() &&
            profile.presharedKey.isEmpty() &&
            profile.certPassword.isEmpty()
        if (!empty) return profile
        val bundle = storage.get(slotFor(profile.id))
            ?.let { runCatching { ConfigCodec.json.decodeFromString(KeyBundle.serializer(), it) }.getOrNull() }
            ?: return profile
        return profile.copy(
            privateKey = bundle.privateKey,
            presharedKey = bundle.presharedKey,
            certPassword = bundle.certPassword,
            rawConfig = if (bundle.rawConfig.isNotEmpty()) bundle.rawConfig else profile.rawConfig
        )
    }

    private fun slotFor(id: String): String = "vpn:$id"
}

/**
 * Файловый обмен пресетами.
 *
 * Отдельный интерфейс, потому что экспорт и импорт -- единственное место, где
 * приложение обращается к внешнему миру.
 *
 * Методы `suspend` не из удобства, а из необходимости: на Android файл
 * выбирается системным диалогом и становится доступен только после возврата
 * из него, то есть после `onActivityResult`. Синхронный метод физически не
 * может дождаться результата и вынужден был бы возвращать пустоту.
 */
interface PresetFileGateway {
    /** Открывает выбор файла и отдаёт содержимое; null, если пользователь отказал. */
    suspend fun read(): String?

    /** Открывает выбор файла и записывает содержимое; false, если отказали или запись не вышла. */
    suspend fun write(content: String, suggestedName: String): Boolean

    /** Доступен ли файловый обмен на этой платформе прямо сейчас. */
    val available: Boolean
}

expect fun createPresetFileGateway(): PresetFileGateway

/** Формат файла пресета. */
@Serializable
data class PresetFileEnvelope(
    val kind: String = "zapp-preset",
    val version: Int = 1,
    val preset: Preset = Preset()
) {
    companion object {
        const val KIND: String = "zapp-preset"
    }
}

/**
 * Импорт и экспорт пресетов.
 *
 * Файл оборачивается в [PresetFileEnvelope], а не пишется «в лоб», по двум
 * причинам: появляется место для версии формата, и импорт пресета можно
 * отличить от импорта целых настроек, если формат когда-нибудь станет общим.
 */
object PresetFiles {
    fun export(preset: Preset): String =
        ConfigCodec.json.encodeToString(
            PresetFileEnvelope.serializer(),
            PresetFileEnvelope(preset = preset)
        )

    /**
     * Возвращает пресет или null, если файл не наш или поле повреждено.
     *
     * Принимаются два вида: конверт с полями `kind`/`preset` и «голый» пресет.
     * Второй вариант нужен для файлов, которые положили руками или выгрузили
     * другой версией приложения, -- отказывать в них молча было бы хуже, чем
     * принять.
     */
    fun import(text: String): Preset? {
        val envelope = runCatching {
            ConfigCodec.json.decodeFromString(PresetFileEnvelope.serializer(), text)
        }.getOrNull()

        if (envelope != null) {
            return if (envelope.kind == PresetFileEnvelope.KIND) envelope.preset else null
        }
        return ConfigCodec.decodePresetOrNull(text)
    }

    fun suggestedName(preset: Preset): String {
        val safe = preset.name.trim().ifEmpty { "preset" }
            .replace(Regex("[^A-Za-z0-9._-]+"), "_")
        return "$safe.zapp.json"
    }
}
