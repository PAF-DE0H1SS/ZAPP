package xyz.azraellab.zapp.ui.theme

import androidx.compose.material3.Typography
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

private fun TextStyle.azrael(
    weight: FontWeight = FontWeight.Normal,
    tracking: Double = 0.0
): TextStyle = copy(
    fontFamily = FontFamily.Default,
    fontWeight = weight,
    letterSpacing = tracking.sp,
    lineHeightStyle = Trim
)

val AzraelTypography = Typography(
    displayLarge = Base.displayLarge.azrael(FontWeight.Bold, -1.0),
    displayMedium = Base.displayMedium.azrael(FontWeight.Bold, -0.5),
    displaySmall = Base.displaySmall.azrael(FontWeight.SemiBold, -0.5),
    headlineLarge = Base.headlineLarge.azrael(FontWeight.SemiBold, -0.5),
    headlineMedium = Base.headlineMedium.azrael(FontWeight.SemiBold, -0.25),
    headlineSmall = Base.headlineSmall.azrael(FontWeight.SemiBold),
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
