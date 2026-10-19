package xyz.azraellab.zapp.core.daemon

import xyz.azraellab.zapp.core.native.ZappNative
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

/**
 * Паритет токенизатора: натив (C++) против фолбэка на Kotlin.
 *
 * Обе ветки обязаны отдавать одинаковый результат -- иначе разбор
 * аргументов зависел бы от того, поднялась ли библиотека на машине.
 */
class ArgTokenizerParityTest {

    private val cases = listOf(
        "",
        "   \t ",
        "--ttl 5 --timeout 3",
        "  --ttl 5\t--timeout 3 ",
        """--file="my list.txt"""",
        "--file 'a b'",
        """--file="my""",
        "a\"b c\"",
        "it's \"quoted\" 'mix'",
        "\"\" --x ''",
        "  lead\tand\ntrail  ",
        "однобайтовый русский аргумент --юникод",
        "-- \"\$HOSTS\" -H:\$HOSTS -H:\$HOSTS"
    )

    @Test
    fun `нативное ядро поднялось`() {
        assertEquals(true, ZappNative.available, "libzappjni.so не загрузилась")
    }

    @Test
    fun `tokenize -- паритет с фолбэком`() {
        for (input in cases) {
            val native = assertNotNull(ZappNative.tokenizeArgs(input), "native null: $input")
            assertEquals(tokenizeArgsFallback(input), native, "расхождение: $input")
        }
    }

    @Test
    fun `expandHosts -- паритет с фолбэком`() {
        val hostSets = listOf(
            emptyList(),
            listOf("a.example"),
            listOf("a.example", "b.example", "c.example")
        )
        val tokenSets = listOf(
            emptyList(),
            listOf("--ttl", "5"),
            listOf("--ttl", "5", "\$HOSTS"),
            listOf("-H:\$HOSTS"),
            listOf("\$HOSTS", "tail"),
            listOf("prefix-\$HOSTS-suffix", "\$HOSTS", "\$HOSTS")
        )
        for (tokens in tokenSets) {
            for (hosts in hostSets) {
                val native = assertNotNull(
                    ZappNative.expandHosts(tokens, hosts),
                    "native null: $tokens / $hosts"
                )
                assertEquals(
                    expandHostsFallback(tokens, hosts),
                    native,
                    "расхождение: $tokens / $hosts"
                )
            }
        }
    }
}
