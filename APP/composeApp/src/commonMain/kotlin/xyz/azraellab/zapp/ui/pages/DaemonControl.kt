package xyz.azraellab.zapp.ui.pages

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import xyz.azraellab.zapp.core.AppState
import xyz.azraellab.zapp.core.AutoStrategy
import xyz.azraellab.zapp.core.Str
import xyz.azraellab.zapp.core.daemon.DaemonEngine
import xyz.azraellab.zapp.core.daemon.DaemonState
import xyz.azraellab.zapp.ui.components.ComponentColors.ButtonRole
import xyz.azraellab.zapp.ui.components.ZappButton
import xyz.azraellab.zapp.ui.components.ZappCard
import xyz.azraellab.zapp.ui.components.ZappExpandableCard
import xyz.azraellab.zapp.ui.theme.AzraelSpace
import xyz.azraellab.zapp.ui.tr

/**
 * Карточка управления демоном: состояние слева, кнопка справа.
 *
 * Один и тот же вид у zapret и goodbyedpi, потому что пользователю важно
 * «работает или нет», а не то, какой именно процесс живёт. Причина сбоя
 * не влезает в строку состояния -- для этого ниже есть журнал.
 */
@Composable
fun DaemonControlCard(
    engine: DaemonEngine,
    onStart: () -> Unit,
    onStop: () -> Unit
) {
    val state by engine.state.collectAsState()
    val log by engine.log.collectAsState()

    if (!engine.supported) return

    // Причина сбоя -- прямо в карточке, а не только в журнале ниже.
    val reason = if (state == DaemonState.ERROR) {
        log.lastOrNull()?.let { if (it.text != null) tr(it.text) + " ${it.arg}" else it.raw }
    } else {
        null
    }

    ZappCard {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(AzraelSpace.md),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = tr(state.labelKey()),
                    style = MaterialTheme.typography.titleMedium,
                    color = when (state) {
                        DaemonState.RUNNING -> MaterialTheme.colorScheme.primary
                        DaemonState.ERROR -> MaterialTheme.colorScheme.error
                        DaemonState.STARTING -> MaterialTheme.colorScheme.tertiary
                        DaemonState.STOPPED -> MaterialTheme.colorScheme.onSurface
                    }
                )
                if (reason != null) {
                    Text(
                        text = reason,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error
                    )
                }
            }
            // Работающий демон останавливается; всё остальное -- запускается.
            // В состоянии ERROR кнопка «Старт» перезапускает процесс, а не
            // показывает прошлую ошибку повторно.
            if (state == DaemonState.RUNNING || state == DaemonState.STARTING) {
                ZappButton(text = tr(Str.COMMON_STOP), role = ButtonRole.DANGER, onClick = onStop)
            } else {
                ZappButton(text = tr(Str.COMMON_START), onClick = onStart)
            }
        }
    }
}

/**
 * Журнал демона: события приложения и сырой вывод процесса в одном списке.
 *
 * Переводятся только собственные события; вывод демона остаётся как есть --
 * это текст инструмента, и подделывать его переводом нельзя.
 *
 * Секция свёрнута: журнал читают, когда что-то не работает, а не каждый
 * день, и развёрнутая лента строк съедает половину главного экрана.
 */
@Composable
fun DaemonLogCard(engine: DaemonEngine) {
    val log by engine.log.collectAsState()
    // map инлайновый, поэтому вызовы tr здесь допустимы и выполняются
    // в момент композиции, а не при сборке списка.
    val lines = log.map { event ->
        if (event.text != null) {
            tr(event.text) + if (event.arg.isEmpty()) "" else " ${event.arg}"
        } else {
            event.raw
        }
    }

    ZappExpandableCard(
        title = tr(Str.ENGINE_LOG),
        badge = if (lines.isEmpty()) null else lines.size.toString()
    ) {
        if (lines.isEmpty()) {
            Text(
                text = tr(Str.ENGINE_LOG_EMPTY),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        } else {
            Column(verticalArrangement = Arrangement.spacedBy(AzraelSpace.xs)) {
                lines.takeLast(JOURNAL_VISIBLE).forEach { line ->
                    // Ошибка -- цветом: причину надо видеть, не читая всё.
                    val isError = line.startsWith("error") ||
                        line.contains("failed", ignoreCase = true) ||
                        line.contains("panic", ignoreCase = true)
                    Text(
                        text = line,
                        style = MaterialTheme.typography.bodySmall,
                        color = if (isError) {
                            MaterialTheme.colorScheme.error
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        }
                    )
                }
            }
        }
    }
}

/**
 * Автоподбор стратегии: кнопка, шаги проверки и применённое решение.
 *
 * Общая карточка для zapret и goodbyedpi: проверяется одна и та же сеть,
 * отличается набор шагов и то, кому решение применяется. Шаги видны и до
 * запуска, и после -- иначе «стратегия подобрана» превращается в магию
 * без единого проверяемого утверждения.
 */
@Composable
fun StrategyProbeCard(state: AppState, target: String) {
    val running by state.strategyRunning.collectAsState()
    val outcome by state.strategyOutcome.collectAsState()
    val current = outcome?.takeIf { it.target == target }
    val steps = if (target == "zapret") AutoStrategy.stepsForZapret()
    else AutoStrategy.stepsForGoodbyeDpi()
    val scheme = MaterialTheme.colorScheme

    ZappCard {
        ZappButton(
            text = if (running) tr(Str.VPN_CHECKING) else tr(Str.STRATEGY_PROBE),
            enabled = !running,
            onClick = { state.autoPickStrategy(target) },
            modifier = Modifier.fillMaxWidth()
        )

        // Шаги видны только во время проверки и после неё: постоянный список
        // «? ? ?» до нажатия -- это пустая стена, а не информация.
        if (running || current != null) {
            steps.forEach { step ->
                val probeStep = current?.report?.steps?.firstOrNull { it.id == step.id }
                val result = probeStep?.result
                val symbol = when (result) {
                    AutoStrategy.ProbeResult.Pass -> "✓"
                    AutoStrategy.ProbeResult.Blocked -> "✗"
                    else -> "?"
                }
                val color = when (result) {
                    AutoStrategy.ProbeResult.Pass -> scheme.primary
                    AutoStrategy.ProbeResult.Blocked -> scheme.error
                    else -> scheme.onSurfaceVariant
                }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(AzraelSpace.md),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(text = symbol, style = MaterialTheme.typography.bodyLarge, color = color)
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = step.title,
                            style = MaterialTheme.typography.bodyMedium,
                            color = scheme.onSurface
                        )
                        if (probeStep?.detail != null && probeStep.detail.isNotEmpty()) {
                            Text(
                                text = probeStep.detail,
                                style = MaterialTheme.typography.bodySmall,
                                color = scheme.onSurfaceVariant
                            )
                        }
                    }
                }
            }
        }

        if (current != null) {
            val noData = current.report.steps.all {
                it.result == AutoStrategy.ProbeResult.Unknown
            }
            Column(verticalArrangement = Arrangement.spacedBy(AzraelSpace.xs)) {
                Text(
                    text = tr(if (noData) Str.STRATEGY_NETWORK_FAIL else Str.STRATEGY_APPLIED),
                    style = MaterialTheme.typography.titleSmall,
                    color = if (noData) scheme.error else scheme.primary
                )
                Text(
                    text = "${current.decision.strategy} -- ${current.decision.reason}",
                    style = MaterialTheme.typography.bodySmall,
                    color = scheme.onSurfaceVariant
                )
                ZappButton(
                    text = tr(Str.COMMON_CLOSE),
                    role = ButtonRole.GHOST,
                    onClick = { state.clearStrategyOutcome() },
                    modifier = Modifier.fillMaxWidth()
                )
            }
        }
    }
}

/** Состояние демона -- ключ строки для перевода. */
private fun DaemonState.labelKey(): Str = when (this) {
    DaemonState.STOPPED -> Str.DAEMON_STATE_STOPPED
    DaemonState.STARTING -> Str.DAEMON_STATE_STARTING
    DaemonState.RUNNING -> Str.DAEMON_STATE_RUNNING
    DaemonState.ERROR -> Str.DAEMON_STATE_ERROR
}

private const val JOURNAL_VISIBLE = 12
