package xyz.azraellab.zapp.core.update

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import xyz.azraellab.zapp.core.net.httpGetText

/**
 * Версия приложения.
 *
 * Одна константа здесь, а не разбросанные строки: версия показывается в
 * настройках и сравнивается с тегом релиза, и расхождение в одном знаке
 * заставило бы пользователя думать, что обновление сломано.
 */
const val APP_VERSION: String = "0.1.0"

/** Ссылка на последний официальный релиз: prerelease сюда не попадают. */
private const val RELEASES_URL = "https://api.github.com/repos/PAF-DE0H1SS/ZAPP/releases/latest"

/** Опубликованный релиз. */
data class ReleaseInfo(
    /** Тег вида `v0.2.0`; для сравнения чистится от `v`. */
    val version: String,
    /** Страница релиза, куда ведёт кнопка «Открыть». */
    val url: String,
    /** Название релиза, если автор его указал. */
    val name: String = "",
    val publishedAt: String = ""
)

/** Результат последней проверки обновлений. */
enum class UpdateState {
    /** Ещё не проверяли. */
    UNKNOWN,

    /** Запрос в полёте. */
    CHECKING,

    /** Установленная версия -- свежайшая. */
    CURRENT,

    /** Вышла новая версия; подробности в поле релиза. */
    AVAILABLE,

    /** Нет сети или сервис ответил ошибкой. */
    ERROR
}

private val RELEASE_JSON = Json { ignoreUnknownKeys = true }

/**
 * Запрашивает последний релиз.
 *
 * Эндпоинт `/releases/latest` отдаёт только обычные релизы: пре-релизы и
 * черновики в него не входят, поэтому проверка не может поймать dev-сборку
 * и предложить её как обновление.
 */
suspend fun fetchLatestRelease(): ReleaseInfo? {
    val body = httpGetText(RELEASES_URL) ?: return null
    return runCatching {
        val root = RELEASE_JSON.parseToJsonElement(body).jsonObject
        fun field(name: String): String =
            root[name]?.jsonPrimitive?.contentOrNull() ?: ""
        ReleaseInfo(
            version = field("tag_name").ifBlank { return null },
            url = field("html_url").ifBlank { return null },
            name = field("name"),
            publishedAt = field("published_at")
        )
    }.getOrNull()
}

/**
 * Насколько [candidate] свежее [current].
 *
 * Сравнение поблочное по числам, а не строками: `0.10.0` новее `0.9.0`,
 * хотя строками наоборот. Не-числовые куски считаются нулём -- так тег
 * `v2.0-rc1` не уедет в бесконечно большое число.
 */
fun isNewerVersion(candidate: String, current: String): Boolean {
    val a = candidate.trim().removePrefix("v").removePrefix("V")
    val b = current.trim().removePrefix("v").removePrefix("V")
    if (a.isEmpty() || b.isEmpty()) return false
    if (a.equals(b, ignoreCase = true)) return false

    // Базис без пререлизной части: `-rc.1` и подобные суффиксы сравниваются
    // отдельно, иначе `0.1.0-rc.1` обогнало бы собственный релиз `0.1.0`.
    val baseA = a.substringBefore('-').substringBefore('+')
    val baseB = b.substringBefore('-').substringBefore('+')
    val partsA = baseA.split('.').map { it.toLongOrNull() ?: 0L }
    val partsB = baseB.split('.').map { it.toLongOrNull() ?: 0L }

    val size = maxOf(partsA.size, partsB.size)
    for (i in 0 until size) {
        val left = partsA.getOrElse(i) { 0L }
        val right = partsB.getOrElse(i) { 0L }
        if (left != right) return left > right
    }

    // Базисы равны: пререлиз (`-rc`) старше своего релиза, поэтому
    // обновлением считается только переход «пререлиз -> релиз».
    val prereleaseA = a.length > baseA.length
    val prereleaseB = b.length > baseB.length
    if (prereleaseA != prereleaseB) return !prereleaseA && prereleaseB
    return false
}

private fun kotlinx.serialization.json.JsonPrimitive.contentOrNull(): String? =
    runCatching { content }.getOrNull()
