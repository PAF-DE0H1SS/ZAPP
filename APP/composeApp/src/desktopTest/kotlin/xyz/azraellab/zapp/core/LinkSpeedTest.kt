package xyz.azraellab.zapp.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * Плашка скорости: направление, формат, фильтр интерфейсов.
 *
 * Скорость -- это дельта двух срезов, поэтому здесь же проверяется и
 * обработка нулевой базы, и уменьшившегося счётчика: ошибка в них
 * даёт либо всплеск при старте, либо вечный ноль в плашке.
 */
class LinkSpeedTest {

    private fun iface(
        name: String,
        rx: Long,
        tx: Long,
        tunnelled: Boolean = false
    ) = InterfaceCounters(name = name, rxBytes = rx, txBytes = tx, tunnelled = tunnelled)

    private fun sample(vararg ifaces: InterfaceCounters, at: Long = 1000L) =
        TrafficSample(timestampMs = at, interfaces = ifaces.toList())

    // --- Направление ---

    @Test
    fun tunDirectionIsNotSwapped() {
        // tun: box пишет в tun пакеты из сети -- это rx счётчика, то
        // есть download; чтение из tun -- исходящий трафик, tx/upload.
        // Стороны как у всех остальных интерфейсов.
        val prev = sample(iface("tun0", rx = 1000, tx = 2000, tunnelled = true), at = 0)
        val cur = sample(iface("tun0", rx = 3000, tx = 6000, tunnelled = true), at = 1000)
        val speed = linkSpeedBetween(prev, cur)!!
        // 2000 байт download (rx), 4000 upload (tx) за 1с.
        assertEquals(2000L, speed.downBps)
        assertEquals(4000L, speed.upBps)
    }

    @Test
    fun wgDirectionIsNotSwapped() {
        val prev = sample(iface("wg0", rx = 500, tx = 100, tunnelled = true), at = 0)
        val cur = sample(iface("wg0", rx = 4500, tx = 600, tunnelled = true), at = 1000)
        val speed = linkSpeedBetween(prev, cur)!!
        assertEquals(4000L, speed.downBps)
        assertEquals(500L, speed.upBps)
    }

    // --- Что считается, а что нет ---

    @Test
    fun plainInterfacesAreIgnored() {
        val prev = sample(iface("wlan0", rx = 0, tx = 0), at = 0)
        val cur = sample(iface("wlan0", rx = 99999, tx = 99999), at = 1000)
        assertNull(linkSpeedBetween(prev, cur))
    }

    @Test
    fun loopbackIsIgnoredEvenIfMarked() {
        val prev = sample(iface("lo", rx = 0, tx = 0, tunnelled = true), at = 0)
        val cur = sample(iface("lo", rx = 5000, tx = 5000, tunnelled = true), at = 1000)
        assertNull(linkSpeedBetween(prev, cur))
    }

    @Test
    fun kernelWgInterfaceNameIsRecognized() {
        // Движок зовёт свои kernel-WG интерфейсы z* (см. ifaceName: "z" +
        // до 7 символов id) -- без отдельного условия они выпали бы из
        // плашки, хотя туннель -- именно они. Одиночная "z" -- не имя
        // интерфейса вовсе.
        assertEquals(true, iface("ztorigar", 0, 0).isTunnelSpeedInterface())
        assertEquals(true, iface("z0", 0, 0).isTunnelSpeedInterface())
        assertEquals(false, iface("z", 0, 0).isTunnelSpeedInterface())
        assertEquals(false, iface("wlan0", 0, 0).isTunnelSpeedInterface())
    }

    // --- Деградация счётчиков ---

    @Test
    fun counterResetCountsAsZeroNotNegative() {
        val prev = sample(iface("tun0", rx = 5000, tx = 5000, tunnelled = true), at = 0)
        val cur = sample(iface("tun0", rx = 100, tx = 100, tunnelled = true), at = 1000)
        val speed = linkSpeedBetween(prev, cur)!!
        assertEquals(0L, speed.downBps)
        assertEquals(0L, speed.upBps)
    }

    @Test
    fun newInterfaceWithoutBaselineIsSkipped() {
        // Первый срез после старта: по интерфейсу ещё нет базы, и
        // считать по нему нельзя -- иначе весь трафик за жизнь
        // интерфейса упал бы в первую секунду.
        val cur = sample(iface("tun0", rx = 900_000, tx = 900_000, tunnelled = true), at = 1000)
        val prev = sample(iface("wlan0", rx = 0, tx = 0), at = 0)
        val speed = linkSpeedBetween(prev, cur)!!
        assertEquals(0L, speed.downBps)
        assertEquals(0L, speed.upBps)
    }

    // --- Формат ---

    @Test
    fun formatSpeedCoversAllUnits() {
        assertEquals("0 B/s", formatSpeed(0))
        assertEquals("512 B/s", formatSpeed(512))
        assertEquals("1 KB/s", formatSpeed(1024))
        assertEquals("1.5 KB/s", formatSpeed(1536))
        assertEquals("5 MB/s", formatSpeed(5L * 1024 * 1024))
        assertEquals("2 GB/s", formatSpeed(2L * 1024 * 1024 * 1024))
    }

    @Test
    fun displayStringShowsBothDirections() {
        val line = LinkSpeed(downBps = 1536, upBps = 512).toDisplayString()
        assertEquals("↓ 1.5 KB/s  ↑ 512 B/s", line)
    }
}
