package xyz.azraellab.zapp.core.log

import xyz.azraellab.zapp.core.Str
import xyz.azraellab.zapp.core.daemon.DaemonEvent
import xyz.azraellab.zapp.core.gps.GpsEvent

/**
 * Разбор журналов на предмет причины сбоя.
 *
 * Состояние ERROR само по себе ничего не объясняет: «Ошибка» без строки
 * причины заставляет гадать, что именно сломалось. Эти функции -- чистая
 * логика выбора строки (без Compose и языка), поэтому она живёт в core и
 * проверяется тестами; перевод и показ -- уже дело UI.
 *
 * Правило выбора одно для всех журналов: сначала явная ошибка, потом
 * событие-причина, потом любая осмысленная строка. Молчаливый fallback
 * «последняя строка» допустим только как крайний случай: лучше увидеть
 * «Exit code 1», чем строку «connecting».
 */

/** Признаки ошибки в сырой строке вывода демона или журнала туннеля. */
private val ERROR_MARKERS = listOf(
    "error",
    "failed",
    "failure",
    "fatal",
    "panic",
    "exception",
    "denied",
    "not permitted",
    "no such file",
    "cannot",
    "couldn't",
    "could not",
    "unable",
    "unknown option",
    "invalid",
    "refused",
    "timed out",
    "timeout",
    "no route",
    "unreachable",
    "already in use",
    "not found",
    "[err",
    "exit code"
)

/** ANSI-последовательности: снимаем до разбора, метки уровня с ними не видны. */
private val ANSI = Regex("""\u001B\[[0-9;]*[a-zA-Z]""")

/**
 * Уровень лога sing-box в строке: `+0000 2026-10-06 11:57:04 ERROR ...`.
 *
 * Требуются и метка времени, и УПЕРКА-регистр: так случайная слово «INFO»
 * в середине сообщения не классифицирует строку как служебную.
 */
private val LEVEL_TAG = Regex("""\d{2}:\d{2}:\d{2}[^A-Za-z]*(TRACE|DEBUG|INFO|WARN|ERROR)\b""")

/**
 * DNS-статус NOERROR -- это УСПЕХ, но он содержит подстроку «error».
 *
 * sing-box пишет `dns: exchanged host NOERROR 838` на каждое успешное
 * разрешение: без маски половина «журнала ошибок» была бы DNS-трафиком.
 */
private const val DNS_OK_TOKEN = "noerror"

/**
 * Выглядит ли строка ошибкой. Регистр не важен: демоны пишут по-разному.
 *
 * Уровень важнее маркеров: `DEBUG`-строка health-пробы urltest («unavailable:
 * i/o timeout») -- диагностика живости узлов, а не сбой сессии, и в багрепорт
 * она только мешает. Строка без уровня (tor, openvpn, stderr) решается
 * маркерами, как и раньше.
 */
fun looksLikeErrorLine(line: String): Boolean {
    if (line.isBlank()) return false
    val plain = ANSI.replace(line, "")
    when (LEVEL_TAG.find(plain)?.groupValues?.get(1)) {
        "ERROR" -> return true
        "DEBUG", "INFO", "TRACE" -> return false
        // WARN и строки без уровня -- решают маркеры.
    }
    val lower = plain.lowercase().replace(DNS_OK_TOKEN, "")
    return ERROR_MARKERS.any { lower.contains(it) }
}

/**
 * Причина сбоя из журнала туннеля (строки, newest в конце).
 *
 * Ищется с конца: свежая попытка подключения важнее прошлой. Если
 * явной ошибки нет, возвращается последняя непустая строка -- state
 * ERROR без объяснения хуже любого объяснения.
 */
fun List<String>.errorLine(): String? {
    val lines = asReversed()
    return lines.firstOrNull { looksLikeErrorLine(it) }
        ?: lines.firstOrNull { it.isNotBlank() }
}

/** События приложения, которые сами по себе означают сбой демона. */
private val DAEMON_ERROR_TEXTS = setOf(
    Str.DAEMON_LOG_EXIT,
    Str.DAEMON_NO_BINARY,
    Str.DAEMON_FILES_FAILED,
    Str.DAEMON_NO_SNI
)

/** Служебные события: причину сбоя из них собирать нельзя. */
private val DAEMON_INFO_TEXTS = setOf(
    Str.DAEMON_LOG_STARTING,
    Str.DAEMON_LOG_RUNNING,
    Str.DAEMON_LOG_STOPPED
)

/** Событие демона само по себе ошибка (а не строка обычного вывода). */
fun DaemonEvent.isError(): Boolean =
    if (raw.isNotEmpty()) looksLikeErrorLine(raw) else text != null && text in DAEMON_ERROR_TEXTS

/**
 * Причина сбоя демона.
 *
 * Сырой вывод предпочтительнее событий приложения: «[err] Reading config
 * failed» объясняет больше, чем «Exit code 1». Событие-причина (код выхода,
 * отсутствующий бинарь) идёт следом, и только потом -- любая строка вывода.
 * Служебные «starting/running/stopped» последним fallback'ом не берутся:
 * они никогда не объясняют сбой.
 */
fun List<DaemonEvent>.errorEvent(): DaemonEvent? {
    val events = asReversed()
    events.firstOrNull { it.raw.isNotEmpty() && looksLikeErrorLine(it.raw) }?.let { return it }
    events.firstOrNull { it.text != null && it.text in DAEMON_ERROR_TEXTS }?.let { return it }
    events.firstOrNull { it.raw.isNotEmpty() }?.let { return it }
    return events.firstOrNull { it.text != null && it.text !in DAEMON_INFO_TEXTS }
}

/** События GPS, которые означают сбой подмены. */
private val GPS_ERROR_TEXTS = setOf(
    Str.GPS_LOG_NO_MOCK,
    Str.GPS_LOG_NO_GATEWAY,
    Str.GPS_LOG_RESOLVE_FAIL,
    Str.GPS_LOG_ROUTE_EMPTY,
    Str.GPS_LOG_PROVIDER_ERROR,
    Str.GPS_LOG_NO_PROVIDER,
    Str.GPS_LOG_START_FAILED
)

/** Событие журнала GPS само по себе ошибка. */
fun GpsEvent.isError(): Boolean = text in GPS_ERROR_TEXTS

/**
 * Причина сбоя подмены координат.
 *
 * Как у демона: сначала событие-причина, потом любое событие, кроме
 * служебных «старт» и «остановка» -- они сопровождают и успех, и провал.
 */
fun List<GpsEvent>.errorEvent(): GpsEvent? {
    val events = asReversed()
    events.firstOrNull { it.isError() }?.let { return it }
    return events.firstOrNull { it.text != Str.GPS_LOG_STARTING && it.text != Str.GPS_LOG_STOPPED }
}

// --- Одна строка диагностики ---

/**
 * Цепочка причин исключения -- до [limit] звеньев через «→».
 *
 * «start failed: Connection refused» ничего не говорит о том, что именно
 * отвергло соединение; цепочка `корень → cause → cause2` -- говорит. Сквозь
 * обёртки (runCatching, суперклассы) ходим по `cause`, дубликаты и пустые
 * сообщения отбрасываем: строка должна быть короткой и без повторов.
 */
fun Throwable.causeChain(limit: Int = 4): String =
    generateSequence(this) { it.cause }
        .mapNotNull { it.message?.takeIf { msg -> msg.isNotBlank() } }
        .distinct()
        .take(limit)
        .joinToString(" → ")

/**
 * Длинные токены в строке причины -- под маску.
 *
 * Конфиги коннектов -- это ключи Reality, пароли и прочие base64-плёнки:
 * диагностика показывается под статусом и в уведомлениях, и без маски
 * секрет уезжал бы туда, где его не должно быть видно. Маскируется
 * всё подозрительно длинное «слово» токенного алфавита; обычные пути,
 * имена и текст короче -- не трогаются.
 */
private val LONG_TOKEN = Regex("""[A-Za-z0-9+/=_-]{40,}""")

fun redactSensitive(line: String): String = LONG_TOKEN.replace(line, "***")

/**
 * Последняя ошибка одной строкой: `время · компонент · цепочка причин`.
 *
 * Тот приём, что делает ошибку читаемой без раскрытия журнала: видно,
 * когда, где и из-за чего всё сломалось. Пустые части пропускаются
 * (время может быть неизвестно), причин берётся не больше четырёх,
 * каждая проходит через [redactSensitive].
 */
fun diagnosisLine(time: String, component: String, causes: List<String>): String {
    val chain = causes.asSequence()
        .map { redactSensitive(it).trim() }
        .filter { it.isNotEmpty() }
        .distinct()
        .take(4)
        .joinToString(" → ")
    return listOf(time.trim(), component.trim(), chain)
        .filter { it.isNotEmpty() }
        .joinToString(" · ")
}
