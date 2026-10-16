package xyz.azraellab.zapp.core.connections

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * Разборы таблиц четырёх систем: реальные образцы вывода, шестнадцатеричные
 * адреса /proc, пропуск мусора.
 */
class ConnectionsParsersTest {

    // --- /proc/net (Android, Linux) ---

    @Test
    fun procListenHasNoRemote() {
        val rows = ProcNetParser.parse(
            "tcp",
            """
            sl  local_address rem_address   st tx_queue:rx_queue tr:tm->when retrnsmt uid
                0: 0100007F:0035 00000000:0000 0A 00000000:00000000 00:00000000 00000000  1000  0 12345 1
            """.trimIndent()
        )
        val row = rows.single()
        assertEquals(ProcNetParser.ProcConn("tcp", 53, "-", 1000), row)
    }

    @Test
    fun procEstablishedFormatsAddresses() {
        // 192.168.1.8:49648 -> 142.250.200.14:443, состоя Established
        val rows = ProcNetParser.parse(
            "tcp",
            "5: 0801A8C0:C1F0 0EC8FA8E:01BB 01 00000000:00000000 00:00000000 00000000 10234 0 555 1"
        )
        val row = rows.single()
        assertEquals(49648, row.localPort)
        assertEquals("142.250.200.14:443", row.remote)
        assertEquals(10234, row.uid)
    }

    @Test
    fun procIpv6WordsAreLittleEndian() {
        // ::1:8080 -- четыре 32-битных слова little-endian
        assertEquals(
            "0:0:0:0:0:0:0:1" to 8080,
            ProcNetParser.parseEndpoint("00000000000000000000000001000000:1F90")
        )
        assertEquals("127.0.0.1" to 53, ProcNetParser.parseEndpoint("0100007F:0035"))
    }

    @Test
    fun procUdpWithoutPeerIsNullRemote() {
        val rows = ProcNetParser.parse(
            "udp",
            "3: 00000000:0044 00000000:0000 07 00000000:00000000 00:00000000 00000000 1000 0 999 1"
        )
        assertEquals(ProcNetParser.ProcConn("udp", 68, "-", 1000), rows.single())
    }

    @Test
    fun procSkipsHeaderAndGarbage() {
        val rows = ProcNetParser.parse(
            "tcp",
            """
            sl local_address rem_address st tx_queue:rx_queue tr:tm->when retrnsmt uid
            0: 0100007F:ZZZZ 00000000:0000 0A 00000000:00000000 00:00000000 00000000 1000 0 1 1
            короче это вообще не таблица
            """.trimIndent()
        )
        assertEquals(emptyList(), rows)
    }

    @Test
    fun procEmptyTextIsEmpty() {
        assertEquals(emptyList(), ProcNetParser.parse("tcp", ""))
    }

    @Test
    fun procEndpointRejectsBrokenValues() {
        assertNull(ProcNetParser.parseEndpoint("0100007F"))
        assertNull(ProcNetParser.parseEndpoint(":0035"))
        assertNull(ProcNetParser.parseEndpoint("0100007F:"))
    }

    // --- ss ---

    @Test
    fun ssEstablishedWithProcess() {
        val rows = SsParser.parse(
            """
            tcp ESTAB 0 0 192.168.1.8:49648 142.250.200.14:443 users:(("chrome",pid=1234,fd=100))
            """.trimIndent()
        )
        val row = rows.single()
        assertEquals(ConnRow("tcp", 49648, "142.250.200.14:443", "chrome:1234"), row)
    }

    @Test
    fun ssListenAndUnboundHaveNoRemote() {
        val rows = SsParser.parse(
            """
            tcp LISTEN 0 128 0.0.0.0:8080 0.0.0.0:*
            udp UNCONN 0 0 127.0.0.1:5353 0.0.0.0:*
            """.trimIndent()
        )
        assertEquals(ConnRow("tcp", 8080, "-", "-"), rows[0])
        assertEquals(ConnRow("udp", 5353, "-", "-"), rows[1])
    }

    @Test
    fun ssUdpWithPeerKeepsAddress() {
        val rows = SsParser.parse("udp ESTAB 0 0 192.168.1.8:52344 8.8.8.8:53")
        assertEquals("8.8.8.8:53", rows.single().remote)
    }

    @Test
    fun ssSkipsShortLines() {
        assertEquals(emptyList(), SsParser.parse("нет тут\nничего\n"))
    }

    @Test
    fun ssEmptyTextIsEmpty() {
        assertEquals(emptyList(), SsParser.parse(""))
    }

    // --- lsof -Fpcnf ---

    @Test
    fun lsofEstablishedRow() {
        val rows = LsofParser.parse(
            """
            p1234
            cchrome
            f46t
            n192.168.1.8.49648->142.250.200.14.443 (ESTABLISHED)
            """.trimIndent()
        )
        assertEquals(ConnRow("tcp", 49648, "142.250.200.14:443", "chrome"), rows.single())
    }

    @Test
    fun lsofListenRowWithoutArrow() {
        val rows = LsofParser.parse("p1\nccup\nf3t\nn192.168.1.8.631 (LISTEN)")
        assertEquals(ConnRow("tcp", 631, "-", "cup"), rows.single())
    }

    @Test
    fun lsofUdpDescriptor() {
        val rows = LsofParser.parse("p2\ncmdns\nf4u\nn*.5353")
        assertEquals("udp", rows.single().proto)
    }

    @Test
    fun lsofParseHostPortSplitsOnLastDot() {
        assertEquals("192.168.1.8" to 443, LsofParser.parseHostPort("192.168.1.8.443"))
        assertEquals("[2001:db8::1]" to 443, LsofParser.parseHostPort("[2001:db8::1].443"))
        assertNull(LsofParser.parseHostPort("noport"))
    }

    // --- строки PowerShell ---

    @Test
    fun psCsvParsesTcpRow() {
        val rows = PsCsvParser.parse("tcp|49648|142.250.200.14|443|chrome")
        assertEquals(ConnRow("tcp", 49648, "142.250.200.14:443", "chrome"), rows.single())
    }

    @Test
    fun psCsvZeroPortIsNoRemote() {
        val rows = PsCsvParser.parse("tcp|53|0.0.0.0|0|-")
        assertEquals(ConnRow("tcp", 53, "-", "-"), rows.single())
    }

    @Test
    fun psCsvUdpRowAndEmptyOwner() {
        val rows = PsCsvParser.parse(
            """
            udp|5353|0.0.0.0|0|mdnsresponder
            tcp|80|1.2.3.4|80|
            """.trimIndent()
        )
        assertEquals("udp", rows[0].proto)
        assertEquals("-", rows[0].remote)
        assertEquals("-", rows[1].owner)
    }

    @Test
    fun psCsvSkipsGarbage() {
        assertEquals(emptyList(), PsCsvParser.parse("слышь, | нет\n|"))
    }
}
