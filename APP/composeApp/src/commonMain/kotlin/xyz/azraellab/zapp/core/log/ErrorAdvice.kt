package xyz.azraellab.zapp.core.log

import xyz.azraellab.zapp.core.Str

/**
 * Рекомендация «что делать» по тексту ошибки.
 *
 * ZAPP уже показывает причину ([ErrorText]), но причина без действия
 * заставляет гадать: «connection refused» -- попробовать другой узел,
 * «address already in use» -- освободить порт. Таблица чистая и тестируемая
 * (как ERROR_MARKERS в ErrorText); показ -- дело UI, флаг transient --
 * дело ретраев движка: transient ошибку имеет смысл повторить, мёртвый
 * узел или занятый порт -- нет.
 */
data class ErrorAdvice(val key: Str, val transient: Boolean)

private data class Rule(val marker: String, val advice: ErrorAdvice)

/** Порядок важен: первое совпадение выигрывает, от общего к частному. */
private val RULES = listOf(
    Rule(
        "already in use",
        ErrorAdvice(Str.ERR_ADV_PORT, transient = false)
    ),
    Rule(
        "permission denied",
        ErrorAdvice(Str.ERR_ADV_PERM, transient = false)
    ),
    Rule(
        "not permitted",
        ErrorAdvice(Str.ERR_ADV_PERM, transient = false)
    ),
    Rule(
        "no such file",
        ErrorAdvice(Str.ERR_ADV_NOFILE, transient = false)
    ),
    Rule(
        "unknown option",
        ErrorAdvice(Str.ERR_ADV_ARGS, transient = false)
    ),
    Rule(
        "certificate",
        ErrorAdvice(Str.ERR_ADV_TLS, transient = true)
    ),
    Rule(
        "handshake",
        ErrorAdvice(Str.ERR_ADV_TLS, transient = true)
    ),
    Rule(
        "resolve",
        ErrorAdvice(Str.ERR_ADV_DNS, transient = true)
    ),
    Rule(
        // Резолвер Go так и начинает: "lookup foo.example: no such host".
        "lookup",
        ErrorAdvice(Str.ERR_ADV_DNS, transient = true)
    ),
    Rule(
        "timed out",
        ErrorAdvice(Str.ERR_ADV_TIMEOUT, transient = true)
    ),
    Rule(
        "timeout",
        ErrorAdvice(Str.ERR_ADV_TIMEOUT, transient = true)
    ),
    Rule(
        "connection refused",
        ErrorAdvice(Str.ERR_ADV_REFUSED, transient = false)
    ),
    Rule(
        "no route",
        ErrorAdvice(Str.ERR_ADV_NET, transient = true)
    ),
    Rule(
        "unreachable",
        ErrorAdvice(Str.ERR_ADV_NET, transient = true)
    )
)

/**
 * Что делать по тексту ошибки; null -- текст ошибки не похож ни на что
 * знакомое, и выдумывать нечего.
 */
fun adviceFor(text: String): ErrorAdvice? {
    val low = text.lowercase()
    return RULES.firstOrNull { low.contains(it.marker) }?.advice
}
