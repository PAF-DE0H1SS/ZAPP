package xyz.azraellab.zapp.core.secure

import java.io.File
import java.io.FileWriter
import kotlin.test.Test
import xyz.azraellab.zapp.core.secure.sha256Hex
import xyz.azraellab.zapp.core.secure.sha256Matches
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Контрольные суммы скачанного.
 *
 * Известный вектор abc -- каноничный тест SHA-256; сравнение принимает
 * фингерпринты с двоеточиями и в верхнем регистре, как их принято писать.
 */
class Sha256Test {

    @Test
    fun `известный вектор abc`() {
        assertEquals(
            "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad",
            sha256Hex("abc".toByteArray())
        )
    }

    @Test
    fun `файл и байты дают одну сумму`() {
        val file = File.createTempFile("sha256", ".txt").apply {
            writeText("hello zapp")
            deleteOnExit()
        }
        assertEquals(sha256Hex("hello zapp".toByteArray()), Sha256.fileHex(file))
    }

    @Test
    fun `фингерпринт с двоеточиями и капсом совпадает`() {
        val expected = "BA:78:16:BF:8F:01:CF:EA:41:41:40:DE:5D:AE:22:23" +
            ":B0:03:61:A3:96:17:7A:9C:B4:10:FF:61:F2:00:15:AD"
        assertTrue(sha256Matches("abc".toByteArray(), expected))
    }

    @Test
    fun `чужая сумма не совпадает`() {
        assertFalse(sha256Matches("abc".toByteArray(), "0".repeat(64)))
    }

    @Test
    fun `пустое ожидание -- не совпадение, а не пропуск на веру`() {
        assertFalse(sha256Matches("abc".toByteArray(), ""))
        assertFalse(sha256Matches("abc".toByteArray(), "  "))
    }

    @Test
    fun `битый файл читается как null, а не как пустая сумма`() {
        val missing = File.createTempFile("sha256-gone", ".txt").apply { delete() }
        assertNull(Sha256.fileHex(missing))
        assertFalse(Sha256.matches(missing, "0".repeat(64)))
    }

    @Test
    fun `пустой файл имеет свою известную сумму`() {
        val file = File.createTempFile("sha256-empty", ".bin").apply {
            FileWriter(this).use { it.write("") }
            deleteOnExit()
        }
        assertTrue(Sha256.matches(file, sha256Hex(ByteArray(0))))
    }
}
