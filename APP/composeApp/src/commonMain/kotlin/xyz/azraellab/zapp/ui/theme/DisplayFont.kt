package xyz.azraellab.zapp.ui.theme

import androidx.compose.ui.text.font.FontFamily

/**
 * Сжатое системное семейство для крупных заголовков (display/headline).
 *
 * В common-слоя готового `FontFamily.SansSerifCondensed` нет, поэтому
 * платформы отдают своё: Android -- «sans-serif-condensed» (Roboto Condensed
 * есть на любом устройстве), десктоп -- системное имя с фолбэком на обычный
 * sans, если дистрибутив condensed не ставил.
 */
internal expect val DisplayFont: FontFamily
