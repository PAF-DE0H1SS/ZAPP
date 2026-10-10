package xyz.azraellab.zapp.core.routing

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Детальная настройка маршрутизации: секции, каскады и фильтры автоподбора.
 *
 * Секция -- именованное правило поверх базовой маршрутизации туннеля:
 * список доменов/сетей/портов плюс действие (через туннель, напрямую,
 * отклонить, свой DNS). Каскад -- цепочка узлов, соединённых
 * detour: трафик выходит через последний узел, а каждый предыдущий
 * дозванивается до следующего. Фильтр -- какие узлы участвуют в
 * urltest-группе: имя, флаг страны или регулярное выражение.
 *
 * Все три живут в [xyz.azraellab.zapp.core.VpnConfig] и применяются в
 * [xyz.azraellab.zapp.core.net.SingboxConfig]: одно место правды для
 * движка, UI и тестов.
 */

/** Действие секции. Коды -- в JSON (`connection`), не имена enum. */
@Serializable
enum class SectionAction(val code: String) {
    /** Трафик секции идёт в основной outbound туннеля. */
    @SerialName("connection")
    CONNECTION("connection"),

    /** Трафик секции обходит туннель напрямую. */
    @SerialName("direct")
    DIRECT("direct"),

    /** Трафик секции отклоняется. */
    @SerialName("block")
    BLOCK("block"),

    /** DNS-резолвинг секции уходит на свой сервер (маршрут не меняется). */
    @SerialName("dns")
    DNS("dns");

    companion object {
        fun of(code: String?): SectionAction =
            entries.firstOrNull { it.code == code?.trim()?.lowercase() } ?: CONNECTION
    }
}

/**
 * Правило маршрутизации: условия и действие.
 *
 * Пустые условия валидны и означают «весь трафик» -- так же, как в
 * sing-box: правило без матчеров совпадает со всем. UI показывает
 * примечание, чтобы это не было сюрпризом.
 */
@Serializable
data class RouteSection(
    /** Имя для журнала и списка; пустое имя секцию выключает. */
    val label: String = "",
    val enabled: Boolean = true,
    val action: SectionAction = SectionAction.CONNECTION,

    /**
     * Домены через запятую или перенос строки.
     *
     * Префикс определяет тип совпадения: `full:` -- точное имя,
     * `keyword:` -- подстрока, `regex:` -- регулярное выражение; без
     * префикса -- суффикс домена (youtube.com ловит и www.youtube.com).
     */
    val domains: String = "",
    /** Подсети CIDR через запятую. */
    val ipCidrs: String = "",
    /** Порты через запятую: `443` или диапазон `8000-9000`. */
    val ports: String = "",
    /** Транспорт: `any` (по умолчанию), `tcp` или `udp`. */
    val network: String = "any",

    /**
     * Разрешить домены в реальный IP перед маршрутизацией.
     *
     * Нужно, когда правило фильтрует по домену, а маршрутизация должна
     * смотреть на адрес: например, блокировка по IP-попаданию после
     * резолва. Работает только с доменными условиями.
     */
    val resolveRealIp: Boolean = false,

    // --- Только action=dns ---
    /** Тип DNS-сервера секции: `udp`, `tcp`, `tls` (DoT) или `https` (DoH). */
    val dnsType: String = "udp",
    /** Адрес DNS-сервера секции: `host`, `host:port` или `https://host/path`. */
    val dnsServer: String = "",
) {
    /** Секция участвует, когда включена и названа. */
    fun active(): Boolean = enabled && label.isNotBlank()

    /** Есть ли вообще условия (для подсказки в UI). */
    fun hasMatchers(): Boolean =
        domains.isNotBlank() || ipCidrs.isNotBlank() || ports.isNotBlank() ||
            network.lowercase() !in setOf("", "any")
}

/** Разобранные доменные условия одного правила. */
data class ParsedDomains(
    val exact: List<String> = emptyList(),
    val suffix: List<String> = emptyList(),
    val keyword: List<String> = emptyList(),
    val regex: List<String> = emptyList()
) {
    fun isEmpty(): Boolean = exact.isEmpty() && suffix.isEmpty() && keyword.isEmpty() && regex.isEmpty()
    fun isNotEmpty(): Boolean = !isEmpty()
}

/** Разобранные порты: одиночные числа и диапазоны в формате sing-box `start:end`. */
data class ParsedPorts(
    val ports: List<Int> = emptyList(),
    val ranges: List<String> = emptyList()
) {
    fun isEmpty(): Boolean = ports.isEmpty() && ranges.isEmpty()
}

/**
 * Разбор доменных условий секции.
 *
 * Разделители -- запятая и перенос строки (одно поле ввода, много
 * условий). Пустые куски пропускаются, повторяющиеся префиксы
 * (`full:full:x`) срезаются -- как в исходном синтаксисе.
 */
fun parseSectionDomains(text: String): ParsedDomains {
    val exact = mutableListOf<String>()
    val suffix = mutableListOf<String>()
    val keyword = mutableListOf<String>()
    val regex = mutableListOf<String>()

    for (raw in text.split(',', '\n', '\r')) {
        var item = raw.trim()
        if (item.isEmpty()) continue
        val kind: String
        val colon = item.indexOf(':')
        if (colon > 0) {
            val prefix = item.substring(0, colon).trim().lowercase()
            if (prefix !in setOf("full", "keyword", "regex")) continue
            kind = prefix
            item = item.substring(colon + 1).trim()
            // Повторный префикс: "full:full:x" == "full:x".
            val inner = "$kind:"
            if (item.length > inner.length && item.take(inner.length).lowercase() == inner) {
                item = item.substring(inner.length).trim()
            }
        } else {
            kind = "suffix"
        }
        if (item.isEmpty()) continue
        when (kind) {
            "full" -> exact += item
            "keyword" -> keyword += item
            "regex" -> regex += item
            else -> suffix += item
        }
    }
    return ParsedDomains(
        exact = exact.distinct(),
        suffix = suffix.distinct(),
        keyword = keyword.distinct(),
        regex = regex.distinct()
    )
}

/** Разбор портов: одиночные числа 1..65535 и диапазоны `a-b` (равные края -- точка). */
fun parseSectionPorts(text: String): ParsedPorts {
    val ports = mutableListOf<Int>()
    val ranges = mutableListOf<String>()
    for (raw in text.split(',', '\n', '\r', ' ', '\t')) {
        val item = raw.trim()
        if (item.isEmpty()) continue
        val dash = item.indexOf('-')
        if (dash < 0) {
            val number = item.toIntOrNull()
            if (number != null && number in 1..65535) ports += number
            continue
        }
        val start = item.substring(0, dash).trim().toIntOrNull()
        val end = item.substring(dash + 1).trim().toIntOrNull()
        if (start == null || end == null) continue
        if (start !in 1..65535 || end !in 1..65535 || start > end) continue
        if (start == end) ports += start else ranges += "$start:$end"
    }
    return ParsedPorts(ports = ports.distinct(), ranges = ranges.distinct())
}

/** Список слов через запятую/пробелы: подсети, hop'ы, имена узлов. */
fun parseWords(text: String): List<String> =
    text.split(',', '\n', '\r', ' ', '\t')
        .map { it.trim() }
        .filter { it.isNotEmpty() }
        .distinct()

/**
 * Разбор duration-строки sing-box в секунды: `30s`, `1m`, `2h`, `1d`
 * или голое число (уже секунды). Мусор -- null.
 */
fun durationSeconds(text: String): Int? {
    val value = text.trim().lowercase()
    if (value.isEmpty()) return null
    if (value.all { it.isDigit() }) return value.toIntOrNull()
    val unit = value.last()
    if (unit !in setOf('s', 'm', 'h', 'd')) return null
    val number = value.dropLast(1).toIntOrNull() ?: return null
    return when (unit) {
        's' -> number
        'm' -> number * 60
        'h' -> number * 3600
        else -> number * 86400
    }
}

/** Обратная операция: секунды -> duration-строка (`60` -> `60s`). */
fun durationString(seconds: Int): String = "${seconds.coerceAtLeast(0)}s"

/**
 * Каскад: цепочка узлов через запятую от входа к выходу.
 *
 * Первый hop -- вход: клиент подключается к нему, его outbound и есть
 * финал маршрута. Последний hop -- выходной: он дозванивается до сети
 * напрямую, каждый узел до него -- через следующий за ним (detour).
 */
@Serializable
data class ProxyCascade(
    val label: String = "",
    /** Hop'ы через запятую, порядок -- от входа к выходу. */
    val hops: String = ""
) {
    fun active(): Boolean = label.isNotBlank() && hopList().isNotEmpty()
    fun hopList(): List<String> = parseWords(hops)
}

/**
 * Фильтр узлов urltest-группы.
 *
 * Режим определяет, какие списки работают: `include` -- только
 * включение, `exclude` -- только исключение, `mixed` -- включение
 * минус исключение, `disabled` -- фильтр выключен, участвуют все узлы.
 * Совпадение по имени, по коду страны (двухбуквенный флаг в имени
 * узла) или по регулярному выражению -- любое из трёх достаточно.
 */
@Serializable
data class UrlTestFilter(
    val mode: String = MODE_DISABLED,
    /** Имена для включения, через запятую. */
    val includeNames: String = "",
    /** Регулярные выражения для включения, через запятую. */
    val includeRegex: String = "",
    /** Коды стран для включения (`DE`, `NL`), через запятую. */
    val includeCountries: String = "",
    /** Имена для исключения, через запятую. */
    val excludeNames: String = "",
    /** Регулярные выражения для исключения, через запятую. */
    val excludeRegex: String = "",
    /** Коды стран для исключения, через запятую. */
    val excludeCountries: String = "",

    /** URL проверки скорости группы; пусто -- значение по умолчанию sing-box. */
    val testingUrl: String = "",
    /** Период перепроверки: duration-строка sing-box (`1m`, `30s`). */
    val interval: String = "1m",
    /** Допуск переключения в мс: переключаться только заметно быстрее. */
    val toleranceMs: Int = 10,
    /** Простой группы перед засыпанием: duration-строка, пусто -- не задан. */
    val idleTimeout: String = ""
) {
    fun configured(): Boolean = mode != MODE_DISABLED

    companion object {
        const val MODE_DISABLED = "disabled"
        const val MODE_INCLUDE = "include"
        const val MODE_EXCLUDE = "exclude"
        const val MODE_MIXED = "mixed"
    }
}

/**
 * Отфильтрованный список тегов группы автоподбора.
 *
 * Пустой результат фильтра -- не «группа падает», а «фильтр не нашёл
 * ни одного узла»: группа остаётся со всеми тегами, иначе один
 * неверно введённый паттерн молча выключал бы автовыбор.
 */
fun filterUrltestTags(tags: List<String>, filter: UrlTestFilter): List<String> {
    if (tags.isEmpty() || !filter.configured()) return tags.distinct()

    val includeNames = parseWords(filter.includeNames).toSet()
    val excludeNames = parseWords(filter.excludeNames).toSet()
    val includeCountries = parseWords(filter.includeCountries).map { it.uppercase() }.toSet()
    val excludeCountries = parseWords(filter.excludeCountries).map { it.uppercase() }.toSet()
    val includeRegex = compileAll(filter.includeRegex)
    val excludeRegex = compileAll(filter.excludeRegex)

    fun matchesInclude(tag: String): Boolean =
        tag in includeNames ||
            countryFromFlag(tag) in includeCountries && includeCountries.isNotEmpty() ||
            includeRegex.any { it.matches(tag) }

    fun matchesExclude(tag: String): Boolean =
        tag in excludeNames ||
            (excludeCountries.isNotEmpty() && countryFromFlag(tag) in excludeCountries) ||
            excludeRegex.any { it.matches(tag) }

    val includeUsed = filter.mode == UrlTestFilter.MODE_INCLUDE || filter.mode == UrlTestFilter.MODE_MIXED
    val excludeUsed = filter.mode == UrlTestFilter.MODE_EXCLUDE || filter.mode == UrlTestFilter.MODE_MIXED

    val result = tags.distinct().filter { tag ->
        val included = !includeUsed || matchesInclude(tag)
        included && (!excludeUsed || !matchesExclude(tag))
    }
    return result.ifEmpty { tags.distinct() }
}

private fun compileAll(text: String): List<Regex> =
    parseWords(text).mapNotNull { runCatching { Regex(it) }.getOrNull() }

/**
 * Страна из флага в имени узла: первая пара региональных индикаторов
 * (`🇩🇪` -> `DE`). Нет пары -- пустая строка.
 *
 * Символы -- суррогатные пары UTF-16: региональный индикатор
 * U+1F1E6..U+1F1FF пишется как `\uD83C\uDDE6`..`\uD83C\uDFFF`.
 */
fun countryFromFlag(name: String): String {
    var i = 0
    var first: Char? = null
    while (i + 1 < name.length) {
        if (name[i] == '\uD83C' && name[i + 1] in '\uDDE6'..'\uDFFF') {
            val letter = ('A'.code + (name[i + 1].code - '\uDDE6'.code)).toChar()
            if (first == null) {
                first = letter
            } else {
                return "$first$letter"
            }
            i += 2
        } else {
            if (first != null) return ""
            i += 1
        }
    }
    return ""
}
