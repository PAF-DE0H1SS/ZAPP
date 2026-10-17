package xyz.azraellab.zapp.ui.nav

import androidx.compose.ui.graphics.vector.ImageVector
import xyz.azraellab.zapp.core.Str
import xyz.azraellab.zapp.ui.theme.AppIcons

/**
 * Разделы приложения.
 *
 * Раздел -- это и вкладка в нижней навигации, и страница, и заголовок
 * страницы. Одно перечисление на всё убирает рассинхрон: раньше строка
 * заголовка и вкладка задавались независимо, и вкладка «Zapret» могла
 * привести на страницу с заголовком «VPN».
 *
 * Порядок -- порядок в нижней панели: Tor идёт первым, до VPN, потому
 * что это отдельный, самостоятельный способ выхода в сеть, а не подмножество
 * списка коннектов.
 *
 * [short] -- метка для нижней панели: полные названия в парных кнопках
 * («T | V», «Z | GDPI») не помещаются, поэтому там идут сокращения.
 */
enum class AppTab(val title: Str, val short: Str, val icon: ImageVector, val activeIcon: ImageVector = icon) {
    TOR(Str.TAB_TOR, Str.TAB_TOR_SHORT, AppIcons.Tor, AppIcons.TorActive),
    VPN(Str.TAB_VPN, Str.TAB_VPN_SHORT, AppIcons.Vpn, AppIcons.VpnActive),
    ZAPRET(Str.TAB_ZAPRET, Str.TAB_ZAPRET_SHORT, AppIcons.Dpi, AppIcons.DpiActive),
    GOODBYE_DPI(Str.TAB_GOODBYE_DPI, Str.TAB_GOODBYE_DPI_SHORT, AppIcons.Globe, AppIcons.GlobeActive),
    GPS(Str.TAB_GPS, Str.TAB_GPS, AppIcons.Location, AppIcons.LocationActive),
    SETTINGS(Str.SETTINGS, Str.SETTINGS, AppIcons.Settings, AppIcons.SettingsActive);

    companion object {
        val first: AppTab = TOR

        /**
         * Вкладки, доступные на этой платформе.
         *
         * Подмена координат системным способом есть только на Android, поэтому
         * на десктопе раздел не показывается вовсе, а не показывается
         * заглушкой «недоступно». Пустая вкладка, которая ничего не делает,
         * хуже отсутствующей: её нажатие выглядит как поломка.
         *
         * [noviceMode] — простой режим: остаётся только базовый сценарий
         * (подключить VPN, выбрать коннект, настройки), всё остальное
         * спрятано, а не выключено. Порядок entries сохраняется.
         */
        fun available(gpsSupported: Boolean, noviceMode: Boolean = false): List<AppTab> =
            if (noviceMode) entries.filter { it == VPN || it == SETTINGS }
            else entries.filter { it != GPS || gpsSupported }
    }
}
