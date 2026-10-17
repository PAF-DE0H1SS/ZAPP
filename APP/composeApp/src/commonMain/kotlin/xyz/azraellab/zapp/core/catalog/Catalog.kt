package xyz.azraellab.zapp.core.catalog

import kotlinx.serialization.Serializable
import xyz.azraellab.zapp.core.ConfigCodec
import xyz.azraellab.zapp.core.Preset
import xyz.azraellab.zapp.core.PresetFiles
import xyz.azraellab.zapp.core.net.httpGetText
import xyz.azraellab.zapp.core.update.isNewerVersion

/** Одна запись каталога: пресет обхода, который можно скачать и применить. */
@Serializable
data class CatalogEntry(
    val id: String = "",
    val name: String = "",
    val description: String = "",
    /** Версия пресета; пусто -- сравнение невозможно. */
    val version: String = "",
    /** Страница с описанием изменений; пусто -- кнопки changelog нет. */
    val changelogUrl: String = "",
    /** Прямая ссылка на JSON пресета (формат [PresetFiles]). */
    val presetUrl: String = ""
)

/** Фид каталога: список записей и метка версии самого фида. */
@Serializable
data class CatalogFeed(
    val feedVersion: String = "",
    val entries: List<CatalogEntry> = emptyList()
)

/**
 * Каталог пресетов обхода поверх JSON-фида.
 *
 * Фид -- обычный JSON со списком записей; каждая запись ведёт на JSON
 * пресета. Разбор отделён от сети ([parseFeed] чистый), чтобы тесты не
 * ходили в интернет, а реальный запрос -- тонкая обёртка [httpGetText].
 */
object CatalogService {

    private val JSON = kotlinx.serialization.json.Json { ignoreUnknownKeys = true }

    /** Разбирает фид; null -- не JSON, не наш формат или пусто. */
    fun parseFeed(text: String): CatalogFeed? = runCatching {
        JSON.decodeFromString(CatalogFeed.serializer(), text)
    }.getOrNull()

    /** Тянет фид по ссылке; null -- сети нет или ответ битый. */
    suspend fun fetchFeed(url: String): CatalogFeed? {
        if (url.isBlank()) return null
        val body = httpGetText(url) ?: return null
        return parseFeed(body)
    }

    /** Скачивает JSON пресета по ссылке; null -- битый или чужой файл. */
    suspend fun fetchPreset(url: String): Preset? {
        if (url.isBlank()) return null
        val body = httpGetText(url) ?: return null
        return runCatching { PresetFiles.import(body) }.getOrNull()
    }

    /** Есть ли у записи версия новее установленной. */
    fun hasUpdate(entry: CatalogEntry, installedVersion: String): Boolean =
        entry.version.isNotBlank() && installedVersion.isNotBlank() &&
            isNewerVersion(entry.version, installedVersion)
}
