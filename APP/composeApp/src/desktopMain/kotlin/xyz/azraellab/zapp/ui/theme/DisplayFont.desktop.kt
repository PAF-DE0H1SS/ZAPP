package xyz.azraellab.zapp.ui.theme

import androidx.compose.ui.text.ExperimentalTextApi
import androidx.compose.ui.text.font.FontFamily

/**
 * Системное семейство по имени. Если в дистрибутиве нет ни одного
 * condensed-шрифта, Compose отдаёт фолбэк -- заголовки останутся обычными,
 * вёрстка не поедет.
 */
@OptIn(ExperimentalTextApi::class)
internal actual val DisplayFont: FontFamily = FontFamily("sans-serif-condensed")
