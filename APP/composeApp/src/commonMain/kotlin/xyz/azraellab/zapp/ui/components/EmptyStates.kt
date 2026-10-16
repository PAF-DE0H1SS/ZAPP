package xyz.azraellab.zapp.ui.components

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import xyz.azraellab.zapp.ui.theme.AzraelCornerXs
import xyz.azraellab.zapp.ui.theme.AzraelSpace

/**
 * Иллюстрации пустых состояний и скелетоны загрузки.
 *
 * Пустое состояние без картинки -- голая строка текста: пользователь не видит
 * разницы между «нет данных» и «загрузка не успела». Компоненты ниже дают
 * три вида визуального ответа:
 *
 *  * [EmptyIllustration] -- декоративный орб-узел (как на карточке VPN),
 *    рисуется Canvas'ом, единый стиль для всех пустых списков;
 *  * [ZappSkeleton] -- серая пульсирующая полоска для строк загрузки;
 *  * [ZappSkeletonList] -- набор полосок-заглушек для списка.
 *
 * Орб статичный (без анимации), чтобы не жечь CPU на N2: пустое состояние
 * рисуется один раз и не требует кадровой петли.
 */
@Composable
fun EmptyIllustration(
    tint: Color = MaterialTheme.colorScheme.onSurfaceVariant,
    size: Dp = 72.dp,
    modifier: Modifier = Modifier
) {
    Canvas(modifier = modifier.size(size)) {
        val r = this.size.minDimension / 2f
        val center = Offset(this.size.width / 2f, this.size.height / 2f)
        // Внешнее кольцо -- «орбита» узла.
        drawCircle(
            color = tint.copy(alpha = 0.25f),
            radius = r * 0.82f,
            style = Stroke(width = r * 0.06f)
        )
        // Внутренний шар -- сам узел.
        drawCircle(
            brush = Brush.radialGradient(
                colors = listOf(tint.copy(alpha = 0.5f), tint.copy(alpha = 0.12f)),
                center = center,
                radius = r * 0.48f
            ),
            radius = r * 0.48f,
            center = center
        )
        // Три маленьких спутника на орбите -- «узлы вокруг».
        val satellite = Path().apply {
            val sr = r * 0.10f
            addOval(
                androidx.compose.ui.geometry.Rect(
                    offset = Offset(center.x - sr, center.y - r * 0.82f - sr),
                    size = androidx.compose.ui.geometry.Size(sr * 2, sr * 2)
                )
            )
        }
        drawPath(satellite, tint.copy(alpha = 0.45f))
        drawCircle(
            color = tint.copy(alpha = 0.35f),
            radius = r * 0.10f,
            center = Offset(center.x + r * 0.58f, center.y + r * 0.58f)
        )
        drawCircle(
            color = tint.copy(alpha = 0.28f),
            radius = r * 0.08f,
            center = Offset(center.x - r * 0.62f, center.y + r * 0.50f)
        )
        // Черта пустоты под орбом -- намёк на «здесь пока ничего нет».
        drawLine(
            color = tint.copy(alpha = 0.30f),
            start = Offset(center.x - r * 0.45f, center.y + r * 1.15f),
            end = Offset(center.x + r * 0.45f, center.y + r * 1.15f),
            cap = StrokeCap.Round,
            strokeWidth = r * 0.08f
        )
    }
}

/**
 * Одна серая полоска-скелетон. Пульсирует по альфе -- типичный shimmer без
 * отдельной зависимости (Compose-анимация, один бесконечный переход).
 */
@Composable
fun ZappSkeleton(
    width: Dp,
    height: Dp = 14.dp,
    modifier: Modifier = Modifier
) {
    val transition = rememberInfiniteTransition(label = "skeleton")
    val alpha by transition.animateFloat(
        initialValue = 0.25f,
        targetValue = 0.55f,
        animationSpec = infiniteRepeatable(
            animation = tween(750),
            repeatMode = RepeatMode.Reverse
        ),
        label = "skeleton-alpha"
    )
    Box(
        modifier = modifier
            .size(width = width, height = height)
            .clip(RoundedCornerShape(AzraelCornerXs))
            .alpha(alpha)
            .background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f))
    )
}

/**
 * Список полосок-заглушек для карточки, где грузится контент (список
 * приложений, коннекты, журнал). Полоска [ZappSkeleton] пульсирует сама.
 */
@Composable
fun ZappSkeletonList(
    rows: Int = 3,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier.fillMaxWidth().padding(vertical = AzraelSpace.xs),
        verticalArrangement = Arrangement.spacedBy(AzraelSpace.sm)
    ) {
        repeat(rows) { index ->
            // Первая строка шире, остальные короче -- имитация списка.
            ZappSkeleton(
                width = if (index == 0) 200.dp else 140.dp,
                height = if (index == 0) 16.dp else 12.dp,
                modifier = Modifier.padding(start = AzraelSpace.md)
            )
        }
    }
}

/**
 * Пустая карточка с иллюстрацией, заголовком и опциональным пояснением:
 * расширенный [ZappEmptyState] с картинкой. Используется там, где
 * раньше был голый `Text`.
 */
@Composable
fun ZappEmptyCard(
    title: String,
    body: String? = null,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier.fillMaxWidth().padding(AzraelSpace.md),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(AzraelSpace.sm)
    ) {
        EmptyIllustration()
        Text(
            text = title,
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurface,
            textAlign = TextAlign.Center
        )
        if (body != null) {
            Text(
                text = body,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center
            )
        }
    }
}
