package xyz.azraellab.zapp.ui.nav

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * Вкладки: порядок и полнота.
 *
 * Порядок в `entries` -- это порядок в нижней панели, поэтому ломается
 * он молча: компилятор не заметит перестановку, а пользователь увидит
 * панель не в том порядке. Tor обязан быть первым, до VPN -- это
 * отдельный самостоятельный способ выхода в сеть, а не пункт списка
 * коннектов.
 */
class AppTabTest {

    @Test
    fun torGoesBeforeVpn() {
        val order = AppTab.entries.map { it.name }
        assertEquals("TOR", order.first())
        assertTrue(order.indexOf("TOR") < order.indexOf("VPN"))
        assertEquals(AppTab.TOR, AppTab.first)
    }

    @Test
    fun coreTabsAlwaysPresent() {
        // Без GPS -- потому что подмена координат есть только на Android.
        // Остальное должно быть всегда, иначе нижняя панель теряет раздел.
        val available = AppTab.available(gpsSupported = false)
        assertEquals(
            listOf("TOR", "VPN", "ZAPRET", "GOODBYE_DPI", "SETTINGS"),
            available.map { it.name }
        )
        // GPS встаёт на своё место в entries (перед SETTINGS), а не в хвост.
        assertEquals(
            listOf("TOR", "VPN", "ZAPRET", "GOODBYE_DPI", "GPS", "SETTINGS"),
            AppTab.available(gpsSupported = true).map { it.name }
        )
    }

    @Test
    fun everyTabHasUniqueTitleAndIcon() {
        // Заголовок и иконка входят в композицию панели: два одинаковых
        // ключа сломали бы подпись вкладки, одинаковые иконки -- выбор.
        assertEquals(AppTab.entries.size, AppTab.entries.map { it.title }.toSet().size)
        assertEquals(AppTab.entries.size, AppTab.entries.map { it.icon }.toSet().size)
        AppTab.entries.forEach { tab ->
            assertNotEquals("", tab.title.name)
        }
    }

    @Test
    fun pairsShareSlots() {
        // Панель: четыре слота вместо шести вкладок. Пара -- только
        // TOR+VPN и Zapret+GoodbyeDPI, остальное по одному.
        assertEquals(
            listOf(listOf("TOR", "VPN"), listOf("ZAPRET", "GOODBYE_DPI"), listOf("GPS"), listOf("SETTINGS")),
            tabSlots(AppTab.available(gpsSupported = true)).map { it.map(AppTab::name) }
        )
        // Без GPS слот убирается целиком, пары не рассыпаются.
        assertEquals(
            listOf(listOf("TOR", "VPN"), listOf("ZAPRET", "GOODBYE_DPI"), listOf("SETTINGS")),
            tabSlots(AppTab.available(gpsSupported = false)).map { it.map(AppTab::name) }
        )
        // Неполная пара не собирается: VPN без TOR в своей кнопке не живёт.
        assertEquals(
            listOf(listOf("VPN"), listOf("ZAPRET"), listOf("SETTINGS")),
            tabSlots(listOf(AppTab.VPN, AppTab.ZAPRET, AppTab.SETTINGS)).map { it.map(AppTab::name) }
        )
    }
}
