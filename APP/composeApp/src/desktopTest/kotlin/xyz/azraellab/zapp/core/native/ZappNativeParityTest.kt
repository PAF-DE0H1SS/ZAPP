package xyz.azraellab.zapp.core.native

import xyz.azraellab.zapp.core.daemon.reconcilePlan
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Паритет нативного ядра с эталоном на Kotlin.
 *
 * Натив (Rust-план, C++-разбор) -- основной путь, фолбэк на Kotlin --
 * страховка. Обе ветки обязаны отдавать одинаковый результат, иначе
 * поведение правил зависело бы от того, поднялась ли библиотека на
 * этой машине. Тест гоняет обе: эталон -- чистые функции из
 * commonMain, натив -- JNI-мост в том же .so, что и на устройстве.
 */
class ZappNativeParityTest {

    @Test
    fun `нативное ядро поднялось`() {
        assertTrue(
            ZappNative.available,
            "libzappjni.so не загрузилась: десктопная нативная сборка не прошла"
        )
    }

    @Test
    fun `разбор iptables-S -- паритет с эталоном`() {
        val output = """
            -N ZAPP_DPI
            -A ZAPP_DPI -d 10.0.0.0/8 -j RETURN
              -A ZAPP_DPI   -p tcp --dport 80 -j NFQUEUE --queue-num 0 --queue-bypass
            это не правило
            -A ZAPP_DPI -m owner --uid-owner 1000 -j RETURN
            -A ZAPP_DPI
        """.trimIndent()
        val expected = listOf(
            "-d 10.0.0.0/8 -j RETURN",
            "-p tcp --dport 80 -j NFQUEUE --queue-num 0 --queue-bypass",
            "-m owner --uid-owner 1000 -j RETURN"
        )
        assertEquals(expected, ZappNative.parseChain(output))
    }

    @Test
    fun `разбор пустого вывода -- пустой список, а не null`() {
        assertEquals(emptyList(), ZappNative.parseChain(""))
    }

    @Test
    fun `план сверки -- паритет с reconcilePlan`() {
        val cases = listOf(
            listOf("a", "b", "c") to listOf("b", "c", "c", "d"),
            listOf<String>() to listOf("x", "y"),
            listOf("only") to listOf<String>(),
            listOf("same") to listOf("same"),
            listOf("m", "a", "z") to listOf("b", "c"),
            listOf("dup", "dup", "rest") to listOf("rest")
        )
        for ((desired, actual) in cases) {
            val native = assertNotNull(ZappNative.plan(desired, actual), "native null: $desired / $actual")
            assertEquals(reconcilePlan(desired, actual), native, "расхождение: $desired / $actual")
        }
    }

    @Test
    fun `план -- добавления первыми, удаления вторыми`() {
        val plan = assertNotNull(ZappNative.plan(listOf("a", "b"), listOf("b", "c")))
        assertEquals(listOf("a" to true, "c" to false), plan.map { it.rule to it.add })
    }

    @Test
    fun `пустые входы -- пустой план`() {
        assertEquals(emptyList(), ZappNative.plan(emptyList(), emptyList()))
    }

    @Test
    fun `план не бросает на краевых входах`() {
        // Пустые строки в наборах -- валидные данные (эквивалентность
        // множеств), исключений быть не должно ни в одной из веток.
        assertNotNull(ZappNative.plan(listOf(""), listOf("")))
        assertNotNull(ZappNative.plan(listOf("пробел в правиле -j RETURN"), emptyList()))
    }

    @Test
    fun `sha256 байтов -- паритет с MessageDigest`() {
        val samples = listOf(
            ByteArray(0),
            "abc".toByteArray(),
            "привет, мир".toByteArray(),
            ByteArray(1 shl 16) { it.toByte() }
        )
        for (bytes in samples) {
            val want = java.security.MessageDigest.getInstance("SHA-256")
                .digest(bytes).joinToString("") { "%02x".format(it) }
            assertEquals(want, assertNotNull(ZappNative.sha256Hex(bytes)), "расхождение")
        }
    }

    @Test
    fun `sha256 файла -- паритет с потоковым фолбэком`() {
        val file = java.io.File.createTempFile("zapp-sha", ".bin")
        try {
            file.writeBytes(ByteArray(300_000) { (it % 251).toByte() })
            val native = assertNotNull(ZappNative.sha256FileHex(file.absolutePath))
            val digest = java.security.MessageDigest.getInstance("SHA-256")
            file.inputStream().use { input ->
                val buffer = ByteArray(1 shl 16)
                while (true) {
                    val read = input.read(buffer)
                    if (read < 0) break
                    digest.update(buffer, 0, read)
                }
            }
            assertEquals(digest.digest().joinToString("") { "%02x".format(it) }, native)
        } finally {
            file.delete()
        }
    }
}
