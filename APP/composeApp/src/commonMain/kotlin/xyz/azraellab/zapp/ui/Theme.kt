package xyz.azraellab.zapp.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import xyz.azraellab.zapp.ui.theme.AzraelCornerGlass
import xyz.azraellab.zapp.ui.theme.AzraelSpace
import xyz.azraellab.zapp.ui.theme.AzraelTheme
import xyz.azraellab.zapp.ui.theme.isDark

// Палитра, типографика и режимы темы живут в ui/theme (единая дизайн-система).
// Здесь только совместимые псевдонимы: код импортирует `xyz.azraellab.zapp.ui.*`,
// и переезд на новые имена идёт постепенно, без правки всех экранов разом.
val AzraelPrimary = xyz.azraellab.zapp.ui.theme.AzraelPrimary
val AzraelOnPrimary = xyz.azraellab.zapp.ui.theme.AzraelOnPrimary
val AzraelSecondary = xyz.azraellab.zapp.ui.theme.AzraelSecondary
val AzraelDanger = xyz.azraellab.zapp.ui.theme.AzraelDanger
val AzraelBgDeep = xyz.azraellab.zapp.ui.theme.AzraelBgDeep
val AzraelBgCard = xyz.azraellab.zapp.ui.theme.AzraelBgCard
val AzraelBorder = xyz.azraellab.zapp.ui.theme.AzraelBorder
val AzraelTextDim = xyz.azraellab.zapp.ui.theme.AzraelTextDim

/**
 * Точка входа темы приложения.
 *
 * Режима нет -- тема одна, тёмная, поэтому здесь нечего восстанавливать из
 * хранилища: корень только натягивает схему и отдаёт содержимое.
 */
@Composable
fun AppThemeRoot(content: @Composable () -> Unit) {
    AzraelTheme(content = content)
}

@Composable
fun GlassBackground(modifier: Modifier = Modifier, content: @Composable BoxScope.() -> Unit) {
    val scheme = MaterialTheme.colorScheme
    // Градиент фона раньше был зашит на тёмную схему (#0C110C → #0A0A0A → #070707),
    // поэтому светлая тема выглядела как «тёмное приложение с чёрным текстом»:
    // схема переключалась, а подложка под ней оставалась тёмной. Теперь края
    // берутся из самой схемы, и `background` в середине - её собственный цвет.
    val deep = scheme.background
    val top = if (scheme.isDark()) Color(0xFF0C110C) else Color(0xFFF1F6F2)
    val bottom = if (scheme.isDark()) Color(0xFF070707) else Color(0xFFFCFDFC)
    Box(
        modifier = modifier
            .fillMaxSize()
            .background(Brush.verticalGradient(0f to top, 0.4f to deep, 1f to bottom))
    ) {
        AmbientGlow(color = scheme.primary, size = 460.dp, alpha = 0.10f, Modifier.align(Alignment.TopStart))
        AmbientGlow(color = scheme.primary, size = 380.dp, alpha = 0.06f, Modifier.align(Alignment.TopEnd))
        AmbientGlow(color = scheme.secondary, size = 420.dp, alpha = 0.03f, Modifier.align(Alignment.BottomEnd))
        // Звёзды и белые кометы как на сайте (/e2) - под контентом, мышь не ловит.
        StarfieldBackground(Modifier.matchParentSize())
        content()
    }
}

@Composable
private fun AmbientGlow(color: Color, size: Dp, alpha: Float, modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .width(size)
            .height(size)
            .background(
                brush = Brush.radialGradient(
                    colors = listOf(color.copy(alpha = alpha), Color.Transparent),
                    center = Offset(size.value / 2f, size.value / 2f),
                    radius = size.value / 2f
                ),
                shape = CircleShape
            )
    )
}

// «Стеклянная» обёртка карточки: скруглённые углы, полупрозрачная заливка с градиентом
// и тонкая светлая рамка - glassmorphism в духе фирменного стиля.
//
// Функция стала `@Composable`, потому что заливка и рамка берутся из темы.
// Раньше дефолты были `AzraelBgCard` (5% белого) и `AzraelBorder` (18% белого) -
// на тёмной схеме это `surface`/`outline` и выглядело как задумано, на светлой
// 5% белого по белому фону не даёт вообще ничего: карточка исчезала, оставался
// только невидимый контур.
//
// Рамка берётся из `outlineVariant`, а не из `outline`, и это осознанно. `outline`
// теперь несут смысловую нагрузку: они нужны там, где граница - элемент управления
// (рамка `OutlinedTextField`, чипы, фокус), и обязаны держать 3:1. Но `AzraelCard`
// - пассивная поверхность, а не кнопка, и 18% белого на тёмной схеме давали лишь
// 1.64:1 - как декоративная волосяная линия. Если бы карточка взяла `outline`, то
// после усиления `outline` до 3.77:1 она получила бы рамку вдвое плотнее прежней
// и стала бы самой заметной границей на экране. Поэтому схема разводит две роли:
// `outline` - значимая граница, `outlineVariant` - декоративная.
@Composable
fun Modifier.glass(
    corner: Dp = AzraelCornerGlass,
    borderColor: Color = MaterialTheme.colorScheme.outlineVariant,
    fill: Color = MaterialTheme.colorScheme.surface
): Modifier = this
    .clip(RoundedCornerShape(corner))
    .background(
        brush = Brush.linearGradient(
            colors = listOf(fill.copy(alpha = 0.86f), fill.copy(alpha = 0.62f)),
            start = Offset.Zero,
            end = Offset(400f, 400f)
        )
    )
    .border(BorderStroke(AzraelSpace.stroke, borderColor), RoundedCornerShape(corner))
