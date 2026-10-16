package xyz.azraellab.zapp.ui.theme

import androidx.compose.material3.ColorScheme

/**
 * Динамические цвета Material You: палитра из обоев устройства.
 *
 * Возвращает null там, где динамики нет (Android до 12, десктоп) -- вызывающий
 * остаётся на выбранной схеме. Отдельный мост, а не `if (android)` в UI:
 * commonMain не знает про API-уровни, а знать их и не должен.
 */
expect fun dynamicColorScheme(isDark: Boolean): ColorScheme?
