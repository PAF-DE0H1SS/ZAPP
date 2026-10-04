package xyz.azraellab.zapp.ui.welcome

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import xyz.azraellab.zapp.core.AppState
import xyz.azraellab.zapp.core.Str
import xyz.azraellab.zapp.core.UsageProfile
import xyz.azraellab.zapp.core.UsageTuner
import xyz.azraellab.zapp.core.native.NativeBinaries
import xyz.azraellab.zapp.core.permissions.PermissionState
import xyz.azraellab.zapp.core.update.UpdateState
import xyz.azraellab.zapp.ui.components.Choice
import xyz.azraellab.zapp.ui.components.ComponentColors.ButtonRole
import xyz.azraellab.zapp.ui.components.ZappButton
import xyz.azraellab.zapp.ui.components.ZappButtonRow
import xyz.azraellab.zapp.ui.components.ZappCard
import xyz.azraellab.zapp.ui.components.ZappChoiceRow
import xyz.azraellab.zapp.ui.components.ZappListRow
import xyz.azraellab.zapp.ui.pages.PermissionRow
import xyz.azraellab.zapp.ui.theme.AzraelSpace
import xyz.azraellab.zapp.ui.tr

/**
 * Приветствие при первом запуске -- ровно один раз, до полной настройки.
 *
 * Три шага: знакомство, разрешения, автонастройка. На последнем шаге
 * приложение само обновляет источники, проверяет коннекты и подбирает
 * стратегию -- человек смотрит на плавно заполняющиеся галочки, а не
 * ищет, что нажать. Флаг welcomeDone ставится только по кнопке «Начать»,
 * поэтому показывается экран ровно один раз за жизнь настройки.
 */
@Composable
fun WelcomeScreen(state: AppState) {
    var step by remember { mutableIntStateOf(0) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            // Ограничение ширины: на широком экране лента не растягивается.
            .widthIn(max = 720.dp)
            .verticalScroll(rememberScrollState())
            .padding(AzraelSpace.screenPadding),
        verticalArrangement = Arrangement.spacedBy(AzraelSpace.cardGap),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Spacer(Modifier.height(AzraelSpace.xl))

        // Ключ -- индекс шага: переход между шагами проигрывается
        // анимацией (плавный сдвиг + проявление), а не сменой статики.
        AnimatedContent(
            targetState = step,
            transitionSpec = {
                fadeIn(tween(220)) + slideInVertically(tween(240)) { it / 6 } togetherWith
                    fadeOut(tween(140))
            },
            label = "welcome-step"
        ) { current ->
            // AnimatedContent складывает нескольких детей стопкой (как Box).
            // Каждый шаг обязан быть одним ребёнком, иначе заголовок,
            // карточка и кнопка наезжают друг на друга.
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(AzraelSpace.cardGap),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                when (current) {
                    0 -> WelcomeStep(onNext = { step = 1 })
                    1 -> PermissionsStep(state, onNext = { step = 2 }, onBack = { step = 0 })
                    else -> AutoSetupStep(state)
                }
            }
        }
    }
}

@Composable
private fun WelcomeStep(onNext: () -> Unit) {
    Text(
        text = tr(Str.WELCOME_TITLE),
        style = MaterialTheme.typography.headlineMedium,
        color = MaterialTheme.colorScheme.primary,
        textAlign = TextAlign.Center
    )
    Text(
        text = tr(Str.WELCOME_SUBTITLE),
        style = MaterialTheme.typography.bodyLarge,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        textAlign = TextAlign.Center
    )

    ZappCard(modifier = Modifier.fillMaxWidth()) {
        ZappListRow(title = tr(Str.TAB_TOR), subtitle = tr(Str.WELCOME_HINT_TOR))
        ZappListRow(title = tr(Str.TAB_VPN), subtitle = tr(Str.WELCOME_HINT_VPN))
        ZappListRow(title = tr(Str.TAB_ZAPRET), subtitle = tr(Str.WELCOME_HINT_ZAPRET))
        ZappListRow(title = tr(Str.TAB_GOODBYE_DPI), subtitle = tr(Str.WELCOME_HINT_DPI))
        ZappListRow(title = tr(Str.TAB_GPS), subtitle = tr(Str.WELCOME_HINT_GPS))
        ZappListRow(title = tr(Str.SETTINGS_LOG), subtitle = tr(Str.WELCOME_HINT_LOG))
    }

    ZappButton(
        text = tr(Str.WELCOME_NEXT),
        onClick = { onNext() },
        modifier = Modifier.fillMaxWidth()
    )
}

@Composable
private fun PermissionsStep(state: AppState, onNext: () -> Unit, onBack: () -> Unit) {
    val statuses by state.permissions.statuses.collectAsState()

    // Пока не выдано всё, что можно выдать, дальше не пускаем:
    // незапрошенные разрешения -- самая частая причина «не работает».
    val allGranted = statuses.all {
        it.state == PermissionState.GRANTED || it.state == PermissionState.UNSUPPORTED
    }

    Text(
        text = tr(Str.WELCOME_PERMISSIONS),
        style = MaterialTheme.typography.titleMedium,
        color = MaterialTheme.colorScheme.onSurface,
        textAlign = TextAlign.Center
    )

    ZappCard(modifier = Modifier.fillMaxWidth()) {
        statuses.forEach { status ->
            PermissionRow(
                status = status,
                onAction = { state.permissions.request(status.id) {} }
            )
        }
    }

    if (!allGranted) {
        Text(
            text = tr(Str.WELCOME_GRANT_ALL),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.error,
            textAlign = TextAlign.Center
        )
    }

    ZappButtonRow(modifier = Modifier.fillMaxWidth()) {
        ZappButton(
            text = tr(Str.COMMON_BACK),
            role = ButtonRole.GHOST,
            onClick = onBack,
            modifier = Modifier.weight(1f)
        )
        ZappButton(
            text = tr(Str.WELCOME_NEXT),
            enabled = allGranted,
            onClick = { onNext() },
            modifier = Modifier.weight(1f)
        )
    }
}

/** Состояние шага автонастройки. */
private enum class AutoStep { PENDING, RUNNING, DONE, FAILED }/**
 * Автонастройка: опросник о себе, затем последовательные шаги.
 *
 * Опросник идёт первым и применяется сразу при выборе ответа: класс
 * устройства задаёт производительность, приоритет -- фон и список. Шаги
 * идут последовательно и видно на прогрессе: что уже сделано, что идёт
 * сейчас и что осталось. Таймауты нужны, чтобы сбой сети или медленный
 * репозиторий не оставил экран висеть вечно -- после таймаута шаг
 * помечается неудачным, а настройка продолжается.
 */
@Composable
private fun AutoSetupStep(state: AppState) {
    var sources by remember { mutableStateOf(AutoStep.PENDING) }
    var pings by remember { mutableStateOf(AutoStep.PENDING) }
    var strategy by remember { mutableStateOf(AutoStep.PENDING) }
    var components by remember { mutableStateOf(AutoStep.PENDING) }
    var updates by remember { mutableStateOf(AutoStep.PENDING) }
    var perfStep by remember { mutableStateOf(AutoStep.PENDING) }
    var finished by remember { mutableStateOf(false) }

    // --- Мини-опросник: ответы применяются на месте ---
    var purpose by remember { mutableStateOf(state.config.usage.purpose) }
    var device by remember { mutableStateOf(state.config.usage.device) }
    var priority by remember { mutableStateOf(state.config.usage.priority) }

    fun applyUsage() {
        if (purpose.isBlank() && device.isBlank() && priority.isBlank()) return
        state.mutate { cfg ->
            cfg.copy(usage = UsageProfile(
                purpose = purpose,
                device = device,
                priority = priority,
                applied = true
            )).let { UsageTuner.apply(it, it.usage) }
        }
    }

    LaunchedEffect(Unit) {
        val sourceStatus = state.sourceStatus
        val checkProgress = state.checkProgress
        val strategyRunning = state.strategyRunning

        // --- Источники: обновить и дождаться статуса ---
        sources = AutoStep.RUNNING
        state.refreshSources()
        var waits = 0
        while (sourceStatus.value.values.any { it.state == AppState.SourceState.LOADING } &&
            waits < 300
        ) {
            delay(200); waits++
        }
        sources = if (sourceStatus.value.values.any { it.state == AppState.SourceState.OK }) {
            AutoStep.DONE
        } else {
            AutoStep.FAILED
        }

        // --- Коннекты: проверка живости ---
        pings = AutoStep.RUNNING
        if (state.visibleVpnProfiles().isNotEmpty()) {
            state.checkAllVisible()
            waits = 0
            while (!checkProgress.value.running && waits < 15) { delay(200); waits++ }
            waits = 0
            while (checkProgress.value.running && waits < 600) { delay(200); waits++ }
            pings = if (state.visibleVpnProfiles().any { it.health.alive == true }) {
                AutoStep.DONE
            } else {
                AutoStep.FAILED
            }
        } else {
            pings = AutoStep.DONE
        }

        // --- Стратегия обхода ---
        strategy = AutoStep.RUNNING
        state.autoPickStrategy("zapret")
        waits = 0
        while (!strategyRunning.value && waits < 15) { delay(200); waits++ }
        waits = 0
        while (strategyRunning.value && waits < 150) { delay(200); waits++ }
        strategy = if (state.strategyOutcome.value?.target == "zapret") {
            AutoStep.DONE
        } else {
            AutoStep.FAILED
        }

        // --- Компоненты: нативные бинари распакованы? ---
        components = AutoStep.RUNNING
        components = if (NativeBinaries.installed().isNotEmpty()) AutoStep.DONE else AutoStep.FAILED

        // --- Обновления: быстрая проверка, не блокирует надолго ---
        updates = AutoStep.RUNNING
        state.checkForUpdates()
        waits = 0
        while (state.updateState.value == UpdateState.UNKNOWN ||
            state.updateState.value == UpdateState.CHECKING
        ) {
            if (waits >= 75) break
            delay(200); waits++
        }
        updates = when (state.updateState.value) {
            UpdateState.UNKNOWN -> AutoStep.FAILED
            else -> AutoStep.DONE
        }

        // --- Производительность: применить ответы опросника ---
        perfStep = AutoStep.RUNNING
        applyUsage()
        perfStep = AutoStep.DONE

        finished = true
    }

    Text(
        text = tr(Str.WELCOME_AUTO_TITLE),
        style = MaterialTheme.typography.titleMedium,
        color = MaterialTheme.colorScheme.onSurface,
        textAlign = TextAlign.Center
    )
    Text(
        text = tr(Str.WELCOME_AUTO_HINT),
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        textAlign = TextAlign.Center
    )

    // --- Мини-опросник ---
    Text(
        text = tr(Str.WELCOME_Q_TITLE),
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.primary
    )
    ZappCard(modifier = Modifier.fillMaxWidth()) {
        ZappChoiceRow(
            label = tr(Str.WELCOME_Q_PURPOSE),
            options = listOf(
                Choice(UsageProfile.PURPOSE_VIDEO, tr(Str.Q_PURPOSE_VIDEO)),
                Choice(UsageProfile.PURPOSE_GAMES, tr(Str.Q_PURPOSE_GAMES)),
                Choice(UsageProfile.PURPOSE_CHAT, tr(Str.Q_PURPOSE_CHAT)),
                Choice(UsageProfile.PURPOSE_PRIVACY, tr(Str.Q_PURPOSE_PRIVACY))
            ),
            selected = purpose,
            onSelect = { purpose = it; applyUsage() }
        )
        ZappChoiceRow(
            label = tr(Str.WELCOME_Q_DEVICE),
            options = listOf(
                Choice(UsageProfile.DEVICE_LOW, tr(Str.Q_DEVICE_LOW)),
                Choice(UsageProfile.DEVICE_MID, tr(Str.Q_DEVICE_MID)),
                Choice(UsageProfile.DEVICE_HIGH, tr(Str.Q_DEVICE_HIGH))
            ),
            selected = device,
            onSelect = { device = it; applyUsage() }
        )
        ZappChoiceRow(
            label = tr(Str.WELCOME_Q_PRIORITY),
            options = listOf(
                Choice(UsageProfile.PRIORITY_SPEED, tr(Str.Q_PRIORITY_SPEED)),
                Choice(UsageProfile.PRIORITY_STABILITY, tr(Str.Q_PRIORITY_STABILITY)),
                Choice(UsageProfile.PRIORITY_BATTERY, tr(Str.Q_PRIORITY_BATTERY))
            ),
            selected = priority,
            onSelect = { priority = it; applyUsage() }
        )
    }

    val steps = listOf(sources, pings, strategy, components, updates, perfStep)
    val doneCount = steps.count { it == AutoStep.DONE || it == AutoStep.FAILED }
    val progress by animateFloatAsState(
        targetValue = doneCount / steps.size.toFloat(),
        animationSpec = tween(500, easing = LinearEasing),
        label = "welcome-progress"
    )
    LinearProgressIndicator(
        progress = { progress },
        modifier = Modifier.fillMaxWidth()
    )

    ZappCard(modifier = Modifier.fillMaxWidth()) {
        AutoRow(tr(Str.WELCOME_AUTO_SOURCES), sources)
        AutoRow(tr(Str.WELCOME_AUTO_PING), pings)
        AutoRow(tr(Str.WELCOME_AUTO_STRATEGY), strategy)
        AutoRow(tr(Str.WELCOME_AUTO_COMPONENTS), components)
        AutoRow(tr(Str.WELCOME_AUTO_UPDATES), updates)
        AutoRow(tr(Str.WELCOME_AUTO_PERF), perfStep)
    }

    ZappButtonRow(modifier = Modifier.fillMaxWidth()) {
        ZappButton(
            text = tr(Str.WELCOME_SKIP),
            role = ButtonRole.GHOST,
            enabled = !finished,
            onClick = { finished = true },
            modifier = Modifier.weight(1f)
        )
        ZappButton(
            text = tr(Str.WELCOME_DONE),
            enabled = finished,
            onClick = { state.finishWelcome() },
            modifier = Modifier.weight(1f)
        )
    }
}

/**
 * Шаг автонастройки: подпись, маркер состояния и плавная смена цвета.
 *
 * RUNNING подсвечивается пульсацией -- видно, что шаг не завис, а работает.
 */
@Composable
private fun AutoRow(title: String, step: AutoStep) {
    val marker = when (step) {
        AutoStep.PENDING -> "○"
        AutoStep.RUNNING -> "…"
        AutoStep.DONE -> "✓"
        AutoStep.FAILED -> "✗"
    }
    val target = when (step) {
        AutoStep.DONE -> MaterialTheme.colorScheme.primary
        AutoStep.FAILED -> MaterialTheme.colorScheme.error
        AutoStep.RUNNING -> MaterialTheme.colorScheme.tertiary
        AutoStep.PENDING -> MaterialTheme.colorScheme.onSurfaceVariant
    }
    val color by animateColorAsState(target, tween(300), label = "auto-row-color")

    val infinite = rememberInfiniteTransition(label = "auto-pulse")
    val rawPulse by infinite.animateFloat(
        initialValue = 0.4f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(700), RepeatMode.Reverse),
        label = "auto-pulse-value"
    )
    val pulse = if (step == AutoStep.RUNNING) rawPulse else 1f

    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(AzraelSpace.md),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = marker,
            style = MaterialTheme.typography.titleMedium,
            color = color,
            modifier = Modifier.alpha(pulse)
        )
        Text(
            text = title,
            style = MaterialTheme.typography.bodyLarge,
            color = when (step) {
                AutoStep.PENDING -> MaterialTheme.colorScheme.onSurfaceVariant
                else -> MaterialTheme.colorScheme.onSurface
            },
            modifier = Modifier.weight(1f),
            maxLines = 2
        )
    }
}
