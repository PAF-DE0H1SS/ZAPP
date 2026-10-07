package xyz.azraellab.zapp.core.dpi

import xyz.azraellab.zapp.core.daemon.DpiOverlay
import java.io.ByteArrayOutputStream
import java.net.ConnectException
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

/**
 * Релей обхода без root: SOCKS5-проксирование и разрыв ClientHello.
 *
 * Обе темы -- про то, что ставится между box и сетью: раунд-трип доказывает,
 * что релей вообще прозрачен для обычного TCP, а разбор ClientHello --
 * что сплит уходит ровно туда, куда уходит домен SNI.
 */
class SplitRelayTest {

    // --- раунд-трип через SOCKS5 ---

    private fun socksConnect(port: Int): Socket {
        val s = Socket("127.0.0.1", port)
        s.soTimeout = 5000
        val out = s.getOutputStream()
        val input = s.getInputStream()
        out.write(byteArrayOf(5, 1, 0))
        out.flush()
        val greet = ByteArray(2)
        var got = 0
        while (got < 2) {
            val r = input.read(greet, got, 2 - got)
            if (r < 0) break
            got += r
        }
        assertEquals(5, greet[0].toInt() and 0xff, "сервер обязан отвечать SOCKS5")
        assertEquals(0, greet[1].toInt() and 0xff, "метод -- no-auth")
        return s
    }

    private fun socksConnectRequest(s: Socket, host: String, port: Int) {
        val req = ByteArrayOutputStream().apply {
            write(5); write(1); write(0); write(3); write(host.length)
            host.forEach { write(it.code) }
            write((port shr 8) and 0xff)
            write(port and 0xff)
        }.toByteArray()
        s.getOutputStream().write(req)
        s.getOutputStream().flush()
    }

    private fun readBytes(s: Socket, n: Int): ByteArray {
        val out = ByteArray(n)
        val input = s.getInputStream()
        var got = 0
        while (got < n) {
            val r = input.read(out, got, n - got)
            if (r < 0) break
            got += r
        }
        return if (got == n) out else out.copyOf(got)
    }

    @Test
    fun relayProxiesPlainTcpTransparently() {
        val echo = ServerSocket(0, 8, InetAddress.getLoopbackAddress())
        val echoPort = echo.localPort
        Thread {
            try {
                val c = echo.accept()
                c.getInputStream().copyTo(c.getOutputStream())
                c.close()
            } catch (_: Exception) {
            }
        }.apply { isDaemon = true; start() }

        assertNull(SplitRelay.acquire(), "свободный порт обязан открываться")
        try {
            val s = socksConnect(DpiOverlay.RELAY_PORT)
            socksConnectRequest(s, "127.0.0.1", echoPort)
            val reply = readBytes(s, 10)
            assertEquals(0, reply[1].toInt() and 0xff, "успешный ответ на CONNECT")
            // Не-TLS: сплита нет, данные проходят как есть.
            s.getOutputStream().write("hello zapp".toByteArray())
            s.getOutputStream().flush()
            assertContentEquals("hello zapp".toByteArray(), readBytes(s, 10))
            s.close()
        } finally {
            SplitRelay.release()
            runCatching { echo.close() }
        }
    }

    @Test
    fun relayStaysUpUntilLastRelease() {
        assertNull(SplitRelay.acquire())
        assertNull(SplitRelay.acquire())
        SplitRelay.release()
        // Одна ссылка ещё держит порт.
        Socket("127.0.0.1", DpiOverlay.RELAY_PORT).close()
        SplitRelay.release()
        // Последняя отпустила -- порт закрыт.
        assertFailsWith<ConnectException> {
            Socket("127.0.0.1", DpiOverlay.RELAY_PORT).close()
        }
    }

    // --- разбор ClientHello ---

    /** Минимальный ClientHello с одним расширением server_name. */
    private fun clientHello(host: String): ByteArray {
        val name = host.toByteArray()
        val sniData = ByteArrayOutputStream().apply {
            write(0)
            write(name.size shr 8); write(name.size and 0xff)
            write(name)
        }.toByteArray()
        val sniList = ByteArrayOutputStream().apply {
            write(sniData.size shr 8); write(sniData.size and 0xff)
            write(sniData)
        }.toByteArray()
        val ext = ByteArrayOutputStream().apply {
            write(0x00); write(0x00) // server_name
            write(sniList.size shr 8); write(sniList.size and 0xff)
            write(sniList)
        }.toByteArray()
        val body = ByteArrayOutputStream().apply {
            write(0x03); write(0x03) // legacy_version
            write(ByteArray(32)) // random
            write(0) // session_id: пусто
            write(0); write(2); write(0x00); write(0x2f) // cipher_suites
            write(1); write(0) // compression_methods
            write(ext.size shr 8); write(ext.size and 0xff)
            write(ext)
        }.toByteArray()
        val handshake = ByteArrayOutputStream().apply {
            write(0x01) // client_hello
            write(body.size shr 16); write(body.size shr 8); write(body.size and 0xff)
            write(body)
        }.toByteArray()
        return ByteArrayOutputStream().apply {
            write(0x16) // handshake record
            write(0x03); write(0x01)
            write(handshake.size shr 8); write(handshake.size and 0xff)
            write(handshake)
        }.toByteArray()
    }

    @Test
    fun splitOffsetFindsHostnameStart() {
        val buf = clientHello("example.com")
        // Диапазон включает последний валидный индекс: домен лежит в самом
        // конце собранного ClientHello.
        val expected = (0..buf.size - 11).first { i ->
            buf.copyOfRange(i, i + 11).contentEquals("example.com".toByteArray())
        }
        assertEquals(expected, SplitRelay.splitOffset(buf, buf.size), "сплит обязан стоять ровно перед доменом")
    }

    @Test
    fun splitOffsetLeavesNonTlsAndTruncatedBuffersAlone() {
        assertEquals(-1, SplitRelay.splitOffset("hello".toByteArray(), 5), "не-TLS не трогаем")
        val buf = clientHello("example.com")
        assertEquals(-1, SplitRelay.splitOffset(buf, 40), "обрезанный заголовок -- без сплита")
    }
}
