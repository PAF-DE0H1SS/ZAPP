package xyz.azraellab.zapp.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class TvModeTest {

    @Test
    fun tvBoostsFontScale() {
        assertEquals(1.3f, tvFontScale(1f, tvMode = true))
        // Системное увеличение шрифта не выбрасывается, а усиливается.
        assertEquals(1.56f, tvFontScale(1.2f, tvMode = true), absoluteTolerance = 0.001f)
    }

    @Test
    fun nonTvKeepsSystemScale() {
        assertEquals(1f, tvFontScale(1f, tvMode = false))
        assertEquals(1.2f, tvFontScale(1.2f, tvMode = false))
    }

    @Test
    fun tvScaleIsAlwaysBigger() {
        assertTrue(tvFontScale(1f, tvMode = true) > tvFontScale(1f, tvMode = false))
    }
}
