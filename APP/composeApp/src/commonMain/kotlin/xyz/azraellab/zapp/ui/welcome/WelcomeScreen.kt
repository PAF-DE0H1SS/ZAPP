package xyz.azraellab.zapp.ui.welcome

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.animateColorAsState
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
import androidx.compose.foundation.layout.Box
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
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import xyz.azraellab.zapp.core.AppState
import xyz.azraellab.zapp.core.RegionTuner
import xyz.azraellab.zapp.core.Str
import xyz.azraellab.zapp.core.UsageProfile
import xyz.azraellab.zapp.core.UsageTuner
import xyz.azraellab.zapp.core.native.NativeBinaries
import xyz.azraellab.zapp.core.permissions.PermissionState
import xyz.azraellab.zapp.core.update.UpdateState
import xyz.azraellab.zapp.ui.components.Choice
import xyz.azraellab.zapp.ui.components.ComponentColors.ButtonRole
import xyz.azraellab.zapp.ui.components.ComponentColors
import xyz.azraellab.zapp.ui.components.EmptyIllustration
import xyz.azraellab.zapp.ui.components.RegionDropdown
import xyz.azraellab.zapp.ui.components.ZappBanner
import xyz.azraellab.zapp.ui.components.ZappButton
import xyz.azraellab.zapp.ui.components.ZappButtonRow
import xyz.azraellab.zapp.ui.components.ZappCard
import xyz.azraellab.zapp.ui.components.ZappChoiceRow
import xyz.azraellab.zapp.ui.components.ZappListRow
import xyz.azraellab.zapp.ui.pages.PermissionRow
import xyz.azraellab.zapp.ui.theme.AzraelMotion
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
                fadeIn(AzraelMotion.spec()) + slideInVertically(AzraelMotion.spec()) { it / 6 } togetherWith
                    fadeOut(AzraelMotion.spec(AzraelMotion.fast))
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
    // Иллюстрация сверху: первый шаг -- единственное место, где приложение
    // представляет себя; без картинки это два текста в пустоте.
    Box(
        modifier = Modifier.fillMaxWidth(),
        contentAlignment = Alignment.Center
    ) {
        EmptyIllustration(size = 96.dp)
    }
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
        modifier = Modifier.fillMaxWidth(),
        path = "welcome.next"
    )
}

@Composable
private fun PermissionsStep(state: AppState, onNext: () -> Unit, onBack: () -> Unit) {
    val statuses by state.permissions.statuses.collectAsState()

    // Тот же переспрос при входе: шаг можно открыть повторно уже
    // после того, как разрешение выдали мимо приложения.
    LaunchedEffect(Unit) { state.permissions.refresh() }

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
                onAction = { state.permissions.request(status.id) {} },
                path = "welcome.permission.${status.id.name.lowercase()}"
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
            modifier = Modifier.weight(1f),
            path = "welcome.back"
        )
        ZappButton(
            text = tr(Str.WELCOME_NEXT),
            enabled = allGranted,
            onClick = { onNext() },
            modifier = Modifier.weight(1f),
            path = "welcome.next"
        )
    }
}

/** Состояние шага автонастройки. */
private enum class AutoStep { PENDING, RUNNING, DONE, FAILED }

/**
 * Шаг и, для неудачи, причина.
 *
 * [reason] -- ключ переведённой строки, [detail] -- сырой текст ошибки
 * (то, что реально ответила сеть: «http 404», «fetch»): его переводить
 * нечего, и именно он объясняет случай. Раньше шаг падал с ✗ и молчал:
 * было видно, что не вышло, но не почему.
 */
private data class AutoStepState(
    val step: AutoStep = AutoStep.PENDING,
    val reason: Str? = null,
    val detail: String = ""
)

/**
 * Автонастройка: опросник о себе, затем последовательные шаги.
 *
 * Опросник идёт первым и применяется сразу при выборе ответа: класс
 * устройства задаёт производительность, приоритет -- фон и список. Шаги
 * идут последовательно и видно на прогрессе: что уже сделано, что идёт
 * сейчас и что осталось. Таймауты нужны, чтобы сбой сети или медленный
 * репозиторий не оставил экран висеть вечно -- после таймаута шаг
 * помечается неудачным, а настройка продолжается.
 *
 * Пропустить шаг нельзя: приветствие не является пропускаемым просто так.
 * Вместо этого есть два пути к «Начать»: автонастройка доходит до конца
 * (неважно, успешно или с ошибками) или пользователь загружает свой пресет --
 * тогда автонастройка отключается, а настройки берутся из пресета.
 */
@Composable
private fun AutoSetupStep(state: AppState) {
    var sources by remember { mutableStateOf(AutoStepState()) }
    var pings by remember { mutableStateOf(AutoStepState()) }
    var strategy by remember { mutableStateOf(AutoStepState()) }
    var components by remember { mutableStateOf(AutoStepState()) }
    var updates by remember { mutableStateOf(AutoStepState()) }
    var perfStep by remember { mutableStateOf(AutoStepState()) }
    var finished by remember { mutableStateOf(false) }

    // Загруженный пресет выключает автонастройку: настройки уже заданы.
    var presetApplied by remember { mutableStateOf(false) }
    var presetError by remember { mutableStateOf<Str?>(null) }
    val scope = rememberCoroutineScope()

    // --- Мини-опросник: ответы применяются на месте ---
    var purpose by remember { mutableStateOf(state.config.usage.purpose) }
    var device by remember { mutableStateOf(state.config.usage.device) }
    var priority by remember { mutableStateOf(state.config.usage.priority) }
    var region by remember { mutableStateOf(state.config.region) }

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

    fun applyRegion(code: String) {
        region = code
        state.mutate { RegionTuner.apply(it, code) }
    }

    LaunchedEffect(presetApplied) {
        // Пресет уже применил все настройки -- автошаги не нужны.
        if (presetApplied) {
            finished = true
            return@LaunchedEffect
        }
        val sourceStatus = state.sourceStatus
        val checkProgress = state.checkProgress
        val strategyRunning = state.strategyRunning

        // --- Источники: обновить и дождаться статуса ---
        sources = AutoStepState(AutoStep.RUNNING)
        val enabledIds = state.config.vpn.sources.filter { it.enabled }.map { it.id }.toSet()
        fun enabledStatus() = sourceStatus.value.filter { it.key in enabledIds }
        if (enabledIds.isEmpty()) {
            sources = AutoStepState(AutoStep.FAILED, Str.WELCOME_FAIL_NO_SOURCES)
        } else {
            state.refreshSources(enabledIds)
            var waits = 0
            // Пока хоть один источник в IDLE или LOADING -- обновление не
            // закончилось. Раньше ждали только LOADING: refreshSources
            // запускает корутину, первый статус появляется не мгновенно,
            // и опрос успевал выйти до старта -- тогда шаг судил по
            // прошлому запуску и падал по-разному при одинаковых условиях.
            while (enabledStatus().values.any {
                    it.state == AppState.SourceState.IDLE ||
                        it.state == AppState.SourceState.LOADING
                } && waits < 300
            ) {
                delay(200); waits++
            }
            val done = enabledStatus().values
            sources = if (done.any { it.state == AppState.SourceState.OK }) {
                AutoStepState(AutoStep.DONE)
            } else {
                AutoStepState(
                    step = AutoStep.FAILED,
                    reason = Str.WELCOME_FAIL_SOURCES,
                    detail = done.firstNotNullOfOrNull { it.error.ifBlank { null } } ?: ""
                )
            }
        }
        var waits = 0

        // --- Коннекты: проверка живости ---
        pings = AutoStepState(AutoStep.RUNNING)
        if (state.visibleVpnProfiles().isNotEmpty()) {
            state.checkAllVisible()
            waits = 0
            while (!checkProgress.value.running && waits < 15) { delay(200); waits++ }
            waits = 0
            while (checkProgress.value.running && waits < 600) { delay(200); waits++ }
            pings = if (state.visibleVpnProfiles().any { it.health.alive == true }) {
                AutoStepState(AutoStep.DONE)
            } else {
                AutoStepState(AutoStep.FAILED, Str.WELCOME_FAIL_PINGS)
            }
        } else {
            pings = AutoStepState(AutoStep.DONE)
        }

        // --- Стратегия обхода ---
        strategy = AutoStepState(AutoStep.RUNNING)
        state.autoPickStrategy("zapret")
        waits = 0
        while (!strategyRunning.value && waits < 15) { delay(200); waits++ }
        waits = 0
        while (strategyRunning.value && waits < 150) { delay(200); waits++ }
        strategy = if (state.strategyOutcome.value?.target == "zapret") {
            AutoStepState(AutoStep.DONE)
        } else {
            AutoStepState(AutoStep.FAILED, Str.WELCOME_FAIL_STRATEGY)
        }

        // --- Компоненты: нативные бинари распакованы? ---
        components = AutoStepState(AutoStep.RUNNING)
        // Распаковка идёт фоном с самого старта: проверка до её конца
        // давала ✗ в зависимости от того, сколько миллисекунд ушло на
        // предыдущие шаги. Ждём конца и только потом спрашиваем.
        NativeBinaries.awaitReady()
        components = if (NativeBinaries.installed().isNotEmpty()) {
            AutoStepState(AutoStep.DONE)
        } else {
            AutoStepState(AutoStep.FAILED, Str.WELCOME_FAIL_COMPONENTS)
        }

        // --- Обновления: быстрая проверка, не блокирует надолго ---
        updates = AutoStepState(AutoStep.RUNNING)
        state.checkForUpdates()
        waits = 0
        while (state.updateState.value == UpdateState.UNKNOWN ||
            state.updateState.value == UpdateState.CHECKING
        ) {
            if (waits >= 75) break
            delay(200); waits++
        }
        // Провал и таймаут (UNKNOWN) и явная ошибка сети (ERROR) --
        // неудача; раньше ERROR считался выполненным шагом.
        updates = when (state.updateState.value) {
            UpdateState.CURRENT, UpdateState.AVAILABLE -> AutoStepState(AutoStep.DONE)
            else -> AutoStepState(AutoStep.FAILED, Str.WELCOME_FAIL_UPDATES)
        }

        // --- Производительность: применить ответы опросника ---
        perfStep = AutoStepState(AutoStep.RUNNING)
        applyUsage()
        perfStep = AutoStepState(AutoStep.DONE)

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
        // Страна -- фактор автонастройки: от неё скрываются протоколы.
        Text(
            text = tr(Str.WELCOME_Q_REGION),
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Text(
            text = tr(Str.WELCOME_Q_REGION_HINT),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        RegionDropdown(
            selected = region,
            onSelect = ::applyRegion,
            path = "welcome.q_region"
        )
        ZappChoiceRow(
            label = tr(Str.WELCOME_Q_PURPOSE),
            options = listOf(
                Choice(UsageProfile.PURPOSE_VIDEO, tr(Str.Q_PURPOSE_VIDEO)),
                Choice(UsageProfile.PURPOSE_GAMES, tr(Str.Q_PURPOSE_GAMES)),
                Choice(UsageProfile.PURPOSE_CHAT, tr(Str.Q_PURPOSE_CHAT)),
                Choice(UsageProfile.PURPOSE_PRIVACY, tr(Str.Q_PURPOSE_PRIVACY))
            ),
            selected = purpose,
            onSelect = { purpose = it; applyUsage() },
            path = "welcome.q_purpose"
        )
        ZappChoiceRow(
            label = tr(Str.WELCOME_Q_DEVICE),
            options = listOf(
                Choice(UsageProfile.DEVICE_LOW, tr(Str.Q_DEVICE_LOW)),
                Choice(UsageProfile.DEVICE_MID, tr(Str.Q_DEVICE_MID)),
                Choice(UsageProfile.DEVICE_HIGH, tr(Str.Q_DEVICE_HIGH))
            ),
            selected = device,
            onSelect = { device = it; applyUsage() },
            path = "welcome.q_device"
        )
        ZappChoiceRow(
            label = tr(Str.WELCOME_Q_PRIORITY),
            options = listOf(
                Choice(UsageProfile.PRIORITY_SPEED, tr(Str.Q_PRIORITY_SPEED)),
                Choice(UsageProfile.PRIORITY_STABILITY, tr(Str.Q_PRIORITY_STABILITY)),
                Choice(UsageProfile.PRIORITY_BATTERY, tr(Str.Q_PRIORITY_BATTERY))
            ),
            selected = priority,
            onSelect = { priority = it; applyUsage() },
            path = "welcome.q_priority"
        )
    }

    // --- Пресет вместо автонастройки ---
    if (presetApplied) {
        ZappBanner(
            title = tr(Str.WELCOME_AUTO_OFF),
            state = ComponentColors.State.ACTIVE
        )
    } else {
        ZappCard(modifier = Modifier.fillMaxWidth()) {
            Text(
                text = tr(Str.WELCOME_LOAD_PRESET_HINT),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            ZappButton(
                text = tr(Str.WELCOME_LOAD_PRESET),
                role = ButtonRole.SECONDARY,
                enabled = !finished,
                onClick = {
                    scope.launch {
                        val result = state.importPreset()
                        if (result.ok) {
                            presetApplied = true
                            finished = true
                        } else {
                            presetError = result.key()
                        }
                    }
                },
                modifier = Modifier.fillMaxWidth(),
                path = "welcome.load_preset"
            )
        }
    }
    presetError?.let { key ->
        Text(
            text = tr(key),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.error,
            textAlign = TextAlign.Center
        )
    }

    val steps = listOf(sources, pings, strategy, components, updates, perfStep)
    val doneCount = if (presetApplied) steps.size
    else steps.count { it.step == AutoStep.DONE || it.step == AutoStep.FAILED }
    val progress by animateFloatAsState(
        targetValue = doneCount / steps.size.toFloat(),
        animationSpec = AzraelMotion.spec(AzraelMotion.state),
        label = "welcome-progress"
    )
    LinearProgressIndicator(
        progress = { progress },
        modifier = Modifier.fillMaxWidth()
    )

    ZappCard(modifier = Modifier.fillMaxWidth()) {
        val presetStep = AutoStepState(AutoStep.DONE)
        AutoRow(tr(Str.WELCOME_AUTO_SOURCES), if (presetApplied) presetStep else sources)
        AutoRow(tr(Str.WELCOME_AUTO_PING), if (presetApplied) presetStep else pings)
        AutoRow(tr(Str.WELCOME_AUTO_STRATEGY), if (presetApplied) presetStep else strategy)
        AutoRow(tr(Str.WELCOME_AUTO_COMPONENTS), if (presetApplied) presetStep else components)
        AutoRow(tr(Str.WELCOME_AUTO_UPDATES), if (presetApplied) presetStep else updates)
        AutoRow(tr(Str.WELCOME_AUTO_PERF), if (presetApplied) presetStep else perfStep)
    }

    ZappButton(
        text = tr(Str.WELCOME_DONE),
        enabled = finished,
        onClick = { state.finishWelcome() },
        modifier = Modifier.fillMaxWidth(),
        path = "welcome.done"
    )
}

/**
 * Шаг автонастройки: подпись, маркер состояния и плавная смена цвета.
 *
 * RUNNING подсвечивается пульсацией -- видно, что шаг не завис, а работает.
 */
@Composable
private fun AutoRow(title: String, state: AutoStepState) {
    val step = state.step
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
    val color by animateColorAsState(target, AzraelMotion.spec(AzraelMotion.state), label = "auto-row-color")

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
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyLarge,
                color = when (step) {
                    AutoStep.PENDING -> MaterialTheme.colorScheme.onSurfaceVariant
                    else -> MaterialTheme.colorScheme.onSurface
                },
                maxLines = 2
            )
            // Причина -- прямо под строкой: ✗ без объяснения заставляет
            // гадать, что именно сломалось, и в трёх проходах «падало»
            // разное. detail -- сырой ответ источника.
            if (step == AutoStep.FAILED && (state.reason != null || state.detail.isNotBlank())) {
                Text(
                    text = listOf(state.reason?.let { tr(it) }.orEmpty(), state.detail)
                        .filter { it.isNotBlank() }
                        .joinToString(" · "),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error
                )
            }
        }
    }
}
