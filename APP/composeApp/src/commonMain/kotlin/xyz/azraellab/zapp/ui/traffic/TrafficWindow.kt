package xyz.azraellab.zapp.ui.traffic

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import xyz.azraellab.zapp.core.AppState
import xyz.azraellab.zapp.core.Str
import xyz.azraellab.zapp.core.TrafficConfig
import xyz.azraellab.zapp.core.TrafficFormat
import xyz.azraellab.zapp.core.TrafficPoint
import xyz.azraellab.zapp.core.TrafficSummary
import xyz.azraellab.zapp.core.connections.ConnectionsFilter
import xyz.azraellab.zapp.core.connections.ConnNote
import xyz.azraellab.zapp.ui.theme.AppIcons
import xyz.azraellab.zapp.ui.theme.AzraelInfo
import xyz.azraellab.zapp.ui.theme.AzraelSpace
import xyz.azraellab.zapp.ui.theme.monoData
import xyz.azraellab.zapp.ui.components.debugPath
import xyz.azraellab.zapp.ui.components.ZappTextField
import xyz.azraellab.zapp.ui.tr

/**
 * Окно мониторинга трафика.
 *
 * Живёт не в нижней панели, а отдельным экраном поверх неё: мониторинг
 * открывают, посмотрели и закрыли, и он не должен конкурировать с разделами
 * за место навигации. Панель на время показа убирается, поэтому остаётся
 * только кнопка возврата.
 *
 * Опрос счётчиков запускается здесь же: начинать его при каждом старте
 * приложения незачем, если окно никто не открыл.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TrafficWindow(state: AppState, onBack: () -> Unit) {
    val history by state.trafficHistory.collectAsState()
    val summary by state.trafficSummaryFlow.collectAsState()
    val config = state.config.traffic

    LaunchedEffect(Unit) { state.ensureTrafficPolling() }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                title = { Text(tr(Str.TAB_TRAFFIC)) },
                navigationIcon = {
                    IconButton(modifier = Modifier.debugPath("traffic.back"), onClick = onBack) {
                        Icon(AppIcons.Back, contentDescription = null)
                    }
                }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = AzraelSpace.screenPadding)
                .padding(bottom = AzraelSpace.xxl)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(AzraelSpace.cardGap)
        ) {
            SpeedCard(history = history)
            SummaryCard(summary = summary, config = config)
            ConnectionsCard(state = state)
        }
    }
}

/** График скорости. */
@Composable
private fun SpeedCard(history: List<TrafficPoint>) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(AzraelSpace.lg),
            verticalArrangement = Arrangement.spacedBy(AzraelSpace.md)
        ) {
            Text(
                text = tr(Str.TRAFFIC_SPEED),
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(160.dp)
            ) {
                TrafficChart(history = history)
            }
        }
    }
}

/** Сводка за сессию. */
@Composable
private fun SummaryCard(summary: TrafficSummary, config: TrafficConfig) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(AzraelSpace.lg),
            verticalArrangement = Arrangement.spacedBy(AzraelSpace.sm)
        ) {
            SummaryRow(tr(Str.TRAFFIC_TOTAL), TrafficFormat.bytes(summary.totalBytes, config))
            SummaryRow(tr(Str.TRAFFIC_RX), TrafficFormat.bytes(summary.rxBytes, config))
            SummaryRow(tr(Str.TRAFFIC_TX), TrafficFormat.bytes(summary.txBytes, config))
            SummaryRow(tr(Str.TRAFFIC_PEAK), TrafficFormat.speed(summary.peakBitsPerSecond, config))
            SummaryRow(tr(Str.TRAFFIC_AVERAGE), TrafficFormat.speed(summary.averageBitsPerSecond, config))
            SummaryRow(tr(Str.TRAFFIC_SPEED), TrafficFormat.speed(summary.currentBitsPerSecond, config))
            SummaryRow(tr(Str.TRAFFIC_ACTIVE_TIME), TrafficFormat.duration(summary.activeSeconds * 1000))
        }
    }
}

/** Строка сводки: подпись слева, число справа. */
@Composable
private fun SummaryRow(label: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Text(
            text = value,
            style = monoData(),
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onSurface
        )
    }
}

/** Линии Rx и Tx поверх лёгкой сетки. */
@Composable
private fun TrafficChart(history: List<TrafficPoint>) {
    // Скачивание (rx) -- красным, загрузка (tx) -- синим, как в спарклайне
    // статусной карточки: один язык цветов на обоих графиках.
    val primary = MaterialTheme.colorScheme.error
    val secondary = AzraelInfo
    val grid = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f)

    Canvas(modifier = Modifier.fillMaxSize()) {
        val w = size.width
        val h = size.height
        if (w <= 0f || h <= 0f || history.isEmpty()) return@Canvas

        val peak = history.maxOfOrNull { maxOf(it.rxBitsPerSecond, it.txBitsPerSecond) } ?: 0.0
        if (peak <= 0.0) return@Canvas

        drawLine(grid, Offset(0f, h * 0.25f), Offset(w, h * 0.25f), strokeWidth = 1f)
        drawLine(grid, Offset(0f, h * 0.75f), Offset(w, h * 0.75f), strokeWidth = 1f)

        val last = (history.size - 1).coerceAtLeast(1)

        fun linePath(selector: (TrafficPoint) -> Double): Path {
            val path = Path()
            history.forEachIndexed { i, point ->
                val x = w * (i.toFloat() / last.toFloat())
                val y = h - (selector(point) / peak * h).toFloat()
                if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
            }
            return path
        }

        drawPath(linePath { it.txBitsPerSecond }, secondary, style = Stroke(2f, cap = StrokeCap.Round))
        drawPath(linePath { it.rxBitsPerSecond }, primary, style = Stroke(2.5f, cap = StrokeCap.Round))
    }
}

/**
 * Живая таблица соединений: кто к чему подключён.
 *
 * Строки -- снимок платформы, обновляется своим циклом в AppState.
 * Колонки процесс/порт/адрес: байты по сокету платформенные таблицы
 * не считают, а точные счётчики живут только у ядра туннеля. Фильтр
 * ложится на снимок локально -- опрос из-за ввода не ускоряется.
 */
@Composable
private fun ConnectionsCard(state: AppState) {
    val sample by state.connections.collectAsState()
    var query by rememberSaveable { mutableStateOf("") }
    val filtered = remember(sample.rows, query) { ConnectionsFilter.apply(sample.rows, query) }

    LaunchedEffect(Unit) { state.startConnectionsPolling() }

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(AzraelSpace.lg),
            verticalArrangement = Arrangement.spacedBy(AzraelSpace.sm)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = tr(Str.TRAFFIC_CONNECTIONS),
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text(
                    text = "${filtered.size}",
                    style = monoData(),
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            sample.note?.let { note ->
                Text(
                    text = tr(
                        when (note) {
                            ConnNote.NO_PROC -> Str.TRAFFIC_CONN_NO_PROC
                            ConnNote.NO_TOOL -> Str.TRAFFIC_CONN_NO_TOOL
                        }
                    ),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            ZappTextField(
                label = tr(Str.TRAFFIC_CONN_FILTER),
                value = query,
                onValueChange = { query = it },
                path = "traffic.connections.filter"
            )
            if (filtered.isEmpty()) {
                Text(
                    text = tr(Str.COMMON_NOTHING_FOUND),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            filtered.take(CONNECTION_ROWS_VISIBLE).forEach { row ->
                Text(
                    text = "${row.owner} · ${row.proto}:${row.localPort} → ${row.remote}",
                    style = monoData(),
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.fillMaxWidth()
                )
            }
        }
    }
}

/** Строк в списке: снимок живой, экран -- нет; длинный хвост прячется, счётчик честный. */
private const val CONNECTION_ROWS_VISIBLE = 50
