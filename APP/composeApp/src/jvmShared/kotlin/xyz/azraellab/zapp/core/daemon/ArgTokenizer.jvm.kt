package xyz.azraellab.zapp.core.daemon

import xyz.azraellab.zapp.core.native.ZappNative

/**
 * Токенизатор: натив (C++, arg_tokenizer.cpp) первым, фолбэк -- та же
 * логика на Kotlin. Обе ветки обязаны отдавать одинаковый результат --
 * это держит паритетный тест.
 */
internal actual fun tokenizeArgs(input: String): List<String> =
    ZappNative.tokenizeArgs(input) ?: tokenizeArgsFallback(input)

internal actual fun expandHostsTokens(
    tokens: List<String>,
    hosts: List<String>
): List<String> = ZappNative.expandHosts(tokens, hosts)
    ?: expandHostsFallback(tokens, hosts)

/** Фолбэк tokenizeArgs без нативной библиотеки. */
internal fun tokenizeArgsFallback(input: String): List<String> {
    val out = mutableListOf<String>()
    val cur = StringBuilder()
    var quote = ' '
    for (ch in input) {
        when {
            quote != ' ' -> if (ch == quote) quote = ' ' else cur.append(ch)
            ch == '"' || ch == '\'' -> quote = ch
            ch.isWhitespace() -> {
                if (cur.isNotEmpty()) {
                    out += cur.toString()
                    cur.clear()
                }
            }
            else -> cur.append(ch)
        }
    }
    if (cur.isNotEmpty()) out += cur.toString()
    return out
}

/** Фолбэк expandHostsTokens без нативной библиотеки. */
internal fun expandHostsFallback(
    tokens: List<String>,
    hosts: List<String>
): List<String> = tokens.flatMap { token ->
    when {
        token == "\$HOSTS" -> hosts
        token.contains("\$HOSTS") && hosts.isNotEmpty() ->
            listOf(token.replace("\$HOSTS", hosts.joinToString(" ")))
        token.contains("\$HOSTS") -> emptyList()
        else -> listOf(token)
    }
}
