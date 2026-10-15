package xyz.azraellab.zapp.core.update

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import xyz.azraellab.zapp.core.log.AppLog
import xyz.azraellab.zapp.core.log.causeChain
import xyz.azraellab.zapp.core.net.httpGetText

/**
 * Версия приложения.
 *
 * Константа генерируется сборкой из `gradle.properties` (см. `generateAppVersion`
 * в build.gradle.kts): одна правка версии кормит и versionName APK, и код.
 * Раньше константа жила здесь руками и застыла на 2.7.3 — приложение считало
 * себя старым и в проверке обновлений предлагало установить уже установленную
 * версию.
 */
// APP_VERSION: см. сгенерированный AppVersion.kt (пакет xyz.azraellab.zapp.core.update)

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
    val publishedAt: String = "",
    /**
     * Прямая ссылка на APK первого ассета релиза.
     *
     * Пусто, если релиз без APK (сборка не выложена): тогда остаётся
     * только кнопка «Открыть страницу релиза».
     */
    val apkUrl: String = ""
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

/** Режимы сравнения версий в настройках обновлений. */
const val UPDATE_COMPARE_SEMVER: String = "semver"
const val UPDATE_COMPARE_TAG: String = "tag"

/**
 * Состояние загрузки APK обновления.
 *
 * Готовность отдельным состоянием не хранится: она читается с диска
 * (закешированный файл переживает перезапуск), а enum отвечает только
 * за то, идёт ли загрузка сейчас и была ли неудача.
 */
enum class UpdateDownload {
    /** Ничего не идёт; кеш может быть пуст или уже полон. */
    NONE,

    /** Загрузка в полёте. */
    RUNNING,

    /** Последняя загрузка не удалась (причина в updateDownloadError). */
    ERROR
}

/**
 * Есть ли обновление выбранной режимом [mode] строкой.
 *
 * Два режима вместо одного навсегда: пока теги проекта числовые,
 * побеждает семвер; как только автор выпустит тег вида `beta-next`,
 * семвер молча не увидит изменение -- режим «по смене тега» считает
 * обновлением любое отличие от установленной версии.
 */
fun updateAvailable(candidate: String, current: String, mode: String): Boolean = when (mode) {
    UPDATE_COMPARE_TAG -> {
        val a = candidate.trim().removePrefix("v").removePrefix("V")
        val b = current.trim().removePrefix("v").removePrefix("V")
        a.isNotEmpty() && b.isNotEmpty() && !a.equals(b, ignoreCase = true)
    }

    else -> isNewerVersion(candidate, current)
}

/**
 * Конец локальных суток в эпохе: момент наступления завтрашнего дня.
 *
 * «Не напоминать сегодня» живёт до полуночи по времени устройства, а не
 * через 24 часа: утренний тап отложил бы напоминание до вечера и оно
 * прозвучало бы всё ещё сегодня.
 */
fun localDayEndMs(nowMs: Long): Long {
    val offset = java.util.TimeZone.getDefault().getOffset(nowMs)
    val local = nowMs + offset
    val dayEndLocal = (local / 86_400_000L + 1) * 86_400_000L
    return dayEndLocal - offset
}

/**
 * Запрашивает последний релиз.
 *
 * Эндпоинт `/releases/latest` отдаёт только обычные релизы: пре-релизы и
 * черновики в него не входят, поэтому проверка не может поймать dev-сборку
 * и предложить её как обновление.
 */
suspend fun fetchLatestRelease(): ReleaseInfo? {
    // Каждый путь возврата null логируется со своей причиной: раньше все
    // три сливались в «unknown», и багрепорт «обновления не проверяются»
    // ничего не говорил -- сеть это, пустой тег релиза или битый JSON.
    val body = httpGetText(RELEASES_URL)
    if (body == null) {
        AppLog.log("update", "check: no response from GitHub (сеть или таймаут)")
        return null
    }
    return runCatching {
        val root = RELEASE_JSON.parseToJsonElement(body).jsonObject
        fun field(name: String): String =
            root[name]?.jsonPrimitive?.contentOrNull() ?: ""
        val tag = field("tag_name")
        if (tag.isBlank()) {
            AppLog.log("update", "check: пустой tag_name в ответе релиза")
            return null
        }
        val url = field("html_url")
        if (url.isBlank()) {
            AppLog.log("update", "check: пустой html_url в ответе релиза")
            return null
        }
        ReleaseInfo(
            version = tag,
            url = url,
            name = field("name"),
            publishedAt = field("published_at"),
            apkUrl = runCatching {
                root["assets"]?.jsonArray
                    ?.mapNotNull { it.jsonObject["browser_download_url"]?.jsonPrimitive?.contentOrNull() }
                    ?.firstOrNull { it.endsWith(".apk", ignoreCase = true) }
                    ?: ""
            }.getOrDefault("")
        )
    }.getOrElse { e ->
        // Ошибка разбора: GitHub мог вернуть HTML ошибки или заглушку
        // блокировщика -- в журнале видно, что именно пришло.
        AppLog.log("update", "check: разбор ответа не удался: ${e.causeChain().ifEmpty { e::class.simpleName ?: "error" }}")
        null
    }
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
