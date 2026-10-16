package xyz.azraellab.zapp.ui.theme

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.filled.Block
import androidx.compose.material.icons.filled.Hub
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.Place
import androidx.compose.material.icons.filled.Public
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.outlined.Block
import androidx.compose.material.icons.outlined.Bolt
import androidx.compose.material.icons.outlined.Dns
import androidx.compose.material.icons.outlined.GpsFixed
import androidx.compose.material.icons.outlined.Hub
import androidx.compose.material.icons.outlined.Language
import androidx.compose.material.icons.outlined.ListAlt
import androidx.compose.material.icons.outlined.Place
import androidx.compose.material.icons.outlined.Public
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.Shield
import androidx.compose.material.icons.outlined.Speed
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.ui.graphics.vector.ImageVector

/**
 * Единый набор иконок приложения.
 *
 * Стиль -- outlined (контурные) по образцу karing: одна штриховка, один радиус,
 * одна сетка 24dp. Material Icons Extended даёт готовые outlined-глифы, свои
 * векторы не нужны. Filled-версии оставлены только там, где состояние обязано
 * быть залитым (выбранная вкладка, активный элемент), контекст -- в компонентах.
 *
 * Семантика:
 *  * [Tor] / [TorActive] -- «мост/хаб» Tor (узел с лучами), как вкладка T|V;
 *  * [Vpn] / [VpnActive] -- щит VPN (закрытый контур), как вкладка Z|GDPI;
 *  * [Dpi] / [DpiActive] -- блок DPI (перечёркнутый квадрат);
 *  * [Globe] / [GlobeActive] -- глобус GoodbyeDPI (альтернативный обход);
 *  * [Location] / [LocationActive] -- метка GPS;
 *  * [Settings] / [SettingsActive] -- шестерёнка настроек;
 *  * [Bolt] -- скорость/подключение; [Dns] -- DNS; [List] -- список коннектов;
 *  * [Gps] -- фикс координат; [Language] -- язык/регион; [Speed] -- трафик;
 *  * [Search], [Refresh], [Chevron], [Back] -- навигация и действия.
 */
object AppIcons {
    // Вкладки: outlined для покоя, filled -- для выбранной (индикация).
    val Tor: ImageVector get() = Icons.Outlined.Hub
    val TorActive: ImageVector get() = Icons.Filled.Hub
    val Vpn: ImageVector get() = Icons.Outlined.Shield
    val VpnActive: ImageVector get() = Icons.Filled.Shield
    val Dpi: ImageVector get() = Icons.Outlined.Block
    val DpiActive: ImageVector get() = Icons.Filled.Block
    val Globe: ImageVector get() = Icons.Outlined.Public
    val GlobeActive: ImageVector get() = Icons.Filled.Public
    val Location: ImageVector get() = Icons.Outlined.Place
    val LocationActive: ImageVector get() = Icons.Filled.Place
    val Settings: ImageVector get() = Icons.Outlined.Settings
    val SettingsActive: ImageVector get() = Icons.Filled.Tune

    // Действия и контент.
    val Bolt: ImageVector get() = Icons.Outlined.Bolt
    val Dns: ImageVector get() = Icons.Outlined.Dns
    val List: ImageVector get() = Icons.Outlined.ListAlt
    val Gps: ImageVector get() = Icons.Outlined.GpsFixed
    val Language: ImageVector get() = Icons.Outlined.Language
    val Speed: ImageVector get() = Icons.Outlined.Speed
    val Search: ImageVector get() = Icons.Outlined.Search
    val Refresh: ImageVector get() = Icons.Outlined.Refresh
    val Chevron: ImageVector get() = Icons.Filled.KeyboardArrowDown
    val Back: ImageVector get() = Icons.AutoMirrored.Outlined.ArrowBack
    val BackFilled: ImageVector get() = Icons.AutoMirrored.Filled.ArrowBack
}
