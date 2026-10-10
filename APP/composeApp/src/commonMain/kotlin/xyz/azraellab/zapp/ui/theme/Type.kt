package xyz.azraellab.zapp.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.runtime.Composable
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.LineHeightStyle
import androidx.compose.ui.unit.sp

private val Base = Typography()

private val Trim = LineHeightStyle(
    alignment = LineHeightStyle.Alignment.Center,
    trim = LineHeightStyle.Trim.None
)

/**
 * Сжатый системный шрифт для крупных заголовков (display/headline):
 * заголовок страницы читается как «приборная» надпись и экономит место в
 * узких колонках. Тело, подписи и кнопки остаются на обычном sans --
 * condensed на мелком кегле хуже читается.
 */
private val Display: FontFamily = DisplayFont

private fun TextStyle.azrael(
    weight: FontWeight = FontWeight.Normal,
    tracking: Double = 0.0,
    family: FontFamily = FontFamily.Default
): TextStyle = copy(
    fontFamily = family,
    fontWeight = weight,
    letterSpacing = tracking.sp,
    lineHeightStyle = Trim
)

val AzraelTypography = Typography(
    displayLarge = Base.displayLarge.azrael(FontWeight.Bold, -1.0, Display),
    displayMedium = Base.displayMedium.azrael(FontWeight.Bold, -0.5, Display),
    displaySmall = Base.displaySmall.azrael(FontWeight.SemiBold, -0.5, Display),
    headlineLarge = Base.headlineLarge.azrael(FontWeight.SemiBold, -0.5, Display),
    headlineMedium = Base.headlineMedium.azrael(FontWeight.SemiBold, -0.25, Display),
    headlineSmall = Base.headlineSmall.azrael(FontWeight.SemiBold, family = Display),
    titleLarge = Base.titleLarge.azrael(FontWeight.SemiBold),
    titleMedium = Base.titleMedium.azrael(FontWeight.SemiBold, 0.1),
    titleSmall = Base.titleSmall.azrael(FontWeight.Medium, 0.1),
    bodyLarge = Base.bodyLarge.azrael(tracking = 0.15),
    bodyMedium = Base.bodyMedium.azrael(tracking = 0.15),
    bodySmall = Base.bodySmall.azrael(tracking = 0.2),
    labelLarge = Base.labelLarge.azrael(FontWeight.Medium, 0.1),
    labelMedium = Base.labelMedium.azrael(FontWeight.Medium, 0.4),
    labelSmall = Base.labelSmall.azrael(FontWeight.Medium, 0.4)
)

/**
 * Стиль данных: цифры скорости, трафика, задержек, адреса.
 *
 * Моноширинный, чтобы значения не «прыгали» при обновлении раз в секунду
 * (цифры одинаковой ширины -- столбец стоит ровно). Где-нибудь в логе или в
 * описании текста его применять не надо: это прибор, а не третий шрифт
 * интерфейса.
 */
@Composable
fun monoData(): TextStyle = MaterialTheme.typography.bodyMedium.copy(
    fontFamily = FontFamily.Monospace,
    fontWeight = FontWeight.Medium,
    letterSpacing = 0.sp
)
