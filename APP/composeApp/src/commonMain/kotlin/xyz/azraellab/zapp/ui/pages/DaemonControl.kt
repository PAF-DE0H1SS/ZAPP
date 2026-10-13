package xyz.azraellab.zapp.ui.pages

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import kotlinx.coroutines.delay
import xyz.azraellab.zapp.core.AppState
import xyz.azraellab.zapp.core.Str
import xyz.azraellab.zapp.core.daemon.DaemonEngine
import xyz.azraellab.zapp.core.daemon.DaemonState
import xyz.azraellab.zapp.core.log.isError
import xyz.azraellab.zapp.core.log.stripAnsi
import xyz.azraellab.zapp.core.probe.ProbeProgress
import xyz.azraellab.zapp.core.probe.StrategyProbeCoordinator
import xyz.azraellab.zapp.ui.components.ComponentColors.ButtonRole
import xyz.azraellab.zapp.ui.components.ZappButton
import xyz.azraellab.zapp.ui.components.ZappCard
import xyz.azraellab.zapp.ui.components.ZappExpandableCard
import xyz.azraellab.zapp.ui.rememberLang
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
    onStop: () -> Unit,
    path: String = "daemon"
) {
    val state by engine.state.collectAsState()
    val log by engine.log.collectAsState()

    if (!engine.supported) return

    // Причина сбоя -- прямо в карточке, а не только в журнале ниже:
    // «Ошибка» без строки под ним ничего не объясняет. Строка --
    // диагностика одной строкой: время, компонент и причина из журнала
    // (выбор строки общий с журналом, core.log).
    val reason = if (state == DaemonState.ERROR) {
        log.diagnosisReason(engine.id.defaultBinary)
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
                ZappButton(
                    text = tr(Str.COMMON_STOP),
                    role = ButtonRole.DANGER,
                    onClick = onStop,
                    path = "$path.stop"
                )
            } else {
                ZappButton(
                    text = tr(Str.COMMON_START),
                    onClick = onStart,
                    path = "$path.start"
                )
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
fun DaemonLogCard(engine: DaemonEngine, path: String = "daemon.log.expand") {
    val log by engine.log.collectAsState()
    // rendered() инлайновый, поэтому вызовы tr здесь допустимы и выполняются
    // в момент композиции, а не при сборке списка.
    val lines = log.map { it.rendered() to it.isError() }

    ZappExpandableCard(
        title = tr(Str.ENGINE_LOG),
        badge = if (lines.isEmpty()) null else lines.size.toString(),
        path = path
    ) {
        if (lines.isEmpty()) {
            Text(
                text = tr(Str.ENGINE_LOG_EMPTY),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        } else {
            Column(verticalArrangement = Arrangement.spacedBy(AzraelSpace.xs)) {
                lines.takeLast(JOURNAL_VISIBLE).forEach { (line, isError) ->
                    // Ошибка -- цветом: причину надо видеть, не читая всё.
                    Text(
                        text = stripAnsi(line),
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
 * Автоподбор стратегии: кнопка, ход перебора и применённый победитель.
 *
 * Общая карточка для zapret и goodbyedpi. Пока идёт проба, видно, какой
 * именно кандидат сейчас обрабатывает трафик (координатор -- общий на все
 * страницы), а после -- кто победил и насколько хорошо. Прогресс вместо
 * списка шагов не случайно: шаги описывали гипотезы о сети, а здесь
 * проверяются реальные стратегии, и важен именно перебор.
 */
@Composable
fun StrategyProbeCard(state: AppState, target: String) {
    val running by state.strategyRunning.collectAsState()
    val outcome by state.strategyOutcome.collectAsState()
    val progress by StrategyProbeCoordinator.progress.collectAsState()
    val current = outcome?.takeIf { it.target == target }
    val lang = rememberLang()
    val scheme = MaterialTheme.colorScheme

    ZappCard {
        ZappButton(
            text = if (running) tr(Str.VPN_CHECKING) else tr(Str.STRATEGY_PROBE),
            enabled = !running,
            onClick = { state.autoPickStrategy(target) },
            modifier = Modifier.fillMaxWidth(),
            path = "$target.probe"
        )

        // Ход перебора: «Кандидат 3/8: Разрез 1×10 мс». Без label до первого
        // тика строка выглядит пустой -- тогда просто «Проверка».
        if (running) {
            val step = progress
            Text(
                text = if (step == null || step.candidate.id.isEmpty()) {
                    tr(Str.VPN_CHECKING)
                } else {
                    "${tr(Str.STRATEGY_CANDIDATE)} ${step.index}/${step.total}: " +
                        step.candidate.label(lang)
                },
                style = MaterialTheme.typography.bodySmall,
                color = scheme.onSurfaceVariant,
                modifier = Modifier.fillMaxWidth()
            )
            if (step != null && step.total > 0) {
                // Доля завершённых, а не позиция текущего: бар на последнем
                // кандидате не должен выглядеть законченным, пока идёт проба.
                val done = (step.index - 1).coerceIn(0, step.total)
                LinearProgressIndicator(
                    progress = { done / step.total.toFloat() },
                    modifier = Modifier.fillMaxWidth()
                )
            }
            val eta = rememberEta(step, running)
            if (eta != null) {
                Text(
                    text = "${tr(Str.STRATEGY_ETA)} ${eta.asClock()}",
                    style = MaterialTheme.typography.bodySmall,
                    color = scheme.onSurfaceVariant,
                    modifier = Modifier.fillMaxWidth()
                )
            }
        }

        if (current != null) {
            val probe = current.probe
            Column(verticalArrangement = Arrangement.spacedBy(AzraelSpace.xs)) {
                Text(
                    text = tr(if (probe.noData) Str.STRATEGY_NETWORK_FAIL else Str.STRATEGY_APPLIED),
                    style = MaterialTheme.typography.titleSmall,
                    color = if (probe.noData) scheme.error else scheme.primary
                )
                if (!probe.noData) {
                    Text(
                        text = "${probe.candidate.label(lang)} -- ${probe.score?.summary() ?: "-"}",
                        style = MaterialTheme.typography.bodySmall,
                        color = scheme.onSurfaceVariant
                    )
                }
                ZappButton(
                    text = tr(Str.COMMON_CLOSE),
                    role = ButtonRole.GHOST,
                    onClick = { state.clearStrategyOutcome() },
                    modifier = Modifier.fillMaxWidth(),
                    path = "$target.probe_close"
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

/**
 * Оставшееся время перебора -- тикает раз в секунду, пока идёт проба.
 *
 * Отсчёт живёт не здесь, а в [StrategyProbeCoordinator] (он общий на все
 * страницы и переживает закрытие карточки), здесь только периодический
 * пересчёт по нему. null -- пробы нет или завершённых кандидатов ещё нет,
 * оценивать не по чему.
 */
@Composable
private fun rememberEta(step: ProbeProgress?, running: Boolean): Long? {
    if (!running || step == null || step.total <= 0) return null
    var tick by remember(step) { mutableStateOf(0) }
    LaunchedEffect(step.index, running) {
        while (true) {
            delay(1000)
            tick++
        }
    }
    return remember(step.index, step.total, tick) {
        StrategyProbeCoordinator.etaSec(StrategyProbeCoordinator.elapsedSec(), step.index, step.total)
    }
}

/** Секунды -> «м:сс»; часы не нужны: перебор кандидатов -- минутное дело. */
private fun Long.asClock(): String {
    val total = coerceAtLeast(0L)
    val m = total / 60
    val s = total % 60
    return "%d:%02d".format(m, s)
}
