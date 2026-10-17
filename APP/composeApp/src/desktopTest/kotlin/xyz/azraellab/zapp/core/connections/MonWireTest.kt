package xyz.azraellab.zapp.core.connections

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.EOFException
import java.io.IOException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

class MonWireTest {

    /** Энкодер снимка -- тот же wire, что у демона: поля по схеме. */
    private fun snapshotBytes(
        atMs: Long,
        rows: List<Triple<String, Int, String>>, // proto, port, remote+owner через " "
        note: Int
    ): ByteArray {
        val out = ByteArrayOutputStream()
        fun varint(value: Long) = MonWire.putVarint(out, value)
        varint((1L shl 3) or 0)
        varint(atMs)
        for ((proto, port, remoteOwner) in rows) {
            val (remote, owner) = remoteOwner.split(" ", limit = 2)
            val msg = ByteArrayOutputStream()
            fun msgVarint(value: Long) = MonWire.putVarint(msg, value)
            msgVarint((1L shl 3) or 2)
            msgVarint(proto.length.toLong())
            msg.write(proto.toByteArray())
            msgVarint((2L shl 3) or 0)
            msgVarint(port.toLong())
            msgVarint((3L shl 3) or 2)
            msgVarint(remote.length.toLong())
            msg.write(remote.toByteArray())
            msgVarint((4L shl 3) or 2)
            msgVarint(owner.length.toLong())
            msg.write(owner.toByteArray())
            val bytes = msg.toByteArray()
            varint((2L shl 3) or 2)
            varint(bytes.size.toLong())
            out.write(bytes)
        }
        varint((3L shl 3) or 0)
        varint(note.toLong())
        return out.toByteArray()
    }

    private fun frame(payload: ByteArray): ByteArray =
        ByteArrayOutputStream().apply {
            write(payload.size and 0xff)
            write((payload.size shr 8) and 0xff)
            write((payload.size shr 16) and 0xff)
            write((payload.size shr 24) and 0xff)
            write(payload)
        }.toByteArray()

    @Test
    fun helloIsGoldenBytes() {
        // Hello{version=1}: тег поля 1 (0x08) + varint 1. Та же пара
        // зафиксирована в Rust-тестах: это точка совместимости сторон.
        assertEquals(listOf(0x08, 0x01), MonWire.hello().toList().map { it.toInt() and 0xff })
        assertEquals(2, MonWire.hello().size)
    }

    @Test
    fun varintMatchesKnownEncoding() {
        val out = ByteArrayOutputStream()
        MonWire.putVarint(out, 300)
        assertEquals(listOf(0xac, 0x02), out.toByteArray().toList().map { it.toInt() and 0xff })
    }

    @Test
    fun snapshotParsesRows() {
        val bytes = snapshotBytes(
            atMs = 1700000000000,
            rows = listOf(
                Triple("tcp", 443, "1.2.3.4:9 curl"),
                Triple("tcp", 8080, "- -"),
                Triple("udp6", 53, "[::1]:53 systemd-resolve")
            ),
            note = 1
        )
        val snapshot = MonWire.snapshot(bytes)!!
        assertEquals(1700000000000, snapshot.atMs)
        assertEquals(1, snapshot.note)
        assertEquals(3, snapshot.rows.size)
        assertEquals(ConnRow("tcp", 443, "1.2.3.4:9", "curl"), snapshot.rows[0])
        assertEquals(ConnRow("tcp", 8080, "-", "-"), snapshot.rows[1], "пустые строки заменяются прочерком")
        assertEquals(ConnRow("udp6", 53, "[::1]:53", "systemd-resolve"), snapshot.rows[2])
    }

    @Test
    fun emptySnapshotIsJustNote() {
        val snapshot = MonWire.snapshot(snapshotBytes(0, emptyList(), 2))!!
        assertEquals(0, snapshot.atMs)
        assertEquals(emptyList(), snapshot.rows)
        assertEquals(2, snapshot.note, "note без строк -- валидное письмо")
    }

    @Test
    fun garbageIsRejectedNotCrashed() {
        assertNull(MonWire.snapshot(byteArrayOf(0x08.toByte(), 0xff.toByte())), "обрванный varint")
        assertNull(MonWire.snapshot(byteArrayOf(0x12, 0x05, 0x01)), "обрезанное сообщение")
        assertNull(MonWire.snapshot(byteArrayOf(0x1a)), "нет значения у varint-поля")
        assertNull(MonWire.snapshot(byteArrayOf(0x0d, 0x00)), "неизвестный wire-тип (fixed32)")
        assertNull(MonWire.snapshot(snapshotBytes(1, listOf(Triple("", 1, "- -")), 1)), "строка без proto")
    }

    @Test
    fun unknownFieldsAreSkipped() {
        // at_ms=5, неизвестное поле 15 (varint и len-delim), note=1.
        val out = ByteArrayOutputStream()
        fun varint(value: Long) = MonWire.putVarint(out, value)
        varint((1L shl 3) or 0)
        varint(5)
        varint((15L shl 3) or 0)
        varint(99)
        varint((14L shl 3) or 2)
        varint(3)
        out.write(byteArrayOf(1, 2, 3))
        varint((3L shl 3) or 0)
        varint(1)
        val snapshot = MonWire.snapshot(out.toByteArray())!!
        assertEquals(5, snapshot.atMs)
        assertEquals(1, snapshot.note)
    }

    @Test
    fun readFrameReassemblesPayload() {
        val payload = snapshotBytes(1, emptyList(), 1)
        val bytes = frame(payload)
        // Куски по одному байту: readFully дожидается фрейм целиком.
        val input = object : java.io.InputStream() {
            var pos = 0
            override fun read(): Int = if (pos >= bytes.size) -1 else bytes[pos++].toInt() and 0xff
        }
        assertEquals(payload.toList(), MonWire.readFrame(input).toList())
    }

    @Test
    fun readFrameRejectsBrokenLengths() {
        assertFailsWith<IOException> {
            // Длина за пределом MAX_FRAME (2^25 > 2^24): битый заголовок,
            // а не «маленький снимок».
            MonWire.readFrame(ByteArrayInputStream(byteArrayOf(0x00, 0x00, 0x00, 0x02)))
        }.let { assertEquals("zappmon: bad frame length 33554432", it.message) }
        assertFailsWith<EOFException> {
            MonWire.readFrame(ByteArrayInputStream(byteArrayOf(0x01)))
        }
        assertFailsWith<EOFException> {
            // Заголовок обещает два байта, а payload один: обрыв посередине.
            MonWire.readFrame(ByteArrayInputStream(byteArrayOf(0x02, 0, 0, 0, 0x08)))
        }
    }
}
