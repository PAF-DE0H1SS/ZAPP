package xyz.azraellab.zapp.core.daemon

/**
 * Разбор пользовательской аргументной строки на отдельные аргументы.
 *
 * Простой split по пробелам ломает кавычки: `--file="my list.txt"`
 * превращался в три аргумента, и демон получал мусор. Идея -- у
 * zaprett-app (ByeDpiVpnService.parseArgs), но их regex не берёт кавычку
 * внутри токена, поэтому здесь посимвольный цикл с состоянием.
 *
 * Сам цикл живёт в нативе (C++ arg_tokenizer.cpp), здесь -- мост:
 * натив первым, та же логика на Kotlin -- фолбэк, когда библиотека
 * не поднялась.
 */
object ArgTokenizer {

    /** Строка пользователя на отдельные аргументы; пустые куски прочь. */
    fun tokenize(input: String): List<String> = tokenizeArgs(input)

    /**
     * Подстановка плейсхолдера `$HOSTS` активным списком доменов.
     *
     * Отдельный токен разворачивается в отдельные аргументы; вплетённый в
     * другой токен (`-H:$HOSTS`) -- в строку через пробел одним аргументом.
     * Список пуст -- токен исчезает целиком, а не превращается в пустышку.
     */
    fun expandHosts(tokens: List<String>, hosts: List<String>): List<String> =
        expandHostsTokens(tokens, hosts)
}

/** Разбор аргументной строки: натив (C++) или фолбэк, см. jvmShared. */
internal expect fun tokenizeArgs(input: String): List<String>

/** Подстановка `$HOSTS`: натив (C++) или фолбэк, см. jvmShared. */
internal expect fun expandHostsTokens(
    tokens: List<String>,
    hosts: List<String>
): List<String>
