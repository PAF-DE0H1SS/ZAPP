package xyz.azraellab.zapp.ui.theme

import androidx.compose.ui.graphics.Color

/**
 * Контраст по WCAG 2.1 в одном месте, чтобы проверки палитры и проверки
 * компонентов не расходились формулой. Раньше контраст считался только
 * одноразовым скриптом `/tmp/opencode/contrast.py`, который после правки
 * забывался: палитра могла снова стать нечитаемой, и ни один тест этого не
 * замечал.
 */

/** Относительная яркость по WCAG 2.1 (sRGB → линейные → взвешенная сумма). */
fun relativeLuminance(color: Color): Double {
    fun channel(v: Float): Double {
        val s = v.toDouble()
        return if (s <= 0.03928) s / 12.92 else Math.pow((s + 0.055) / 1.055, 2.4)
    }
    return 0.2126 * channel(color.red) + 0.7152 * channel(color.green) + 0.0722 * channel(color.blue)
}

/** Контраст двух цветов, 1.0…21.0. */
fun contrast(foreground: Color, background: Color): Double {
    val a = relativeLuminance(foreground)
    val b = relativeLuminance(background)
    return (maxOf(a, b) + 0.05) / (minOf(a, b) + 0.05)
}

/** Наложить полупрозрачный `fg` на непрозрачный `bg`. */
fun composite(fg: Color, bg: Color): Color {
    val a = fg.alpha
    return Color(
        red = fg.red * a + bg.red * (1 - a),
        green = fg.green * a + bg.green * (1 - a),
        blue = fg.blue * a + bg.blue * (1 - a),
        alpha = 1f
    )
}

/** Контраст цвета с фоном с учётом альфы: `fg` рисуется поверх `bg`. */
fun contrastOver(fg: Color, bg: Color): Double = contrast(composite(fg, bg), bg)

/**
 * Контраст `fg` с «эффективным» фоном схемы.
 *
 * `AzraelDarkScheme.surface` - это 5% белого (то самое стекло), поэтому
 * сравнивать foreground напрямую с `surface` бессмысленно: получится контраст
 * с прозрачным цветом. Реальная подложка под `surface` - `background`,
 * поэтому сначала кладём `surface` на `background`, потом `fg` на результат.
 */
fun contrastOnScheme(surface: Color, backdrop: Color, fg: Color): Double {
    val effectiveBg = if (surface.alpha < 1f) composite(surface, backdrop) else surface
    return contrastOver(fg, effectiveBg)
}

/** Минимальный контраст для обычного текста (WCAG AA). */
const val TEXT_CONTRAST_MIN = 4.5

/** Минимальный контраст для значимых границ, иконок и нетекстовых элементов (WCAG AA). */
const val NON_TEXT_CONTRAST_MIN = 3.0
