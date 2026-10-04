package xyz.azraellab.zapp.core.engine

import xyz.azraellab.zapp.core.daemon.DaemonEvent
import kotlin.test.Test
import kotlin.test.assertEquals

class TorBootstrapPercentTest {

    @Test
    fun `percent is parsed from the last matching line`() {
        val lines = listOf(
            DaemonEvent(raw = "Oct 04 [notice] Bootstrapped 25% (loading_status)"),
            DaemonEvent(raw = "Oct 04 [notice] Bootstrapped 100% (done)"),
            DaemonEvent(raw = "Oct 04 [notice] new bridge descriptor"),
        )
        assertEquals(100, torProgressPercent(lines))
    }

    @Test
    fun `progress is read from every step`() {
        assertEquals(0, torProgressPercent(listOf(DaemonEvent(raw = "Bootstrapped 0%"))))
        assertEquals(45, torProgressPercent(listOf(DaemonEvent(raw = "Bootstrapped 45% (connect)"))))
        assertEquals(99, torProgressPercent(listOf(DaemonEvent(raw = "Bootstrapped 99%"))))
    }

    @Test
    fun `no progress lines yet returns -1`() {
        assertEquals(-1, torProgressPercent(emptyList()))
        assertEquals(-1, torProgressPercent(listOf(DaemonEvent(raw = "Opening Socks listener"))))
        assertEquals(-1, torProgressPercent(listOf(DaemonEvent(raw = "Bootstrapped"))))
        assertEquals(-1, torProgressPercent(listOf(DaemonEvent(raw = "Bootstrapped not-a-number"))))
    }

    @Test
    fun `latest line wins over an older higher percent`() {
        val lines = listOf(
            DaemonEvent(raw = "Bootstrapped 100% (done)"),
            DaemonEvent(raw = "[warn] retrying, connection not ready"),
        )
        assertEquals(100, torProgressPercent(lines))
        val onlyOlder = listOf(
            DaemonEvent(raw = "Bootstrapped 100% (done)"),
            DaemonEvent(raw = "Bootstrapped 70% (handshake)"),
        )
        assertEquals(70, torProgressPercent(onlyOlder))
    }

    @Test
    fun `percents outside the range are clamped`() {
        assertEquals(100, torProgressPercent(listOf(DaemonEvent(raw = "Bootstrapped 150%"))))
        assertEquals(10, torProgressPercent(listOf(DaemonEvent(raw = "Bootstrapped 10%"))))
    }
}
