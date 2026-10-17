package xyz.azraellab.zapp.core.connections

import java.io.ByteArrayOutputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/**
 * Клиентская сторона IPC: реальный TCP-сервер играет роль демона.
 *
 * Сокет настоящий, письма настоящие (те же wire-байты, что шлёт Rust):
 * проверяется именно протокольный мост, а не моки.
 */
class MonConnectionsSourceTest {

    /** Тестовый демон: принимает одного клиента, сверяет Hello, зовёт handler. */
    private class TestMon(private val handler: (Socket) -> Unit) : java.io.Closeable {
        private val server = ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"))

        /** Hello, которые прислали клиенты (для проверки рукопожатия). */
        val hellos = mutableListOf<List<Int>>()

        val port: Int get() = server.localPort

        init {
            Thread {
                try {
                    server.accept().use { socket ->
                        val hello = MonWire.readFrame(socket.getInputStream())
                        hellos.add(hello.toList().map { it.toInt() and 0xff })
                        handler(socket)
                    }
                } catch (_: Exception) {
                    // Клиент мог отвалиться первым -- это тоже исход.
                }
            }.apply {
                isDaemon = true
                start()
            }
        }

        override fun close() {
            runCatching { server.close() }
        }
    }

    /** Кадр снимка с [rows] строками и [note]; wire тот же, что у демона. */
    private fun snapshotFrame(rows: Int = 1, note: Int = 1): ByteArray {
        val out = ByteArrayOutputStream()
        fun varint(value: Long) = MonWire.putVarint(out, value)
        varint((1L shl 3) or 0)
        varint(42)
        repeat(rows) { index ->
            val msg = ByteArrayOutputStream()
            fun msgVarint(value: Long) = MonWire.putVarint(msg, value)
            msgVarint((1L shl 3) or 2)
            msgVarint(3)
            msg.write("tcp".toByteArray())
            msgVarint((2L shl 3) or 0)
            msgVarint((1000 + index).toLong())
            msgVarint((3L shl 3) or 2)
            msgVarint(11)
            msg.write("1.2.3.4:443".toByteArray())
            msgVarint((4L shl 3) or 2)
            msgVarint((3 + index.toString().length).toLong())
            msg.write("app$index".toByteArray())
            val bytes = msg.toByteArray()
            varint((2L shl 3) or 2)
            varint(bytes.size.toLong())
            out.write(bytes)
        }
        varint((3L shl 3) or 0)
        varint(note.toLong())
        val payload = out.toByteArray()
        return ByteArrayOutputStream().apply {
            write(payload.size and 0xff)
            write((payload.size shr 8) and 0xff)
            write((payload.size shr 16) and 0xff)
            write((payload.size shr 24) and 0xff)
            write(payload)
        }.toByteArray()
    }

    /** Клиентское подключение -- то же рукопожатие, что делает ZappMon. */
    private fun client(port: Int): MonSession =
        MonSession(Socket("127.0.0.1", port).also { it.tcpNoDelay = true }, null)

    @Test
    fun deliversSnapshotAndSendsHello() {
        TestMon { socket ->
            socket.getOutputStream().write(snapshotFrame(rows = 2))
            socket.getOutputStream().flush()
        }.use { mon ->
            val source = MonConnectionsSource(connect = { client(mon.port) }, timeoutMs = 2000)
            val sample = source.read()
            assertEquals(2, sample.rows.size)
            assertEquals(ConnRow("tcp", 1000, "1.2.3.4:443", "app0"), sample.rows[0])
            assertEquals(ConnRow("tcp", 1001, "1.2.3.4:443", "app1"), sample.rows[1])
            assertEquals(null, sample.note, "note=1 -- обычное состояние, пометки нет")
            assertEquals(listOf(listOf(0x08, 0x01)), mon.hellos.toList())
        }
    }

    @Test
    fun noteMapsToConnNote() {
        TestMon { socket ->
            socket.getOutputStream().write(snapshotFrame(rows = 0, note = 2))
            socket.getOutputStream().flush()
        }.use { mon ->
            val source = MonConnectionsSource(connect = { client(mon.port) }, timeoutMs = 2000)
            assertEquals(ConnNote.NO_PROC, source.read().note)
        }
    }

    @Test
    fun silentDaemonTimesOut() {
        TestMon { socket ->
            // Рукопожатие приняли, дальше молчим.
            Thread.sleep(500)
        }.use { mon ->
            val source = MonConnectionsSource(connect = { client(mon.port) }, timeoutMs = 120)
            val error = assertFailsWith<java.io.IOException> { source.read() }
            assertEquals("zappmon: timeout", error.message)
        }
    }

    @Test
    fun brokenDaemonReconnectsOnNextTick() {
        val connects = AtomicInteger()
        val first = TestMon { socket ->
            // Рукопожатие приняли и молча вышли: клиент получит обрыв.
        }
        val second = TestMon { socket ->
            socket.getOutputStream().write(snapshotFrame())
            socket.getOutputStream().flush()
        }
        try {
            val source = MonConnectionsSource(
                connect = {
                    val attempt = connects.incrementAndGet()
                    client(if (attempt == 1) first.port else second.port)
                },
                timeoutMs = 2000
            )
            assertFailsWith<java.io.IOException> { source.read() }
            val sample = source.read()
            assertEquals(1, sample.rows.size, "вторая попытка отдаёт снимок")
            assertEquals(2, connects.get())
        } finally {
            first.close()
            second.close()
        }
    }

    @Test
    fun deadDaemonMeansExceptionNotFakeEmpty() {
        // Connect возвращает null, как бинарь без демона: это исключение,
        // а не пустая таблица, -- иначе UI соврал бы «соединений нет».
        val source = MonConnectionsSource(connect = { null }, timeoutMs = 50)
        assertFailsWith<java.io.IOException> { source.read() }
    }
}
