package xyz.azraellab.zapp.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import xyz.azraellab.zapp.core.ThemeMode

/**
 * Тема приложения: тёмная по умолчанию, светлая -- по выбору.
 *
 * Выбор режима -- единственный вход: [AzraelTheme] не читает хранилище сам,
 * режим приходит снаружи (из [AppConfig.themeMode]), и UI переключается без
 * правки экранов -- токены разведены в [AzraelDarkScheme]/[AzraelLightScheme].
 *
 * Material You: при включённом `dynamicColors` палитра берётся из обоев
 * устройства. Мост [dynamicColorScheme] отдаёт null на платформах без
 * динамики, и тогда остаётся схема режима -- переключатель честен везде.
 *
 * [isSystemInDarkTheme] используется только для [ThemeMode.SYSTEM]; прочерк
 * (никогда не затемняется) означает светлую.
 */
@Composable
fun AzraelTheme(
    mode: ThemeMode = ThemeMode.DARK,
    dynamicColors: Boolean = false,
    content: @Composable () -> Unit
) {
    val systemDark = isSystemInDarkTheme()
    val dark = when (mode) {
        ThemeMode.SYSTEM -> systemDark
        ThemeMode.DARK -> true
        ThemeMode.LIGHT -> false
    }
    val base: ColorScheme = if (dark) AzraelDarkScheme else AzraelLightScheme
    val scheme = if (dynamicColors) {
        dynamicColorScheme(isDark = dark) ?: base
    } else {
        base
    }
    MaterialTheme(
        colorScheme = scheme,
        typography = AzraelTypography,
        shapes = AzraelShapes
    ) {
        // Цвет текста по умолчанию. Без этой строки в иерархии действует
        // дефолт foundation -- чёрный, потому что нигде нет Surface: заголовки
        // строк настроек и другие Text без явного цвета рисовались чёрным
        // по тёмной карточке и были невидимы. MaterialTheme LocalContentColor
        // не задаёт, поэтому провайдер ставится здесь, один раз на всё приложение.
        CompositionLocalProvider(LocalContentColor provides scheme.onSurface) {
            AzraelIndicationTheme(content)
        }
    }
}
