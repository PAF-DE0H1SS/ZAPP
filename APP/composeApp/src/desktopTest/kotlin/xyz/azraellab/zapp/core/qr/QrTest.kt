package xyz.azraellab.zapp.core.qr

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** QR: кодирование текста в матрицу, обратное чтение, разбор ASCII-матрицы. */
class QrTest {

    @Test
    fun encodesToMatrix() {
        val matrix = encodeQr("https://example.com")
        assertNotNull(matrix)
        assertTrue(matrix.width > 0)
        assertTrue(matrix.height > 0)
    }

    @Test
    fun emptyTextRejected() {
        assertNull(encodeQr(""))
    }

    @Test
    fun roundTripRecoversText() {
        val text = "vless://abc@1.2.3.4:443?type=ws#name"
        val matrix = encodeQr(text)
        assertNotNull(matrix)
        assertEquals(text, decodeQr(matrix))
    }

    @Test
    fun roundTripLongConfig() {
        val text = "[Interface]\nPrivateKey = abcdef\nAddress = 10.0.0.2/32\n" +
            "[Peer]\nPublicKey = xyz\nEndpoint = 1.2.3.4:51820"
        val matrix = encodeQr(text)
        assertNotNull(matrix)
        assertEquals(text, decodeQr(matrix))
    }

    @Test
    fun decodesHandBuiltMatrix() {
        // Простейшая «матрица»: раскодировать её как QR нельзя, но разбор
        // ячеек из ASCII должен дать точную сетку.
        val matrix = qrMatrixOf(
            listOf(
                "#.#",
                "###",
                "#.#"
            )
        )
        assertEquals(3, matrix.width)
        assertEquals(3, matrix.height)
        assertTrue(matrix[0, 0])
        assertEquals(false, matrix[1, 0])
        assertTrue(matrix[2, 0])
        assertTrue(matrix[0, 1])
        assertTrue(matrix[1, 1])
        assertEquals(false, matrix[1, 2])
        assertTrue(matrix[2, 2])
    }

    @Test
    fun decodeGarbageRejected() {
        assertNull(decodeQr(qrMatrixOf(listOf("#.#", ".#.", "#.#"))))
    }
}
