package xyz.azraellab.zapp.ui.nav

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Block
import androidx.compose.material.icons.filled.Public
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material.icons.filled.Place
import androidx.compose.material.icons.filled.Tune
import androidx.compose.ui.graphics.vector.ImageVector
import xyz.azraellab.zapp.core.Str

/**
 * Разделы приложения.
 *
 * Раздел -- это и вкладка в нижней навигации, и страница, и заголовок
 * страницы. Одно перечисление на всё убирает рассинхрон: раньше строка
 * заголовка и вкладка задавались независимо, и вкладка «Zapret» могла
 * привести на страницу с заголовком «VPN».
 */
enum class AppTab(val title: Str, val icon: ImageVector) {
    VPN(Str.TAB_VPN, Icons.Filled.Shield),
    ZAPRET(Str.TAB_ZAPRET, Icons.Filled.Block),
    GOODBYE_DPI(Str.TAB_GOODBYE_DPI, Icons.Filled.Public),
    GPS(Str.TAB_GPS, Icons.Filled.Place),
    SETTINGS(Str.SETTINGS, Icons.Filled.Tune);

    companion object {
        val first: AppTab = VPN

        /**
         * Вкладки, доступные на этой платформе.
         *
         * Подмена координат системным способом есть только на Android, поэтому
         * на десктопе раздел не показывается вовсе, а не показывается
         * заглушкой «недоступно». Пустая вкладка, которая ничего не делает,
         * хуже отсутствующей: её нажатие выглядит как поломка.
         */
        fun available(gpsSupported: Boolean): List<AppTab> =
            entries.filter { it != GPS || gpsSupported }
    }
}
