package xyz.azraellab.zapp.core.connections

import java.io.ByteArrayOutputStream
import java.io.EOFException
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream

/**
 * proto3-wire для IPC с zappmon.
 *
 * Схема -- native/mon/daemon.proto: тег = (поле << 3) | тип, varint --
 * 7 бит на байт, length-delimited -- varint-длина + байты. Демон пишет
 * и читает этим же форматом; известные значения varint зафиксированы
 * одинаковыми голден-байтами в тестах обеих сторон, а полная
 * совместимость проверяется живым интеграционным тестом.
 */
internal object MonWire {

    /** Версия протокола: первое письмо клиента обязано её назвать. */
    const val PROTO_VERSION = 1

    /** Предел размера письма: снимок с тысячами строк и так мегабайт. */
    const val MAX_FRAME = 1 shl 24

    /** Hello клиента: поле 1, varint. */
    fun hello(version: Int = PROTO_VERSION): ByteArray {
        val out = ByteArrayOutputStream(4)
        putVarint(out, 1L shl 3)
        putVarint(out, version.toLong())
        return out.toByteArray()
    }

    /** Снимок демона; null -- битое или чужое письмо. */
    fun snapshot(bytes: ByteArray): MonSnapshot? {
        var atMs = 0L
        var note = 0
        val rows = ArrayList<ConnRow>()
        var pos = 0
        while (pos < bytes.size) {
            val tag = varint(bytes, pos) ?: return null
            pos = tag.second
            val field = tag.first shr 3
            when (val wireType = tag.first and 7L) {
                0L -> {
                    val value = varint(bytes, pos) ?: return null
                    pos = value.second
                    when (field) {
                        1L -> atMs = value.first
                        3L -> note = value.first.toInt()
                    }
                }
                2L -> {
                    val len = varint(bytes, pos) ?: return null
                    pos = len.second
                    if (pos + len.first > bytes.size) return null
                    if (field == 2L) {
                        rows.add(conn(bytes, pos, len.first.toInt()) ?: return null)
                    }
                    pos += len.first.toInt()
                }
                else -> return null
            }
        }
        return MonSnapshot(atMs, rows, note)
    }

    /** Один фрейм в поток: u32 LE длина + письмо. */
    fun writeFrame(output: OutputStream, payload: ByteArray) {
        output.write(payload.size and 0xff)
        output.write((payload.size shr 8) and 0xff)
        output.write((payload.size shr 16) and 0xff)
        output.write((payload.size shr 24) and 0xff)
        output.write(payload)
    }

    /** Один фрейм из потока: u32 LE длина + письмо. */
    fun readFrame(input: InputStream): ByteArray {
        val header = ByteArray(4)
        readFully(input, header)
        val len = (header[0].toInt() and 0xff) or
            ((header[1].toInt() and 0xff) shl 8) or
            ((header[2].toInt() and 0xff) shl 16) or
            ((header[3].toInt() and 0xff) shl 24)
        if (len < 0 || len > MAX_FRAME) throw IOException("zappmon: bad frame length $len")
        val payload = ByteArray(len)
        readFully(input, payload)
        return payload
    }

    /** Строка Conn: proto=1, local_port=2, remote=3, owner=4. */
    private fun conn(bytes: ByteArray, start: Int, length: Int): ConnRow? {
        var proto = ""
        var port = 0
        var remote = ""
        var owner = ""
        var pos = start
        val end = start + length
        while (pos < end) {
            val tag = varint(bytes, pos) ?: return null
            pos = tag.second
            val field = tag.first shr 3
            when (val wireType = tag.first and 7L) {
                0L -> {
                    val value = varint(bytes, pos) ?: return null
                    pos = value.second
                    if (field == 2L) port = value.first.toInt()
                }
                2L -> {
                    val len = varint(bytes, pos) ?: return null
                    pos = len.second
                    if (pos + len.first > end) return null
                    val text = bytes.decodeToString(pos, pos + len.first.toInt())
                    when (field) {
                        1L -> proto = text
                        3L -> remote = text
                        4L -> owner = text
                    }
                    pos += len.first.toInt()
                }
                else -> return null
            }
        }
        if (proto.isEmpty()) return null
        return ConnRow(
            proto = proto,
            localPort = port,
            remote = remote.ifEmpty { "-" },
            owner = owner.ifEmpty { "-" }
        )
    }

    /** varint: значение и позиция после него; null -- письмо оборвано. */
    private fun varint(bytes: ByteArray, start: Int): Pair<Long, Int>? {
        var result = 0L
        var shift = 0
        var pos = start
        while (pos < bytes.size) {
            val byte = bytes[pos].toInt() and 0xff
            pos++
            result = result or ((byte and 0x7f).toLong() shl shift)
            if (byte and 0x80 == 0) return result to pos
            shift += 7
            if (shift > 63) return null
        }
        return null
    }

    private fun readFully(input: InputStream, dst: ByteArray) {
        var pos = 0
        while (pos < dst.size) {
            val read = input.read(dst, pos, dst.size - pos)
            if (read < 0) throw EOFException("zappmon: stream closed")
            pos += read
        }
    }

    internal fun putVarint(out: ByteArrayOutputStream, value: Long) {
        var v = value
        while (true) {
            val byte = (v and 0x7f).toByte()
            v = v ushr 7
            if (v == 0L) {
                out.write(byte.toInt())
                return
            }
            out.write(byte.toInt() or 0x80)
        }
    }
}

/** Снимок демона в форме JVM: строки уже в наших ConnRow. */
internal data class MonSnapshot(
    val atMs: Long,
    val rows: List<ConnRow>,
    val note: Int
)
