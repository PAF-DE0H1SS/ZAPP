package xyz.azraellab.zapp.core

import kotlinx.serialization.Serializable

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
