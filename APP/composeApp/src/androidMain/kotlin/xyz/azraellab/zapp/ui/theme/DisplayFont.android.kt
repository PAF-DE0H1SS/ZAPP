package xyz.azraellab.zapp.ui.theme

import android.graphics.Typeface
import androidx.compose.ui.text.font.FontFamily

/**
 * Roboto Condensed живёт в системе под именем sans-serif-condensed;
 * [Typeface.create] при отсутствии вернёт обычный sans -- заголовки в
 * худшем случае будут неконденсированными, а не сломанными.
 */
internal actual val DisplayFont: FontFamily = FontFamily(
    Typeface.create("sans-serif-condensed", Typeface.NORMAL)
)
