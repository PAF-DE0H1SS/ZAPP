package xyz.azraellab.zapp.core.traffic

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** История трафика: бакеты по дням, «сегодня/неделя», prune, сериализация. */
class TrafficHistoryTest {

    private val day = TrafficHistory.DAY_MS

    @Test
    fun accumulatesWithinSameDay() {
        val h = TrafficHistory()
        h.add(day * 100, 100, 10)
        h.add(day * 100 + 3_600_000, 50, 5)
        val today = h.today(day * 100 + 7_000_000)
        assertEquals(150, today.downBytes)
        assertEquals(15, today.upBytes)
    }

    @Test
    fun separatesByDay() {
        val h = TrafficHistory()
        h.add(day * 100, 100, 0)
        h.add(day * 101, 200, 0)
        assertEquals(100, h.today(day * 100).downBytes)
        assertEquals(200, h.today(day * 101).downBytes)
    }

    @Test
    fun weekSumsLastSevenDays() {
        val h = TrafficHistory()
        // 10 дней по 100 байт; неделя = последние 7 -> 700
        for (i in 0 until 10) h.add(day * (50 + i), 100, 0)
        assertEquals(700, h.thisWeek(day * 59).downBytes)
    }

    @Test
    fun weekIgnoresOlderThanSevenDays() {
        val h = TrafficHistory()
        h.add(day * 1, 500, 0) // очень старый день
        h.add(day * 59, 10, 0) // сегодня
        assertEquals(10, h.thisWeek(day * 59).downBytes)
    }

    @Test
    fun nonPositiveIgnored() {
        val h = TrafficHistory()
        h.add(day * 100, 0, 0)
        h.add(day * 100, -5, 10)
        assertEquals(0, h.today(day * 100).totalBytes)
        assertEquals(0, h.days().size)
    }

    @Test
    fun pruneDropsOldDays() {
        val h = TrafficHistory()
        h.add(day * 1, 100, 0)
        h.add(day * 59, 10, 0)
        h.prune(day * 59, keep = 7)
        assertEquals(1, h.days().size)
        assertEquals(10, h.today(day * 59).downBytes)
    }

    @Test
    fun encodeDecodeRoundTrips() {
        val h = TrafficHistory()
        h.add(day * 100, 123, 45)
        h.add(day * 101, 678, 90)
        val restored = TrafficHistory.decode(h.encode())
        assertEquals(123, restored.today(day * 100).downBytes)
        assertEquals(678, restored.today(day * 101).downBytes)
        assertEquals(45, restored.today(day * 100).upBytes)
    }

    @Test
    fun decodeSkipsMalformedLines() {
        val restored = TrafficHistory.decode("garbage\n100,5,6\nnot,numbers,here\n101,7,8")
        assertEquals(5, restored.today(day * 100).downBytes)
        assertEquals(7, restored.today(day * 101).downBytes)
    }

    @Test
    fun decodeEmptyTextYieldsEmptyHistory() {
        val restored = TrafficHistory.decode("")
        assertTrue(restored.days().isEmpty())
    }
}
