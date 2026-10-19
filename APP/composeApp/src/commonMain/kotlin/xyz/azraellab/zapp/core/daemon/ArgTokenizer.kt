package xyz.azraellab.zapp.core.daemon

/**
 * Разбор пользовательской аргументной строки на отдельные аргументы.
 *
 * Простой split по пробелам ломает кавычки: `--file="my list.txt"`
 * превращался в три аргумента, и демон получал мусор. Идея -- у
 * zaprett-app (ByeDpiVpnService.parseArgs), но их regex не берёт кавычку
 * внутри токена, поэтому здесь посимвольный цикл с состоянием.
 */
object ArgTokenizer {

    /** Строка пользователя на отдельные аргументы; пустые куски прочь. */
    fun tokenize(input: String): List<String> {
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

    /**
     * Подстановка плейсхолдера `$HOSTS` активным списком доменов.
     *
     * Отдельный токен разворачивается в отдельные аргументы; вплетённый в
     * другой токен (`-H:$HOSTS`) -- в строку через пробел одним аргументом.
     * Список пуст -- токен исчезает целиком, а не превращается в пустышку.
     */
    fun expandHosts(tokens: List<String>, hosts: List<String>): List<String> =
        tokens.flatMap { token ->
            when {
                token == "\$HOSTS" -> hosts
                token.contains("\$HOSTS") && hosts.isNotEmpty() ->
                    listOf(token.replace("\$HOSTS", hosts.joinToString(" ")))
                token.contains("\$HOSTS") -> emptyList()
                else -> listOf(token)
            }
        }
}

