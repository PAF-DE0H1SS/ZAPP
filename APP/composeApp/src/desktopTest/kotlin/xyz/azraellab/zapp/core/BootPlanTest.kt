package xyz.azraellab.zapp.core

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * План автозапуска: снимок намерений из конфига.
 *
 * Реестр (и судьба ресивера BOOT_COMPLETED) строится именно из этих
 * трёх флагов, поэтому условия обязаны зеркалить и мастер-тумблер,
 * и намерения сессий: построил план -- подними ресивер, пустой --
 * погаси.
 */
class BootPlanTest {

    private fun config(block: (AppConfig) -> AppConfig): AppConfig =
        block(AppConfig())

    @Test
    fun `дефолтный конфиг -- пустой план`() {
        assertTrue(BootPlan.of(AppConfig()).isEmpty)
    }

    @Test
    fun `мастер выключен -- план пустой, что бы ни было в сессиях`() {
        val plan = BootPlan.of(config {
            it.copy(
                bootAutostart = false,
                vpn = it.vpn.copy(autoStart = true),
                zapret = it.zapret.copy(enabled = true),
                gps = it.gps.copy(enabled = true)
            )
        })
        assertTrue(plan.isEmpty)
        assertFalse(plan.vpn)
        assertFalse(plan.dpi)
        assertFalse(plan.gps)
    }

    @Test
    fun `максимум намерений -- все три флага`() {
        val plan = BootPlan.of(config {
            it.copy(
                bootAutostart = true,
                vpn = it.vpn.copy(autoStart = true),
                zapret = it.zapret.copy(enabled = true),
                gps = it.gps.copy(enabled = true)
            )
        })
        assertTrue(plan.vpn)
        assertTrue(plan.dpi)
        assertTrue(plan.gps)
        assertFalse(plan.isEmpty)
    }

    @Test
    fun `dpi-план от доброго DPI, а не только от zapret`() {
        val plan = BootPlan.of(config {
            it.copy(
                bootAutostart = true,
                goodbyeDpi = it.goodbyeDpi.copy(autoStart = true)
            )
        })
        assertTrue(plan.dpi)
        assertFalse(plan.vpn)
        assertFalse(plan.gps)
    }

    @Test
    fun `gps с выключенным режимом не обещает старт`() {
        val plan = BootPlan.of(config {
            it.copy(
                bootAutostart = true,
                // Намерение есть, но режим гасит его: автостарт не обещаем.
                gps = it.gps.copy(enabled = true, mode = GpsMode.DISABLED.code)
            )
        })
        assertFalse(plan.gps)
        assertTrue(plan.isEmpty)
    }

    @Test
    fun `пустота плана совпадает с отсутствием флагов`() {
        assertFalse(BootPlan(vpn = true).isEmpty)
        assertFalse(BootPlan(dpi = true).isEmpty)
        assertFalse(BootPlan(gps = true).isEmpty)
        assertTrue(BootPlan().isEmpty)
    }
}
