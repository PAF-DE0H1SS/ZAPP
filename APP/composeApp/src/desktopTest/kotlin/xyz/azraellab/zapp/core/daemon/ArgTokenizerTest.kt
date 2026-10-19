package xyz.azraellab.zapp.core.daemon

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Разбор пользовательской аргументной строки.
 *
 * Пробелы в кавычках -- главная ловушка: раньше `--file="my list.txt"`
 * разваливался на три аргумента, и демон получал мусор.
 */
class ArgTokenizerTest {

    @Test
    fun `простые аргументы через пробелы и табуляции`() {
        assertEquals(
            listOf("--ttl", "5", "--timeout", "3"),
            ArgTokenizer.tokenize("  --ttl 5\t--timeout 3 ")
        )
    }

    @Test
    fun `двоичные кавычки склеивают аргумент`() {
        assertEquals(
            listOf("--file=my list.txt"),
            ArgTokenizer.tokenize("""--file="my list.txt"""")
        )
    }

    @Test
    fun `одинарные кавычки тоже склеивают`() {
        assertEquals(
            listOf("--file", "a b"),
            ArgTokenizer.tokenize("--file 'a b'")
        )
    }

    @Test
    fun `пустая строка и пробелы -- пустой список`() {
        assertTrue(ArgTokenizer.tokenize("").isEmpty())
        assertTrue(ArgTokenizer.tokenize("   \t ").isEmpty())
    }

    @Test
    fun `открытая кавычка не роняет разбор`() {
        assertEquals(listOf("--file=my"), ArgTokenizer.tokenize("""--file="my"""))
    }

    @Test
    fun `отдельный плейсхолдер разворачивается в список`() {
        assertEquals(
            listOf("--ttl", "5", "a.example", "b.example"),
            ArgTokenizer.expandHosts(
                listOf("--ttl", "5", "\$HOSTS"),
                listOf("a.example", "b.example")
            )
        )
    }

    @Test
    fun `плейсхолдер внутри токена подставляется строкой`() {
        assertEquals(
            listOf("-H:a.example b.example"),
            ArgTokenizer.expandHosts(listOf("-H:\$HOSTS"), listOf("a.example", "b.example"))
        )
    }

    @Test
    fun `пустой список доменов исчезает, а не оставляет пустышку`() {
        assertEquals(
            listOf("--ttl", "5"),
            ArgTokenizer.expandHosts(listOf("--ttl", "5", "\$HOSTS"), emptyList())
        )
        assertEquals(
            listOf(),
            ArgTokenizer.expandHosts(listOf("\$HOSTS"), emptyList())
        )
    }

    @Test
    fun `токены без плейсхолдера проходят как есть`() {
        assertEquals(
            listOf("-x", "-y"),
            ArgTokenizer.expandHosts(listOf("-x", "-y"), listOf("a.example"))
        )
    }
}
