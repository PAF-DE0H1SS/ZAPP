package xyz.azraellab.zapp.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import xyz.azraellab.zapp.core.TrafficPoint
import xyz.azraellab.zapp.core.traffic.SparkPoint
import xyz.azraellab.zapp.core.traffic.sparklinePair
import xyz.azraellab.zapp.ui.theme.AzraelInfo

/**
 * Мини-график скорости для статусной карточки туннеля.
 *
 * Не замена большого графика в окне мониторинга -- это «погода за окном»:
 * секунды, а не минуты истории, без осей и сетки. Показывает пропорцию
 * скачков rx и tx к общему пику за окно, поэтому на пике обе линии
 * прижимаются к верху, а в тишине -- опускаются к низу.
 *
 * Точки считает чистая математика [sparklinePair] (покрыта dev-тестами),
 * здесь только соединение точек линиями и раскладка.
 */
@Composable
fun TrafficSparkline(
    history: List<TrafficPoint>,
    modifier: Modifier = Modifier,
    samples: Int = 48,
    height: Dp = 28.dp
) {
    if (history.size < 2) return
    val rx = history.map { it.rxBitsPerSecond }
    val tx = history.map { it.txBitsPerSecond }
    // Скачивание -- красным (danger), загрузка -- синим (info): цвета
    // совпадают с большими графиками, чтобы читать их одинаково.
    val rxColor = MaterialTheme.colorScheme.error
    val txColor = AzraelInfo
    val base = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.25f)

    Canvas(
        modifier = modifier
            .fillMaxWidth()
            .height(height)
    ) {
        val w = size.width
        val h = size.height
        if (w <= 0f || h <= 0f) return@Canvas

        val (rxPoints, txPoints) = sparklinePair(rx, tx, samples, w, h)
        if (rxPoints.isEmpty() && txPoints.isEmpty()) return@Canvas

        drawLine(base, Offset(0f, h), Offset(w, h), strokeWidth = 1f)

        fun pathOf(points: List<SparkPoint>): Path? {
            if (points.isEmpty()) return null
            val path = Path()
            points.forEachIndexed { i, p ->
                if (i == 0) path.moveTo(p.x, p.y) else path.lineTo(p.x, p.y)
            }
            return path
        }

        pathOf(txPoints)?.let { drawPath(it, txColor, style = Stroke(1.5f, cap = StrokeCap.Round)) }
        pathOf(rxPoints)?.let { drawPath(it, rxColor, style = Stroke(2f, cap = StrokeCap.Round)) }
    }
}
