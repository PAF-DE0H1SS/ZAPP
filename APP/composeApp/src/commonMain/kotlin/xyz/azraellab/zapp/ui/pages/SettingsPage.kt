package xyz.azraellab.zapp.ui.pages

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import xyz.azraellab.zapp.core.AppConfig
import xyz.azraellab.zapp.core.AppLang
import xyz.azraellab.zapp.core.AppState
import xyz.azraellab.zapp.core.Docs
import xyz.azraellab.zapp.core.GoodbyeDpiMode
import xyz.azraellab.zapp.core.Str
import xyz.azraellab.zapp.core.ZapretFamily
import xyz.azraellab.zapp.core.ZapretMode
import xyz.azraellab.zapp.core.ZapretStrategy
import xyz.azraellab.zapp.core.log.AppLog
import xyz.azraellab.zapp.core.native.NativeBinaries
import xyz.azraellab.zapp.core.permissions.PermissionId
import xyz.azraellab.zapp.core.permissions.PermissionState
import xyz.azraellab.zapp.core.permissions.PermissionStatus
import xyz.azraellab.zapp.core.update.UpdateState
import xyz.azraellab.zapp.ui.AppLangState
import xyz.azraellab.zapp.ui.components.ComponentColors.ButtonRole
import xyz.azraellab.zapp.ui.components.ComponentColors
import xyz.azraellab.zapp.ui.components.Choice
import xyz.azraellab.zapp.ui.components.ZappBanner
import xyz.azraellab.zapp.ui.components.ZappButton
import xyz.azraellab.zapp.ui.components.ZappButtonRow
import xyz.azraellab.zapp.ui.components.ZappCard
import xyz.azraellab.zapp.ui.components.ZappChoiceRow
import xyz.azraellab.zapp.ui.components.ZappListRow
import xyz.azraellab.zapp.ui.components.ZappNumberField
import xyz.azraellab.zapp.ui.components.ZappSectionTitle
import xyz.azraellab.zapp.ui.components.ZappSettingRow
import xyz.azraellab.zapp.ui.components.ZappTextField
import xyz.azraellab.zapp.ui.nav.ZappBackHandler
import xyz.azraellab.zapp.ui.tr

/**
 * Страница настроек: категории вместо ленты.
 *
 * Корень -- список категорий, каждая открывает своё окно с кнопкой «Назад».
 * Лента из всех полей подряд оказалась непроходимой на телефоне: язык,
 * пресеты, clash-API и компоненты вперемешку невозможно сканировать глазами.
 */
@Composable
fun SettingsPage(state: AppState) {
    // null = корень со списком категорий; иначе -- имя открытого окна.
    var section by rememberSaveable { mutableStateOf<String?>(null) }

    // Системная кнопка «назад» закрывает открытое подокно, а не всё
    // приложение: из окна категории возвращаемся в список категорий.
    ZappBackHandler(enabled = section != null) { section = null }

    when (section) {
        null -> SettingsRoot(
            state = state,
            onOpen = { section = it }
        )

        "general" -> SubPage(Str.SETTINGS_SECTION_GENERAL, back = { section = null }) {
            GeneralSection(state)
        }

        "permissions" -> SubPage(Str.SETTINGS_PERMISSIONS, back = { section = null }) {
            PermissionsSection(state)
        }

        "device" -> SubPage(Str.SETTINGS_DEVICE, back = { section = null }) {
            DeviceSection(state)
        }

        "updates" -> SubPage(Str.SETTINGS_UPDATES, back = { section = null }) {
            UpdatesSection(state)
        }

        "traffic" -> SubPage(Str.TAB_TRAFFIC, back = { section = null }) {
            TrafficSection(state)
        }

        "presets" -> SubPage(Str.SETTINGS_PRESETS, back = { section = null }) {
            PresetsSection(state)
        }

        "perf" -> SubPage(Str.SETTINGS_PERF, back = { section = null }) {
            PerfSection(state)
        }

        "vpn" -> SubPage(Str.TAB_VPN, back = { section = null }) {
            VpnExtrasSection(state)
        }

        "tor" -> SubPage(Str.TAB_TOR, back = { section = null }) {
            TorSection(state)
        }

        "zapret" -> SubPage(Str.TAB_ZAPRET, back = { section = null }) {
            ZapretSection(state)
        }

        "dpi" -> SubPage(Str.TAB_GOODBYE_DPI, back = { section = null }) {
            GoodbyeDpiSection(state)
        }

        "components" -> SubPage(Str.COMPONENTS, back = { section = null }) {
            ComponentsSection(state)
        }

        "log" -> SubPage(Str.SETTINGS_LOG, back = { section = null }) {
            LogSection(state)
        }

        "docs" -> SubPage(Str.SETTINGS_DOCS, back = { section = null }) {
            DocsSection()
        }

        "about" -> SubPage(Str.SETTINGS_ABOUT, back = { section = null }) {
            AboutSection(state)
        }
    }
}

// --- Корень: список категорий ---

@Composable
private fun SettingsRoot(state: AppState, onOpen: (String) -> Unit) {
    PageScaffold(title = tr(Str.TAB_SETTINGS)) {
        ZappCard {
            CategoryRow(tr(Str.SETTINGS_SECTION_GENERAL)) { onOpen("general") }
            CategoryRow(tr(Str.SETTINGS_PERMISSIONS)) { onOpen("permissions") }
            CategoryRow(tr(Str.SETTINGS_DEVICE)) { onOpen("device") }
            CategoryRow(tr(Str.SETTINGS_UPDATES)) { onOpen("updates") }
            CategoryRow(tr(Str.TAB_TRAFFIC)) { onOpen("traffic") }
            CategoryRow(tr(Str.SETTINGS_PRESETS)) { onOpen("presets") }
            CategoryRow(tr(Str.SETTINGS_PERF)) { onOpen("perf") }
            CategoryRow(tr(Str.TAB_VPN)) { onOpen("vpn") }
            CategoryRow(tr(Str.TAB_TOR)) { onOpen("tor") }
            if (NativeBinaries.installed().isNotEmpty()) {
                CategoryRow(tr(Str.TAB_ZAPRET)) { onOpen("zapret") }
                CategoryRow(tr(Str.TAB_GOODBYE_DPI)) { onOpen("dpi") }
            }
            if (NativeBinaries.installed().isNotEmpty()) {
                CategoryRow(tr(Str.COMPONENTS)) { onOpen("components") }
            }
            CategoryRow(tr(Str.SETTINGS_LOG)) { onOpen("log") }
            CategoryRow(tr(Str.SETTINGS_DOCS)) { onOpen("docs") }
            CategoryRow(tr(Str.SETTINGS_ABOUT)) { onOpen("about") }
        }
    }
}

/** Строка категории: нажимается целиком, хвост -- стрелка «открыть». */
@Composable
private fun CategoryRow(title: String, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = MaterialTheme.typography.bodyLarge.fontSize.value.dp / 2),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurface
        )
        Text(
            text = ">",
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

/** Общая обёртка подокна: заголовок с кнопкой «Назад» и содержимое. */
@Composable
private fun SubPage(title: Str, back: () -> Unit, content: @Composable () -> Unit) {
    PageScaffold(title = tr(title)) {
        ZappButton(
            text = tr(Str.COMMON_BACK),
            role = ButtonRole.GHOST,
            onClick = back
        )
        content()
    }
}

// --- Категории ---

@Composable
private fun GeneralSection(state: AppState) {
    PageGroup(tr(Str.SETTINGS_LANGUAGE)) {
        ZappCard {
            ZappChoiceRow(
                label = tr(Str.SETTINGS_LANGUAGE),
                options = AppLang.entries.map { Choice(it.code, labelOf(it)) },
                selected = AppLangState.current.code,
                onSelect = { code -> AppLangState.set(AppLang.of(code)) }
            )
        }
    }
    PageGroup(tr(Str.SETTINGS_REPLAY_WELCOME)) {
        ZappCard {
            ZappButton(
                text = tr(Str.SETTINGS_REPLAY_WELCOME),
                role = ButtonRole.SECONDARY,
                onClick = {
                    state.mutate { it.copy(welcomeDone = false) }
                    AppLog.log("app", "welcome tour replay requested")
                },
                modifier = Modifier.fillMaxWidth()
            )
        }
    }
}

/**
 * Производительность: фон и размер страницы списка.
 *
 * Отдельная категория, а не в «Основном»: это настройки под слабое
 * железо, к языку и приветствию они отношения не имеют. Значения
 * ограничены [PerfConfig.sanitized], поэтому мусор из файла не попадёт
 * в цикл отрисовки.
 */
@Composable
private fun PerfSection(state: AppState) {
    val perf = state.config.perf.sanitized()
    PageGroup(tr(Str.SETTINGS_PERF)) {
        ZappCard {
            ZappSettingRow(
                title = tr(Str.PERF_BG_ANIM),
                checked = perf.animatedBackground,
                onCheckedChange = { on ->
                    state.mutate { it.copy(perf = it.perf.copy(animatedBackground = on).sanitized()) }
                }
            )
            ZappChoiceRow(
                label = tr(Str.PERF_BG_FPS),
                options = listOf(15, 30, 60).map { Choice(it.toString(), "$it fps") },
                selected = perf.backgroundFps.toString(),
                onSelect = { code ->
                    state.mutate {
                        it.copy(perf = it.perf.copy(backgroundFps = code.toInt()).sanitized())
                    }
                }
            )
            ZappChoiceRow(
                label = tr(Str.PERF_LIST_PAGE),
                options = listOf(15, 30, 60, 120).map { Choice(it.toString(), it.toString()) },
                selected = perf.listPageSize.toString(),
                onSelect = { code ->
                    state.mutate {
                        it.copy(perf = it.perf.copy(listPageSize = code.toInt()).sanitized())
                    }
                }
            )
            Text(
                text = tr(Str.PERF_LIST_PAGE_HINT),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun PermissionsSection(state: AppState) {
    val statuses by state.permissions.statuses.collectAsState()
    PageGroup(tr(Str.SETTINGS_PERMISSIONS)) {
        ZappCard {
            if (statuses.isEmpty()) {
                Text(
                    text = tr(Str.PROBE_UNKNOWN),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            } else {
                statuses.forEach { status ->
                    PermissionRow(
                        status = status,
                        onAction = { state.permissions.request(status.id) {} }
                    )
                }
            }
        }
    }
}

@Composable
private fun DeviceSection(state: AppState) {
    val probe by state.probe.collectAsState()
    PageGroup(tr(Str.SETTINGS_DEVICE)) {
        ZappCard {
            InfoRow(tr(Str.PROBE_MODEL), probe?.model.orDash())
            InfoRow(tr(Str.PROBE_SYSTEM), probe?.system.orDash())
            InfoRow(tr(Str.PROBE_KERNEL), probe?.kernel.orDash())
            InfoRow(tr(Str.PROBE_API), probe?.apiLevel?.takeIf { it > 0 }?.toString().orDash())
            InfoRow(tr(Str.PROBE_ROOT), tr((probe?.root
                ?: xyz.azraellab.zapp.core.probe.Probe.UNKNOWN).label))
            InfoRow(tr(Str.PROBE_MAGISK), tr((probe?.magisk
                ?: xyz.azraellab.zapp.core.probe.Probe.UNKNOWN).label))
            InfoRow(tr(Str.PROBE_VPN), tr((probe?.vpnTunnel
                ?: xyz.azraellab.zapp.core.probe.Probe.UNKNOWN).label))
        }
    }
}

@Composable
private fun UpdatesSection(state: AppState) {
    val updateState by state.updateState.collectAsState()
    PageGroup(tr(Str.SETTINGS_UPDATES)) {
        ZappCard {
            InfoRow(tr(Str.ABOUT_VERSION), state.appVersion)
            InfoRow(tr(Str.UPD_CHECK), tr(updateState.labelKey()))
            ZappButtonRow(modifier = Modifier.fillMaxWidth()) {
                ZappButton(
                    text = tr(Str.UPD_CHECK),
                    role = ButtonRole.SECONDARY,
                    enabled = updateState != UpdateState.CHECKING,
                    onClick = { state.checkForUpdates() },
                    modifier = Modifier.weight(1f)
                )
                if (updateState == UpdateState.AVAILABLE) {
                    ZappButton(
                        text = tr(Str.UPD_OPEN),
                        onClick = { state.openReleasePage() },
                        modifier = Modifier.weight(1f)
                    )
                }
            }
        }
    }
}

@Composable
private fun TrafficSection(state: AppState) {
    PageGroup(tr(Str.TAB_TRAFFIC)) {
        ZappCard {
            ZappSettingRow(
                title = tr(Str.TRAFFIC_PER_APP),
                checked = state.config.traffic.perApp,
                onCheckedChange = { on ->
                    state.mutate { it.copy(traffic = it.traffic.copy(perApp = on)) }
                    state.rebuildMeter()
                }
            )
            ZappSettingRow(
                title = tr(Str.TRAFFIC_SMOOTH),
                checked = state.config.traffic.activeInterfacesOnly,
                onCheckedChange = { on ->
                    state.mutate { it.copy(traffic = it.traffic.copy(activeInterfacesOnly = on)) }
                    state.rebuildMeter()
                }
            )
            ZappButton(
                text = tr(Str.TRAFFIC_OPEN_WINDOW),
                onClick = { state.setTrafficWindowOpen(true) },
                modifier = Modifier.fillMaxWidth()
            )
        }
    }
}

@Composable
private fun PresetsSection(state: AppState) {
    val scope = rememberCoroutineScope()
    val message = remember { mutableStateOf<Str?>(null) }
    var failure by rememberSaveable { mutableStateOf(false) }

    LaunchedEffect(message.value) {
        if (message.value != null) {
            kotlinx.coroutines.delay(3000)
            message.value = null
        }
    }

    PageGroup(tr(Str.SETTINGS_PRESETS)) {
        message.value?.let { text ->
            ZappBanner(
                title = tr(text),
                state = if (failure) ComponentColors.State.DANGER else ComponentColors.State.ACTIVE
            )
        }
        ZappCard {
            if (state.presets.isEmpty()) {
                Text(
                    text = tr(Str.LIST_EMPTY_PRESETS),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            } else {
                state.presets.forEach { preset ->
                    PresetRow(
                        name = preset.name,
                        onApply = {
                            state.applyPreset(preset)
                            message.value = state.lastMessage?.key() ?: Str.ERR_PRESET_APPLIED
                            failure = state.lastMessage?.ok != true
                        },
                        onDelete = {
                            state.deletePreset(preset.id)
                            message.value = Str.ERR_PRESET_DELETED
                            failure = false
                        }
                    )
                }
            }

            ZappButtonRow(modifier = Modifier.fillMaxWidth()) {
                ZappButton(
                    text = tr(Str.COMMON_EXPORT),
                    role = ButtonRole.SECONDARY,
                    onClick = {
                        scope.launch {
                            val result = state.exportPreset()
                            message.value = result.key()
                            failure = !result.ok
                        }
                    },
                    modifier = Modifier.weight(1f)
                )
                ZappButton(
                    text = tr(Str.COMMON_IMPORT),
                    role = ButtonRole.SECONDARY,
                    onClick = {
                        scope.launch {
                            val result = state.importPreset()
                            message.value = result.key()
                            failure = !result.ok
                        }
                    },
                    modifier = Modifier.weight(1f)
                )
            }
            ZappButton(
                text = tr(Str.COMMON_RESET),
                role = ButtonRole.GHOST,
                onClick = {
                    scope.launch {
                        state.resetToDefaults()
                        message.value = Str.ERR_PRESET_APPLIED
                        failure = false
                    }
                },
                modifier = Modifier.fillMaxWidth()
            )
        }
    }
}

/** VPN-мелочи, которые не нужны на основном экране. */
@Composable
 private fun VpnExtrasSection(state: AppState) {
    val vpn = state.config.vpn
    PageGroup(tr(Str.VPN_PROFILES)) {
        VpnProfileEditor(state)
    }
    PageGroup(tr(Str.VPN_ROUTING)) {
        VpnRoutingCard(state)
    }
    PageGroup(tr(Str.SETTINGS_SYSTEM)) {
        ZappCard {
            ZappSettingRow(
                title = tr(Str.VPN_CLASH_API),
                checked = vpn.clashApiEnabled,
                onCheckedChange = { on ->
                    state.mutate { it.copy(vpn = it.vpn.copy(clashApiEnabled = on)) }
                }
            )
            if (vpn.clashApiEnabled) {
                ZappNumberField(
                    label = tr(Str.VPN_PORT),
                    value = vpn.clashApiPort,
                    onValueChange = { v ->
                        state.mutate { it.copy(vpn = it.vpn.copy(clashApiPort = v ?: 9090)) }
                    },
                    placeholder = "9090"
                )
                ZappTextField(
                    label = tr(Str.VPN_SECRET),
                    value = vpn.clashApiSecret,
                    onValueChange = { v ->
                        state.mutate { it.copy(vpn = it.vpn.copy(clashApiSecret = v)) }
                    }
                )
            }
        }
    }
}

/**
 * Настройки Tor.
 *
 * Всё, что относится к тор'у, лежит здесь, а не в разделе VPN: SOCKS-порт
 * уехал из VPN-окна, чтобы каждое окно говорило только о своём.
 */
@Composable
private fun TorSection(state: AppState) {
    val vpn = state.config.vpn
    PageGroup(tr(Str.SETTINGS_SYSTEM)) {
        ZappCard {
            ZappNumberField(
                label = tr(Str.VPN_TOR_SOCKS),
                value = vpn.torSocksPort,
                onValueChange = { v ->
                    state.mutate { it.copy(vpn = it.vpn.copy(torSocksPort = v ?: 9050)) }
                },
                placeholder = "9050"
            )
        }
    }
}

/**
 * Тонкие настройки Zapret.
 *
 * Перенесены сюда с главного экрана: фильтры портов, desync-ручки и пути
 * к бинарю -- это разовая настройка под конкретную сеть, а главный экран
 * показывает только выбор стратегии и кнопки запуска.
 */
@Composable
private fun ZapretSection(state: AppState) {
    val config = state.config.zapret
    val family = ZapretStrategy.of(config.strategy).family

    PageGroup(tr(Str.ZAPRET_STRATEGY)) {
        ZappCard {
            ZappSettingRow(
                title = tr(Str.ZAPRET_ALL_TRAFFIC),
                checked = config.allTraffic,
                onCheckedChange = { on -> state.mutate { it.copy(zapret = it.zapret.copy(allTraffic = on)) } }
            )
            ZappSettingRow(
                title = tr(Str.ZAPRET_IPV4_ONLY),
                checked = config.ipv4Only,
                onCheckedChange = { on -> state.mutate { it.copy(zapret = it.zapret.copy(ipv4Only = on)) } }
            )
            ZappSettingRow(
                title = tr(Str.COMMON_TEST),
                checked = config.verifyStrategy,
                onCheckedChange = { on ->
                    state.mutate { it.copy(zapret = it.zapret.copy(verifyStrategy = on)) }
                }
            )
            ZappSettingRow(
                title = tr(Str.DAEMON_AUTOSTART),
                checked = config.autoRestart,
                onCheckedChange = { on ->
                    state.mutate { it.copy(zapret = it.zapret.copy(autoRestart = on)) }
                }
            )
        }
    }

    PageGroup(tr(Str.ZAPRET_FILTERS)) {
        ZappCard {
            ZappTextField(
                label = tr(Str.ZAPRET_FILTER_TCP),
                value = config.filterTcp,
                onValueChange = { v -> state.mutate { it.copy(zapret = it.zapret.copy(filterTcp = v)) } },
                placeholder = "80,443"
            )
            ZappTextField(
                label = tr(Str.ZAPRET_FILTER_UDP),
                value = config.filterUdp,
                onValueChange = { v -> state.mutate { it.copy(zapret = it.zapret.copy(filterUdp = v)) } },
                placeholder = "443"
            )
            ZappTextField(
                label = tr(Str.LIST_EXCLUSIONS),
                value = config.exclusions,
                onValueChange = { v -> state.mutate { it.copy(zapret = it.zapret.copy(exclusions = v)) } },
                placeholder = "cdn.example.org"
            )
        }
    }

    PageGroup(tr(Str.SETTINGS_SYSTEM)) {
        ZappCard {
            ZappTextField(
                label = tr(Str.DAEMON_BINARY),
                value = config.binaryPath,
                onValueChange = { v -> state.mutate { it.copy(zapret = it.zapret.copy(binaryPath = v)) } },
                placeholder = "nfqws"
            )
            ZappChoiceRow(
                label = tr(Str.ZAPRET_MODE),
                options = ZapretMode.entries.map { Choice(it.code, it.modeLabel()) },
                selected = config.mode,
                onSelect = { code -> state.mutate { it.copy(zapret = it.zapret.copy(mode = code)) } }
            )
            ZappChoiceRow(
                label = tr(Str.ZAPRET_DPI_DESYNC),
                options = ZAPRET_DESYNC_CHOICES,
                selected = config.desyncMethods.firstOrNull() ?: "",
                onSelect = { method ->
                    state.mutate { it.copy(zapret = it.zapret.copy(desyncMethods = setOf(method))) }
                }
            )

            // TTL и окно относятся к desync: в других семействах поля не
            // показываются, а не сереют.
            if (family == ZapretFamily.DESYNC) {
                ZappNumberField(
                    label = tr(Str.ZAPRET_TTL),
                    value = config.ttl,
                    onValueChange = { v -> state.mutate { it.copy(zapret = it.zapret.copy(ttl = v)) } },
                    placeholder = "2"
                )
                if (config.ttl != null) {
                    ZappSettingRow(
                        title = "--dpi-desync-ttl6",
                        checked = config.ttl6,
                        onCheckedChange = { on ->
                            state.mutate { it.copy(zapret = it.zapret.copy(ttl6 = on)) }
                        }
                    )
                }
                ZappNumberField(
                    label = tr(Str.ZAPRET_WINDOW),
                    value = config.wsize,
                    onValueChange = { v -> state.mutate { it.copy(zapret = it.zapret.copy(wsize = v)) } },
                    placeholder = "65535"
                )
                ZappTextField(
                    label = "--dpi-desync-split-pos",
                    value = config.dpiSplitPos,
                    onValueChange = { v -> state.mutate { it.copy(zapret = it.zapret.copy(dpiSplitPos = v)) } },
                    placeholder = "1,m1"
                )
                ZappNumberField(
                    label = "--dpi-desync-split-seqovl",
                    value = config.dpiSplitSeqovl,
                    onValueChange = { v ->
                        state.mutate { it.copy(zapret = it.zapret.copy(dpiSplitSeqovl = v)) }
                    }
                )
                ZappNumberField(
                    label = "--dpi-desync-repeats",
                    value = config.fakeRepeats,
                    onValueChange = { v -> state.mutate { it.copy(zapret = it.zapret.copy(fakeRepeats = v)) } }
                )
            }

            if (family == ZapretFamily.FAKE || family == ZapretFamily.PATCH) {
                ZappSettingRow(
                    title = tr(Str.ZAPRET_FAKE),
                    checked = config.fakePacket,
                    onCheckedChange = { on ->
                        state.mutate { it.copy(zapret = it.zapret.copy(fakePacket = on)) }
                    }
                )
                ZappNumberField(
                    label = tr(Str.DPI_FAKE_SEQ),
                    value = config.fakeSeq,
                    onValueChange = { v -> state.mutate { it.copy(zapret = it.zapret.copy(fakeSeq = v)) } }
                )
                ZappSettingRow(
                    title = tr(Str.DPI_FAKE_CSUM),
                    checked = config.fakeCsum,
                    onCheckedChange = { on ->
                        state.mutate { it.copy(zapret = it.zapret.copy(fakeCsum = on)) }
                    }
                )
                ZappSettingRow(
                    title = "--dpi-desync-fooling=ts",
                    checked = config.foolingTs,
                    onCheckedChange = { on ->
                        state.mutate { it.copy(zapret = it.zapret.copy(foolingTs = on)) }
                    }
                )
            }

            ZappSettingRow(
                title = "--hostcase",
                checked = config.hostCase,
                onCheckedChange = { on -> state.mutate { it.copy(zapret = it.zapret.copy(hostCase = on)) } }
            )
            ZappSettingRow(
                title = "--hostspell",
                checked = config.hostSpell,
                onCheckedChange = { on -> state.mutate { it.copy(zapret = it.zapret.copy(hostSpell = on)) } }
            )
            ZappSettingRow(
                title = "--hostnospace",
                checked = config.hostNoSpace,
                onCheckedChange = { on -> state.mutate { it.copy(zapret = it.zapret.copy(hostNoSpace = on)) } }
            )
            ZappSettingRow(
                title = "--methodeol",
                checked = config.methodEol,
                onCheckedChange = { on -> state.mutate { it.copy(zapret = it.zapret.copy(methodEol = on)) } }
            )
            ZappNumberField(
                label = "--wssize",
                value = config.wsSize,
                onValueChange = { v -> state.mutate { it.copy(zapret = it.zapret.copy(wsSize = v)) } }
            )
            Text(
                text = tr(Str.ZAPRET_COMMAND),
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Text(
                text = config.toCommandLine(),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurface
            )
        }
    }
}

/**
 * Тонкие настройки GoodbyeDPI.
 *
 * Как и у Zapret: разовая настройка под сеть живёт здесь, а не на
 * главном экране, который отвечает за запуск и режим.
 */
@Composable
private fun GoodbyeDpiSection(state: AppState) {
    val config = state.config.goodbyeDpi
    val mode = GoodbyeDpiMode.of(config.mode)

    PageGroup(tr(Str.DPI_MODE)) {
        ZappCard {
            ZappSettingRow(
                title = tr(Str.DAEMON_AUTOSTART),
                checked = config.autoStart,
                onCheckedChange = { on ->
                    state.mutate { it.copy(goodbyeDpi = it.goodbyeDpi.copy(autoStart = on)) }
                }
            )
            if (mode == GoodbyeDpiMode.SPLIT_POS) {
                ZappNumberField(
                    label = tr(Str.DPI_SPLIT_POS),
                    value = config.splitPos.toIntOrNull(),
                    onValueChange = { v ->
                        state.mutate {
                            it.copy(goodbyeDpi = it.goodbyeDpi.copy(splitPos = v?.toString() ?: "2"))
                        }
                    },
                    placeholder = "2"
                )
            }
            if (mode != GoodbyeDpiMode.FAKE_SSL) {
                ZappNumberField(
                    label = tr(Str.DPI_FAKE_SEQ),
                    value = config.fakeSeq,
                    onValueChange = { v ->
                        state.mutate { it.copy(goodbyeDpi = it.goodbyeDpi.copy(fakeSeq = v)) }
                    }
                )
            }
            if (mode != GoodbyeDpiMode.FAKE_PATCH) {
                ZappSettingRow(
                    title = tr(Str.DPI_FAKE_CSUM),
                    checked = config.fakeCsum,
                    onCheckedChange = { on ->
                        state.mutate { it.copy(goodbyeDpi = it.goodbyeDpi.copy(fakeCsum = on)) }
                    }
                )
            }
            if (mode == GoodbyeDpiMode.FAKE_SNI) {
                ZappTextField(
                    label = tr(Str.DPI_FAKE_SNI),
                    value = config.fakeSni,
                    onValueChange = { v ->
                        state.mutate { it.copy(goodbyeDpi = it.goodbyeDpi.copy(fakeSni = v)) }
                    },
                    placeholder = "example.com"
                )
            }
            // Название -- сам флаг: включён означает «пропустить без SNI».
            ZappSettingRow(
                title = "--allow-no-sni",
                checked = !config.hostCheck,
                onCheckedChange = { on ->
                    state.mutate { it.copy(goodbyeDpi = it.goodbyeDpi.copy(hostCheck = !on)) }
                }
            )
            ZappTextField(
                label = tr(Str.DPI_DAEMON_PATH),
                value = config.daemonPath,
                onValueChange = { v ->
                    state.mutate { it.copy(goodbyeDpi = it.goodbyeDpi.copy(daemonPath = v)) }
                },
                placeholder = "goodbyedpi"
            )
            Text(
                text = tr(Str.ZAPRET_COMMAND),
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Text(
                text = config.toCommandLine(),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurface
            )
        }
    }
}

@Composable
private fun ComponentsSection(state: AppState) {
    val scope = rememberCoroutineScope()
    val installed = NativeBinaries.installed()
    PageGroup(tr(Str.COMPONENTS)) {
        ZappCard {
            installed.forEach { tool ->
                InfoRow(label = tool, value = state.appVersion)
            }
            ZappButton(
                text = tr(Str.COMPONENTS_REINSTALL),
                role = ButtonRole.SECONDARY,
                onClick = { scope.launch { NativeBinaries.reinstall() } }
            )
        }
    }
}

// --- Журнал: детальный лог с тумблером ---

@Composable
private fun LogSection(state: AppState) {
    val lines by AppLog.lines.collectAsState()
    val scope = rememberCoroutineScope()

    PageGroup(tr(Str.SETTINGS_LOG)) {
        ZappCard {
            ZappSettingRow(
                title = tr(Str.LOG_DETAIL_ON),
                subtitle = tr(Str.LOG_FILE_HINT),
                checked = state.config.detailLogEnabled,
                onCheckedChange = { on ->
                    state.mutate { it.copy(detailLogEnabled = on) }
                }
            )
            ZappButton(
                text = tr(Str.LOG_CLEAR),
                role = ButtonRole.DANGER,
                onClick = { AppLog.clear() },
                modifier = Modifier.fillMaxWidth()
            )
        }
        ZappCard {
            if (lines.isEmpty()) {
                Text(
                    text = tr(Str.LOG_EMPTY),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            } else {
                // Хвост ленты: новые записи внизу, экран показывает последние.
                lines.takeLast(100).forEach { line ->
                    // Ошибка -- цветом, чтобы её было видно с ходу.
                    val isError = "[err" in line ||
                        line.contains("error", ignoreCase = true) ||
                        line.contains("fail", ignoreCase = true)
                    Text(
                        text = line,
                        style = MaterialTheme.typography.labelSmall,
                        color = if (isError) {
                            MaterialTheme.colorScheme.error
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        },
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            }
        }
    }
}

// --- Документация ---

@Composable
private fun DocsSection() {
    val lang = AppLangState.current
    PageGroup(tr(Str.SETTINGS_DOCS)) {
        Docs.sections.forEach { section ->
            ZappSectionTitle(section.title.pick(lang))
            ZappCard {
                section.items.forEach { item ->
                    Column(modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                        Text(
                            text = item.title.pick(lang),
                            style = MaterialTheme.typography.titleSmall,
                            color = MaterialTheme.colorScheme.primary
                        )
                        Text(
                            text = item.body.pick(lang),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            textAlign = TextAlign.Start
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun AboutSection(state: AppState) {
    PageGroup(tr(Str.SETTINGS_ABOUT)) {
        ZappCard {
            InfoRow(label = tr(Str.ABOUT_VERSION), value = state.appVersion)
            InfoRow(label = tr(Str.ABOUT_PLATFORM), value = platformName(state))
        }
    }
}

// --- Общие строки ---

/** Строка пресета с действиями. */
@Composable
private fun PresetRow(name: String, onApply: () -> Unit, onDelete: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = name.ifBlank { "-" },
            style = MaterialTheme.typography.bodyLarge,
            modifier = Modifier.weight(1f)
        )
        ZappButton(
            text = tr(Str.COMMON_APPLY),
            role = ButtonRole.PRIMARY,
            onClick = onApply
        )
        ZappButton(
            text = tr(Str.COMMON_DELETE),
            role = ButtonRole.DANGER,
            onClick = onDelete
        )
    }
}

/** Пара «подпись -- значение». */
@Composable
private fun InfoRow(label: String, value: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(4.dp),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(text = label, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(text = value, color = MaterialTheme.colorScheme.onSurface)
    }
}

private fun platformName(state: AppState): String =
    if (state.caps.gpsSpoofingSupported) "Android" else "Desktop"

/** Строка разрешения: статус, подсказка и кнопка, если действие есть. */
@Composable
fun PermissionRow(status: PermissionStatus, onAction: () -> Unit) {
    val granted = status.state == PermissionState.GRANTED
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = tr(status.id.title),
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurface
            )
            Text(
                text = tr(status.state.labelKey()),
                style = MaterialTheme.typography.bodySmall,
                color = if (granted) {
                    MaterialTheme.colorScheme.primary
                } else {
                    MaterialTheme.colorScheme.tertiary
                }
            )
            status.hint?.let { hint ->
                Text(
                    text = tr(hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
        if (!granted && status.state != PermissionState.UNSUPPORTED) {
            ZappButton(
                text = tr(
                    if (status.state == PermissionState.SETTINGS) {
                        Str.PERM_OPEN_SETTINGS
                    } else {
                        Str.PERM_GRANT
                    }
                ),
                role = ButtonRole.SECONDARY,
                onClick = onAction
            )
        }
    }
}

/** Состояние разрешения -- ключ строки. */
private fun PermissionState.labelKey(): Str = when (this) {
    PermissionState.GRANTED -> Str.PERM_STATE_GRANTED
    PermissionState.DENIED -> Str.PERM_STATE_DENIED
    PermissionState.SETTINGS -> Str.PERM_STATE_SETTINGS
    PermissionState.UNSUPPORTED -> Str.PERM_STATE_UNSUPPORTED
}

/** Итог проверки обновлений -- ключ строки. */
private fun UpdateState.labelKey(): Str = when (this) {
    UpdateState.UNKNOWN -> Str.UPD_STATE_UNKNOWN
    UpdateState.CHECKING -> Str.UPD_STATE_CHECKING
    UpdateState.CURRENT -> Str.UPD_STATE_CURRENT
    UpdateState.AVAILABLE -> Str.UPD_STATE_AVAILABLE
    UpdateState.ERROR -> Str.UPD_STATE_ERROR
}

/** Пустое значение устройства -- тире вместо пустой строки. */
private fun String?.orDash(): String = if (isNullOrBlank()) "-" else this

/** Название языка на его собственном языке. */
private fun labelOf(lang: AppLang): String = when (lang) {
    AppLang.EN -> "English"
    AppLang.RU -> "Русский"
    AppLang.ZH -> "中文"
}

/** Список методов desync: стабильный, вне композиции. */
private val ZAPRET_DESYNC_CHOICES = listOf(
    "fake", "split2", "fakedsplit", "multisplit", "datanozzle", "multiback", "seqovl"
).map { Choice(it, it) }

/** Метка режима Zapret на языке интерфейса. */
@Composable
private fun ZapretMode.modeLabel(): String {
    val lang = AppLangState.current
    return if (lang == AppLang.RU) ru else en
}
