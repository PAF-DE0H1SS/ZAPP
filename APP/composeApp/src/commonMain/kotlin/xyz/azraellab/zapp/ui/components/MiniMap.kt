package xyz.azraellab.zapp.ui.components

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp
import xyz.azraellab.zapp.core.RoutePoint
import xyz.azraellab.zapp.core.gps.GpsFix
import xyz.azraellab.zapp.core.gps.RouteProjection

/**
 * Мини-карта маршрута: ломаная, старт, финиш и текущая точка.
 *
 * Без тайлов и внешних зависимостей -- схематичный взгляд «где я на
 * маршруте», а не навигатор: рисовать полноразмерную карту ради одной
 * точки положения не стоит ни сети, ни тяжёлой зависимости. Геометрия
 * (проекция bbox, пропорции, вырожденные случаи) живёт в
 * [RouteProjection] и покрыта тестами, здесь только отрисовка.
 *
 * Текущая точка пульсирует -- живой сигнал, что подмена работает.
 */
@Composable
fun RouteMiniMap(
    route: List<RoutePoint>,
    current: GpsFix?,
    modifier: Modifier = Modifier
) {
    if (route.size < 2) return

    val lineColor = MaterialTheme.colorScheme.primary
    val dotColor = MaterialTheme.colorScheme.primary
    val startColor = MaterialTheme.colorScheme.outline
    val endColor = MaterialTheme.colorScheme.error
    val background = MaterialTheme.colorScheme.surfaceVariant
    val ring = MaterialTheme.colorScheme.surface

    // Пульс живёт только пока карта в кадре: rememberInfiniteTransition
    // сам гаснет с композицией, кадровой петли на фоне не остаётся.
    val pulse by rememberInfiniteTransition(label = "minimap").animateFloat(
        initialValue = 0.45f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(900), RepeatMode.Reverse),
        label = "minimapAlpha"
    )

    Canvas(
        modifier = modifier
            .fillMaxWidth()
            .height(140.dp)
    ) {
        if (size.width <= 0f || size.height <= 0f) return@Canvas
        val pad = 10.dp.toPx()
        val projection = RouteProjection.transform(route, size.width, size.height, pad)

        drawRoundRect(
            color = background,
            cornerRadius = CornerRadius(12.dp.toPx())
        )

        val path = Path()
        projection.project(route).forEachIndexed { i, (x, y) ->
            if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
        }
        drawPath(
            path = path,
            color = lineColor,
            style = Stroke(
                width = 3.dp.toPx(),
                cap = StrokeCap.Round,
                join = StrokeJoin.Round
            )
        )

        // Старт -- полый кружок, финиш -- заливной: различимы и без подписей.
        val first = projection.xy(route.first().latitude, route.first().longitude)
        drawCircle(
            color = startColor,
            radius = 5.dp.toPx(),
            center = Offset(first.first, first.second),
            style = Stroke(width = 2.dp.toPx())
        )
        val last = projection.xy(route.last().latitude, route.last().longitude)
        drawCircle(
            color = endColor,
            radius = 5.dp.toPx(),
            center = Offset(last.first, last.second)
        )

        current?.let { fix ->
            val (cx, cy) = projection.xy(fix.latitude, fix.longitude)
            // Подложка-кольцо отделяет точку от линии маршрута.
            drawCircle(
                color = ring,
                radius = 8.dp.toPx(),
                center = Offset(cx, cy)
            )
            drawCircle(
                color = dotColor.copy(alpha = pulse),
                radius = 6.dp.toPx(),
                center = Offset(cx, cy)
            )
        }
    }
}
