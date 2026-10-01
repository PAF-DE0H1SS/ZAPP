package xyz.azraellab.zapp.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Shapes
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

val AzraelCornerXs: Dp = 6.dp
val AzraelCornerSm: Dp = 10.dp
val AzraelCornerMd: Dp = 14.dp
val AzraelCornerLg: Dp = 20.dp
val AzraelCornerXl: Dp = 26.dp
val AzraelCornerGlass: Dp = 22.dp

val AzraelShapes = Shapes(
    extraSmall = RoundedCornerShape(AzraelCornerXs),
    small = RoundedCornerShape(AzraelCornerSm),
    medium = RoundedCornerShape(AzraelCornerMd),
    large = RoundedCornerShape(AzraelCornerLg),
    extraLarge = RoundedCornerShape(AzraelCornerXl)
)

fun cornerGlass(): RoundedCornerShape = RoundedCornerShape(AzraelCornerGlass)
