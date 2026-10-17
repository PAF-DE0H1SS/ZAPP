package xyz.azraellab.zapp.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SourceFreshnessTest {

    private val hourMs = 3_600_000L

    @Test
    fun freshWithinOnePeriod() {
        val now = 100 * hourMs
        assertEquals(
            SourceFreshness.FRESH,
            sourceFreshness(now, now - hourMs, refreshSeconds = 7200)
        )
    }

    @Test
    fun staleAfterThreePeriods() {
        // Три периода (6ч при 2ч-периоде) позади -- источник устарел.
        val now = 100 * hourMs
        assertEquals(
            SourceFreshness.STALE,
            sourceFreshness(now, now - 6 * hourMs, refreshSeconds = 7200)
        )
    }

    @Test
    fun singleMissedPeriodStaysFresh() {
        // Один проскоченный период -- пауза ноутбука, а не устаревание.
        val now = 100 * hourMs
        assertEquals(
            SourceFreshness.FRESH,
            sourceFreshness(now, now - 3 * hourMs, refreshSeconds = 7200)
        )
    }

    @Test
    fun neverRefreshedIsNever() {
        assertEquals(
            SourceFreshness.NEVER,
            sourceFreshness(nowMs = hourMs, lastRefreshAtMs = 0L, refreshSeconds = 7200)
        )
    }

    @Test
    fun manualRefreshModeIsAlwaysFresh() {
        // Автообновитель выключен (refreshSeconds <= 0): человек сам
        // решает, когда обновлять, и счёт не должен навязываться.
        val now = 100 * hourMs
        assertEquals(
            SourceFreshness.FRESH,
            sourceFreshness(now, now - 1000 * hourMs, refreshSeconds = 0)
        )
        assertEquals(
            SourceFreshness.FRESH,
            sourceFreshness(now, now - 1000 * hourMs, refreshSeconds = -5)
        )
    }

    @Test
    fun ageTextScalesUnits() {
        val now = 100 * hourMs
        assertEquals("—", sourceAgeText(now, 0L))
        assertEquals("0m", sourceAgeText(now, now))
        assertEquals("45m", sourceAgeText(now, now - 45 * 60_000L))
        assertEquals("3h", sourceAgeText(now, now - 3 * hourMs))
        assertEquals("2d", sourceAgeText(now, now - 48 * hourMs))
    }

    @Test
    fun ageNeverGoesNegative() {
        // Часы могли уйти назад (смена таймзоны): возраст не отрицательный.
        val now = hourMs
        assertEquals("0m", sourceAgeText(now, now + hourMs))
        assertTrue(sourceAgeText(now, now + hourMs).first().isDigit())
    }
}
