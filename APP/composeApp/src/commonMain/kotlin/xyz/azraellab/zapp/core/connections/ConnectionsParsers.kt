package xyz.azraellab.zapp.core.connections

/**
 * Разборы текстовых таблиц соединений четырёх систем.
 *
 * Всё чистое и без зависимостей: форматы стабильны, а раннеры команд
 * и чтение файлов остаются на платформах. Мусорные и короткие строки
 * пропускаются молча -- таблица живая, защищаться от «не нашего»
 * вывода важнее, чем упасть посреди опроса.
 */

/** /proc/net/{tcp,tcp6,udp,udp6}: адреса и uid в шестнадцатеричном виде. */
internal object ProcNetParser {

    /** Один слот из /proc/net: uid пригодится, чтобы назвать пакет. */
    internal data class ProcConn(val proto: String, val localPort: Int, val remote: String, val uid: Int)

    fun parse(protocol: String, text: String): List<ProcConn> {
        val rows = mutableListOf<ProcConn>()
        for (line in text.lineSequence()) {
            val parts = line.trim().split(Regex("\\s+"))
            // Строка данных: "0: АДРЕС:ПОРТ АДРЕС:ПОРТ СОСТ ОЧЕРЕДИ ... uid ..."
            if (parts.size < 8 || !parts[0].endsWith(":")) continue
            val local = parseEndpoint(parts[1]) ?: continue
            val remote = parseEndpoint(parts[2]) ?: continue
            val state = parts[3]
            val uid = parts[7].toIntOrNull() ?: 0
            // LISTEN (tcp 0A) и датаграммы без адресата -- удалённой стороны нет.
            val remoteText = if (state == "0A" || remote.second == 0) "-" else "${remote.first}:${remote.second}"
            rows += ProcConn(protocol, local.second, remoteText, uid)
        }
        return rows
    }

    /** "0100007F:0035" -> ("127.0.0.1", 53); адреса /proc лежат побайтово little-endian. */
    internal fun parseEndpoint(value: String): Pair<String, Int>? {
        val cut = value.lastIndexOf(':')
        if (cut <= 0 || cut == value.length - 1) return null
        val port = value.substring(cut + 1).toIntOrNull(16) ?: return null
        val address = formatAddress(value.substring(0, cut)) ?: return null
        return address to port
    }

    private fun formatAddress(hex: String): String? = when (hex.length) {
        // Четыре байта наоборот: "0100007F" = 127.0.0.1
        8 -> {
            val bytes = hexToBytes(hex) ?: return null
            bytes.reversed().joinToString(".") { it.toString() }
        }
        // Четыре 32-битных слова little-endian, каждое -- две группы IPv6
        32 -> {
            val words = hexToBytes(hex) ?: return null
            (0 until 4).joinToString(":") { word ->
                val offset = word * 4
                val value = words[offset].toLong() or
                    (words[offset + 1].toLong() shl 8) or
                    (words[offset + 2].toLong() shl 16) or
                    (words[offset + 3].toLong() shl 24)
                "${(value shr 16).toString(16)}:${(value and 0xFFFF).toString(16)}"
            }
        }
        else -> null
    }

    private fun hexToBytes(hex: String): List<Int>? {
        if (hex.length % 2 != 0) return null
        val out = ArrayList<Int>(hex.length / 2)
        var i = 0
        while (i < hex.length) {
            val byte = hex.substring(i, i + 2).toIntOrNull(16) ?: return null
            out += byte
            i += 2
        }
        return out
    }
}

/** Вывод `ss -H -tunap`: netid состояние recvq sendq локальный удалённый [процесс]. */
internal object SsParser {

    fun parse(text: String): List<ConnRow> {
        val rows = mutableListOf<ConnRow>()
        for (line in text.lineSequence()) {
            val parts = line.trim().split(Regex("\\s+"))
            // tcp ESTAB 0 0 127.0.0.1:9999 1.2.3.4:443 users:(("app",pid=1,fd=5))
            if (parts.size < 6) continue
            val proto = parts[0]
            val state = parts[1]
            val localPort = parts[4].substringAfterLast(':').toIntOrNull() ?: continue
            val peer = parsePeer(parts[5])
            val remote = if (state == "LISTEN" || peer == null) "-" else peer
            rows += ConnRow(proto, localPort, remote, ownerOf(parts.drop(6).joinToString(" ")))
        }
        return rows
    }

    private fun parsePeer(value: String): String? {
        val port = value.substringAfterLast(':').toIntOrNull() ?: return null
        if (port == 0) return null
        return value
    }

    /** users:(("chrome",pid=1234,fd=100)) -> "chrome:1234" */
    private fun ownerOf(tail: String): String {
        val name = Regex("\"([^\"]+)\"").find(tail)?.groupValues?.get(1)
        val pid = Regex("pid=(\\d+)").find(tail)?.groupValues?.get(1)
        return when {
            name != null && pid != null -> "$name:$pid"
            name != null -> name
            else -> "-"
        }
    }
}

/** Вывод `lsof -Fpcnf` (macOS): записи p/c/f/n, имя -- host.port->host.port. */
internal object LsofParser {

    fun parse(text: String): List<ConnRow> {
        val rows = mutableListOf<ConnRow>()
        var owner = "-"
        var proto = "tcp"
        for (line in text.lineSequence()) {
            val tag = line.firstOrNull() ?: continue
            val value = line.substring(1)
            when (tag) {
                // p -- pid процесса, c -- его имя; имя владельца важнее номера
                'c' -> owner = value.ifEmpty { "-" }
                // f -- дескриптор: кончается на 'u' -- UDP, иначе TCP
                'f' -> proto = if (value.endsWith('u')) "udp" else "tcp"
                // n -- адрес сокета: host.port[->host.port] [(СОСТОЯНИЕ)]
                'n' -> {
                    val clean = value.substringBefore(" (")
                    val arrow = clean.split("->")
                    val local = parseHostPort(arrow.first()) ?: continue
                    val remote = if (arrow.size < 2) null else parseHostPort(arrow[1])
                    rows += ConnRow(proto, local.second, remote?.let { "${it.first}:${it.second}" } ?: "-", owner)
                }
            }
        }
        return rows
    }

    /** "192.168.1.5.52344" -> ("192.168.1.5", 52344); порт -- последняя точка. */
    internal fun parseHostPort(value: String): Pair<String, Int>? {
        val cut = value.lastIndexOf('.')
        if (cut <= 0 || cut == value.length - 1) return null
        val port = value.substring(cut + 1).toIntOrNull() ?: return null
        return value.substring(0, cut) to port
    }
}

/** Вывод PowerShell: "proto|локальныйПорт|удалённый|удалённыйПорт|процесс". */
internal object PsCsvParser {

    fun parse(text: String): List<ConnRow> {
        val rows = mutableListOf<ConnRow>()
        for (line in text.lineSequence()) {
            val parts = line.trim().split('|')
            if (parts.size < 5) continue
            val localPort = parts[1].toIntOrNull() ?: continue
            val remotePort = parts[3].toIntOrNull()
            val remote = if (parts[2] == "-" || remotePort == null || remotePort == 0) {
                "-"
            } else {
                "${parts[2]}:$remotePort"
            }
            rows += ConnRow(parts[0], localPort, remote, parts[4].ifEmpty { "-" })
        }
        return rows
    }
}
