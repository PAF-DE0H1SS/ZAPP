package xyz.azraellab.zapp.core.log

import xyz.azraellab.zapp.core.Str

/**
 * Прогресс сборки Tor в журнале туннеля.
 *
 * B1: проценты в журнале уже есть (движок пишет `tor bootstrap N%`), но на
 * карточке их никто не показывал -- «Подключение...» висело минутами без
 * единого обновления, и зависание неотличимо от работы. Здесь только разбор
 * готовой строки: показ и перевод -- дело UI, как у причин сбоя в [errorLine].
 *
 * @param percent 0..100; -1 не бывает: класс создаётся только по разобранной строке.
 * @param stage тег стадии tor'а (`loading_descriptors`), без переводных скобок.
 */
data class BootstrapProgress(val percent: Int, val stage: String?)

/**
 * Строка прогресса из журнала туннеля: `tor bootstrap 45% (requesting_descriptors)`.
 *
 * Скобки со стадией опциональны: старые строки без них валидны. Прочие строки
 * с этим префиксом (`tor bootstrap, socks:9050`, `tor bootstrap timeout (300s)`)
 * под шаблон не попадают -- после «bootstrap» обязано идти число с процентом.
 */
private val BOOTSTRAP_LINE = Regex("""tor bootstrap (\d+)%(?: \((\w+)\))?""")

/**
 * Последний прогресс сборки Tor из журнала; null, если в текущей сессии
 * процентов ещё не было.
 *
 * Граница сессии -- последняя строка `connecting`, которую пишет движок при
 * старте: строки до неё принадлежат прошлой попытке, и её 100% не должны
 * выдаваться за готовность новой.
 */
fun List<String>.bootstrapProgress(): BootstrapProgress? {
    val boundary = indexOfLast { it.trim() == "connecting" } + 1
    for (i in indices.reversed()) {
        if (i < boundary) return null
        val match = BOOTSTRAP_LINE.find(this[i]) ?: continue
        val percent = match.groupValues[1].toIntOrNull()?.coerceIn(0, 100) ?: continue
        val stage = match.groupValues[2].takeIf { it.isNotEmpty() }
        return BootstrapProgress(percent, stage)
    }
    return null
}

// --- Теги стадий tor'а -> перевод ---
//
// Полный список -- `boot_to_str_tab` из control_bootstrap.c тора 0.4.9.13.
// Теги сгруппированы по смыслу для пользователя: «спрашивает консенсус» и
// «грузит ключи» -- одно и то же для него «каталог сети».

private val TAGS_START = setOf("starting", "undef")

private val TAGS_RELAY = setOf(
    "conn", "conn_pt", "conn_done_pt", "conn_proxy", "conn_done_proxy", "conn_done",
    "ap_conn", "ap_conn_pt", "ap_conn_done_pt", "ap_conn_proxy",
    "ap_conn_done_proxy", "ap_conn_done"
)

private val TAGS_HANDSHAKE = setOf(
    "handshake", "handshake_done", "ap_handshake", "ap_handshake_done"
)

private val TAGS_DIR = setOf(
    "onehop_create", "requesting_status", "loading_status", "loading_keys", "enough_dirinfo"
)

private val TAGS_DESCRIPTORS = setOf("requesting_descriptors", "loading_descriptors")

private val TAGS_CIRCUIT = setOf("circuit_create")

private val TAGS_DONE = setOf("done")

/**
 * Перевод стадии сборки; null для неизвестного тега.
 *
 * Неизвестный тег -- не повод врать: строка покажет один процент без подписи,
 * а выдуманная стадия врала бы. Новые теги тора попадают сюда по мере
 * появления -- покрытие проверяется тестом на полноту относительно таблицы.
 */
fun bootstrapStageKey(tag: String?): Str? = when {
    tag.isNullOrEmpty() -> null
    tag in TAGS_START -> Str.TOR_STAGE_START
    tag in TAGS_RELAY -> Str.TOR_STAGE_RELAY
    tag in TAGS_HANDSHAKE -> Str.TOR_STAGE_HANDSHAKE
    tag in TAGS_DIR -> Str.TOR_STAGE_DIR
    tag in TAGS_DESCRIPTORS -> Str.TOR_STAGE_DESCRIPTORS
    tag in TAGS_CIRCUIT -> Str.TOR_STAGE_CIRCUIT
    tag in TAGS_DONE -> Str.TOR_STAGE_DONE
    else -> null
}
