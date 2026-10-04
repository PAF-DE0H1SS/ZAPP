package xyz.azraellab.zapp.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Мини-опросник приветствия: как ответы меняют настройки.
 *
 * Маппинг -- чистая функция [UsageTuner.apply], поэтому здесь проверяются
 * ровно те правила, которые видит пользователь: класс устройства меняет
 * производительность, приоритет -- фон, цель -- поведение VPN. Всё, что
 * не следует из ответа однозначно, меняться не должно.
 */
class UsageTunerTest {

    private val defaults = AppConfig()

    private fun usage(
        purpose: String = "",
        device: String = "",
        priority: String = "",
        applied: Boolean = true
    ) = UsageProfile(purpose = purpose, device = device, priority = priority, applied = applied)

    // --- Неприменённый опросник ничего не меняет ---

    @Test
    fun notAppliedProfileKeepsConfigUntouched() {
        // Незаполненный опросник не пишет ничего: config возвращается как есть,
        // сами ответы записывает экран в config.usage отдельным mutate.
        val usage = usage(device = UsageProfile.DEVICE_LOW, applied = false)
        assertEquals(defaults, UsageTuner.apply(defaults, usage))
    }

    @Test
    fun emptyAnswersKeepDefaults() {
        val cfg = UsageTuner.apply(defaults, usage())
        assertEquals(defaults.perf, cfg.perf)
        assertEquals(defaults.vpn.groupMode, cfg.vpn.groupMode)
        assertEquals(defaults.vpn.killSwitch, cfg.vpn.killSwitch)
    }

    @Test
    fun unknownCodesAreIgnored() {
        val cfg = UsageTuner.apply(
            defaults,
            usage(purpose = "mining", device = "toaster", priority = "quantum")
        )
        assertEquals(defaults.perf, cfg.perf)
        assertEquals(defaults.vpn, cfg.vpn)
    }

    // --- Класс устройства: размер страницы списка ---

    @Test
    fun deviceClassSetsListPageSize() {
        assertEquals(15, UsageTuner.apply(defaults, usage(device = UsageProfile.DEVICE_LOW)).perf.listPageSize)
        assertEquals(30, UsageTuner.apply(defaults, usage(device = UsageProfile.DEVICE_MID)).perf.listPageSize)
        assertEquals(60, UsageTuner.apply(defaults, usage(device = UsageProfile.DEVICE_HIGH)).perf.listPageSize)
    }

    @Test
    fun lowDeviceGetsSlowerBackground() {
        val perf = UsageTuner.apply(defaults, usage(device = UsageProfile.DEVICE_LOW)).perf
        assertEquals(20, perf.backgroundFps)
        assertTrue(perf.animatedBackground, "слабое устройство не выключает фон, а замедляет")
    }

    // --- Приоритет: батарея гасит анимацию ---

    @Test
    fun batteryPriorityDisablesAnimatedBackground() {
        val perf = UsageTuner.apply(defaults, usage(priority = UsageProfile.PRIORITY_BATTERY)).perf
        assertFalse(perf.animatedBackground)
        assertEquals(15, perf.backgroundFps)
    }

    @Test
    fun speedAndStabilityKeepBackground() {
        for (priority in listOf(UsageProfile.PRIORITY_SPEED, UsageProfile.PRIORITY_STABILITY)) {
            val perf = UsageTuner.apply(defaults, usage(priority = priority)).perf
            assertTrue(perf.animatedBackground, priority)
            assertEquals(30, perf.backgroundFps, priority)
        }
    }

    // --- Цель использования: поведение VPN ---

    @Test
    fun privacyEnablesKillSwitch() {
        val vpn = UsageTuner.apply(defaults, usage(purpose = UsageProfile.PURPOSE_PRIVACY)).vpn
        assertTrue(vpn.killSwitch)
    }

    @Test
    fun chatAndVideoEnableGroupMode() {
        for (purpose in listOf(UsageProfile.PURPOSE_CHAT, UsageProfile.PURPOSE_VIDEO)) {
            val vpn = UsageTuner.apply(defaults, usage(purpose = purpose)).vpn
            assertTrue(vpn.groupMode, purpose)
        }
    }

    @Test
    fun gamesAndUnknownPurposeDoNotTouchVpn() {
        for (purpose in listOf(UsageProfile.PURPOSE_GAMES, "")) {
            val cfg = UsageTuner.apply(defaults, usage(purpose = purpose))
            assertEquals(defaults.vpn.groupMode, cfg.vpn.groupMode, purpose)
            assertEquals(defaults.vpn.killSwitch, cfg.vpn.killSwitch, purpose)
        }
    }

    @Test
    fun purposeNeverTouchesBypassSettings() {
        // Опросник не имеет права менять стратегии обхода: это ручная
        // настройка под сеть, и ответ «игры» не должен её перекрашивать.
        val cfg = UsageTuner.apply(defaults, usage(purpose = UsageProfile.PURPOSE_GAMES))
        assertEquals(defaults.zapret, cfg.zapret)
        assertEquals(defaults.goodbyeDpi, cfg.goodbyeDpi)
    }

    // --- Санитаризация ---

    @Test
    fun perfSanitizedClampsToBounds() {
        val perf = PerfConfig(backgroundFps = 9999, listPageSize = 1).sanitized()
        assertEquals(60, perf.backgroundFps)
        assertEquals(10, perf.listPageSize)

        val low = PerfConfig(backgroundFps = 1, listPageSize = 100000).sanitized()
        assertEquals(15, low.backgroundFps)
        assertEquals(120, low.listPageSize)
    }

    @Test
    fun combinedAnswersComposeTogether() {
        val cfg = UsageTuner.apply(
            defaults,
            usage(
                purpose = UsageProfile.PURPOSE_PRIVACY,
                device = UsageProfile.DEVICE_LOW,
                priority = UsageProfile.PRIORITY_BATTERY
            )
        )
        assertEquals(15, cfg.perf.listPageSize)
        assertFalse(cfg.perf.animatedBackground)
        assertEquals(15, cfg.perf.backgroundFps)
        assertTrue(cfg.vpn.killSwitch)
    }
}
