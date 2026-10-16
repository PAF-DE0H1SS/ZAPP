package xyz.azraellab.zapp.core.connections

import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals

/** Фильтр: процесс и адрес -- подстрокой, порт -- номером. */
class ConnectionsFilterTest {

    private val rows = listOf(
        ConnRow("tcp", 49648, "142.250.200.14:443", "chrome:1234"),
        ConnRow("udp", 5353, "-", "mdnsresponder:88"),
        ConnRow("tcp", 8080, "10.0.0.2:8080", "-")
    )

    @Test
    fun emptyQueryPassesEverything() {
        assertEquals(rows, ConnectionsFilter.apply(rows, "   "))
    }

    @Test
    fun processMatchesCaseInsensitively() {
        val hit = ConnectionsFilter.apply(rows, "CHROME")
        assertEquals(listOf(rows[0]), hit)
    }

    @Test
    fun addressMatchesBySubstring() {
        val hit = ConnectionsFilter.apply(rows, "250.200")
        assertEquals(listOf(rows[0]), hit)
    }

    @Test
    fun numberMatchesLocalAndRemotePort() {
        assertEquals(listOf(rows[1]), ConnectionsFilter.apply(rows, "5353"), "локальный порт")
        assertEquals(listOf(rows[0]), ConnectionsFilter.apply(rows, "443"), "порт удалённой стороны")
        assertEquals(emptyList(), ConnectionsFilter.apply(rows, "9999"))
    }

    @Test
    fun remoteAddressTextMatchesEvenWithoutPort() {
        val hit = ConnectionsFilter.apply(rows, "10.0.0.2")
        assertEquals(listOf(rows[2]), hit)
    }
}
