package xyz.azraellab.zapp.ui.pages

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
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
import xyz.azraellab.zapp.core.BuildMode
import xyz.azraellab.zapp.core.Docs
import xyz.azraellab.zapp.core.GoodbyeDpiMode
import xyz.azraellab.zapp.core.RegionTuner
import xyz.azraellab.zapp.core.Str
import xyz.azraellab.zapp.core.backup.RestoreMode
import xyz.azraellab.zapp.core.ZapretFamily
import xyz.azraellab.zapp.core.ZapretMode
import xyz.azraellab.zapp.core.ZapretStrategy
import xyz.azraellab.zapp.core.consistency.SettingKeys
import xyz.azraellab.zapp.core.dev.isDebugBuild
import xyz.azraellab.zapp.core.engine.isLive
import xyz.azraellab.zapp.core.log.AppLog
import xyz.azraellab.zapp.core.log.ErrorLog
import xyz.azraellab.zapp.core.log.looksLikeErrorLine
import xyz.azraellab.zapp.core.log.stripAnsi
import xyz.azraellab.zapp.core.native.NativeBinaries
import xyz.azraellab.zapp.core.permissions.PermissionId
import xyz.azraellab.zapp.core.permissions.PermissionState
import xyz.azraellab.zapp.core.permissions.PermissionStatus
import xyz.azraellab.zapp.core.update.UPDATE_COMPARE_SEMVER
import xyz.azraellab.zapp.core.update.UPDATE_COMPARE_TAG
import xyz.azraellab.zapp.core.update.UpdateDownload
import xyz.azraellab.zapp.core.update.UpdateState
import xyz.azraellab.zapp.core.update.ZappComponent
import xyz.azraellab.zapp.core.update.cachedUpdateApk
import xyz.azraellab.zapp.core.ThemeMode
import xyz.azraellab.zapp.core.update.probeComponentVersion
import xyz.azraellab.zapp.ui.AppLangState
import xyz.azraellab.zapp.ui.components.ComponentColors.ButtonRole
import xyz.azraellab.zapp.ui.components.ComponentColors
import xyz.azraellab.zapp.ui.components.Choice
import xyz.azraellab.zapp.ui.components.ZappBanner
import xyz.azraellab.zapp.ui.components.ZappButton
import xyz.azraellab.zapp.ui.components.ZappButtonRow
import xyz.azraellab.zapp.ui.components.ZappCard
import xyz.azraellab.zapp.ui.components.ZappChoiceRow
import xyz.azraellab.zapp.ui.components.ZappDialog
import xyz.azraellab.zapp.ui.components.ZappEmptyCard
import xyz.azraellab.zapp.ui.components.ZappListRow
import xyz.azraellab.zapp.ui.components.ZappNumberField
import xyz.azraellab.zapp.ui.components.ZappSectionTitle
import xyz.azraellab.zapp.ui.components.ZappSettingRow
import xyz.azraellab.zapp.ui.components.ZappTextField
import xyz.azraellab.zapp.ui.components.debugPath
import xyz.azraellab.zapp.ui.components.RegionDropdown
import xyz.azraellab.zapp.ui.consistency.forcedValue
import xyz.azraellab.zapp.ui.consistency.isLocked
import xyz.azraellab.zapp.ui.consistency.lockReason
import xyz.azraellab.zapp.ui.consistency.settingLocks
import xyz.azraellab.zapp.ui.nav.ZappBackHandler
import xyz.azraellab.zapp.ui.theme.AzraelSpace
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
    // «Тесты» -- вторая ступень внутри «Для разработчиков», поэтому назад
    // из неё ведёт в раздел, а не мимо, в корень. Строки perapp.* --
    // ступень внутри «Приложений».
    ZappBackHandler(enabled = section != null) {
        section = when {
            section?.startsWith("perapp.") == true -> "perapp"
            section == "devtests" -> "dev"
            else -> null
        }
    }

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
            BackupSection(state)
        }

        "perf" -> SubPage(Str.SETTINGS_PERF, back = { section = null }) {
            PerfSection(state)
        }

        "perapp" -> SubPage(Str.SETTINGS_PERAPP, back = { section = null }) {
            PerAppRoot(state, onOpen = { section = it })
        }

        "perapp.vpn" -> SubPage(Str.TAB_VPN, back = { section = "perapp" }) {
            PerAppPicker(state, PerAppScope.VPN)
        }

        "perapp.tor" -> SubPage(Str.TAB_TOR, back = { section = "perapp" }) {
            PerAppPicker(state, PerAppScope.TOR)
        }

        "perapp.zapret" -> SubPage(Str.TAB_ZAPRET, back = { section = "perapp" }) {
            PerAppPicker(state, PerAppScope.ZAPRET)
        }

        "perapp.gdpi" -> SubPage(Str.TAB_GOODBYE_DPI, back = { section = "perapp" }) {
            PerAppPicker(state, PerAppScope.GDPI)
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
            ComponentsSection()
        }

        "log" -> SubPage(Str.SETTINGS_LOG, back = { section = null }) {
            LogSection(state)
        }

        // Только debug-сборка: в релизе этих строк нет вовсе (см. isDebugBuild).
        "dev" -> SubPage(Str.SETTINGS_DEV, back = { section = null }) {
            ZappCard {
                CategoryRow(tr(Str.DEV_TESTS), path = "settings.dev.tests") { section = "devtests" }
            }
        }

        "devtests" -> SubPage(Str.DEV_TESTS, back = { section = "dev" }) {
            DevTestsSection()
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
    // Простой режим: остаются категории базового сценария. Продвинутые
    // прячутся вместе со своими вкладками, а не ведут на страницы
    // скрытых разделов.
    val novice = state.config.noviceMode
    PageScaffold(title = tr(Str.TAB_SETTINGS)) {
        ZappCard {
            CategoryRow(tr(Str.SETTINGS_SECTION_GENERAL), path = "settings.general") { onOpen("general") }
            CategoryRow(tr(Str.SETTINGS_PERMISSIONS), path = "settings.permissions") { onOpen("permissions") }
            CategoryRow(tr(Str.SETTINGS_DEVICE), path = "settings.device") { onOpen("device") }
            CategoryRow(tr(Str.SETTINGS_UPDATES), path = "settings.updates") { onOpen("updates") }
            CategoryRow(tr(Str.TAB_TRAFFIC), path = "settings.traffic") { onOpen("traffic") }
            if (!novice) {
                CategoryRow(tr(Str.SETTINGS_PRESETS), path = "settings.presets") { onOpen("presets") }
                CategoryRow(tr(Str.SETTINGS_PERF), path = "settings.perf") { onOpen("perf") }
                CategoryRow(tr(Str.SETTINGS_PERAPP), path = "settings.perapp") { onOpen("perapp") }
            }
            CategoryRow(tr(Str.TAB_VPN), path = "settings.vpn") { onOpen("vpn") }
            if (!novice) {
                CategoryRow(tr(Str.TAB_TOR), path = "settings.tor") { onOpen("tor") }
            }
            if (NativeBinaries.installed().isNotEmpty() && !novice) {
                CategoryRow(tr(Str.TAB_ZAPRET), path = "settings.zapret") { onOpen("zapret") }
                CategoryRow(tr(Str.TAB_GOODBYE_DPI), path = "settings.gdpi") { onOpen("dpi") }
            }
            if (NativeBinaries.installed().isNotEmpty() && !novice) {
                CategoryRow(tr(Str.COMPONENTS), path = "settings.components") { onOpen("components") }
            }
            if (!novice) {
                CategoryRow(tr(Str.SETTINGS_LOG), path = "settings.log") { onOpen("log") }
            }
            CategoryRow(tr(Str.SETTINGS_DOCS), path = "settings.docs") { onOpen("docs") }
            CategoryRow(tr(Str.SETTINGS_ABOUT), path = "settings.about") { onOpen("about") }
            // Разработка: только debug, в релизе строка не рисуется.
            if (isDebugBuild() && !novice) {
                CategoryRow(tr(Str.SETTINGS_DEV), path = "settings.dev") { onOpen("dev") }
            }
        }
    }
}

/** Строка категории: нажимается целиком, хвост -- стрелка «открыть». */
@Composable
private fun CategoryRow(title: String, path: String, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .debugPath(path)
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
            onClick = back,
            path = "settings.back"
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
            Spacer(modifier = Modifier.height(AzraelSpace.xs))
            Text(
                text = tr(Str.WELCOME_Q_REGION_HINT),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            RegionDropdown(
                selected = state.config.region,
                onSelect = { code ->
                    state.mutate { RegionTuner.apply(it, code) }
                    AppLog.log("app", "region set to $code")
                },
                path = "settings.region"
            )
        }
    }
    // Оформление: режим темы и Material You. Отдельная группа --
    // настройка вида, а не поведения, и менять её должны, не читая инструкцию.
    PageGroup(tr(Str.SETTINGS_APPEARANCE)) {
        ZappCard {
            ZappChoiceRow(
                label = tr(Str.SETTINGS_THEME),
                options = ThemeMode.entries.map { Choice(it.name, themeLabel(it)) },
                selected = state.config.themeMode.name,
                onSelect = { name ->
                    val mode = ThemeMode.entries.firstOrNull { it.name == name }
                        ?: ThemeMode.DARK
                    state.mutate { it.copy(themeMode = mode) }
                    AppLog.log("app", "theme mode -> $mode")
                },
                path = "settings.themeMode"
            )
            Spacer(modifier = Modifier.height(AzraelSpace.xs))
            ZappSettingRow(
                title = tr(Str.SETTINGS_DYNAMIC_COLORS),
                subtitle = tr(Str.SETTINGS_DYNAMIC_COLORS_HINT),
                checked = state.config.dynamicColors,
                onCheckedChange = { on ->
                    state.mutate { it.copy(dynamicColors = on) }
                    AppLog.log("app", "dynamic colors -> $on")
                },
                path = "settings.dynamicColors"
            )
        }
    }
    PageGroup(tr(Str.SETTINGS_NOVICE_MODE)) {
        ZappCard {
            ZappSettingRow(
                title = tr(Str.SETTINGS_NOVICE_MODE),
                subtitle = tr(Str.SETTINGS_NOVICE_MODE_HINT),
                checked = state.config.noviceMode,
                onCheckedChange = { on ->
                    state.mutate { it.copy(noviceMode = on) }
                    AppLog.log("app", "novice mode -> $on")
                },
                path = "settings.noviceMode"
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

    // Старт после перезагрузки: отдельная группа -- настройка
    // поведения системы, а не оформления или языка. На десктопе это
    // настоящая регистрация в системе: Run-ключ, файл
    // автозапуска, Login Items, -- и тумблер читает её напрямую;
    // на Android остаётся флагом для BroadcastReceiver.
    PageGroup(tr(Str.AUTOSTART_BOOT)) {
        ZappCard {
            ZappSettingRow(
                title = tr(Str.AUTOSTART_BOOT),
                subtitle = tr(Str.AUTOSTART_BOOT_HINT),
                checked = if (state.caps.autostartSupported) {
                    state.autostartOn.collectAsState().value
                } else {
                    state.config.bootAutostart
                },
                onCheckedChange = { on ->
                    if (state.caps.autostartSupported) {
                        state.setAutostart(on)
                    } else {
                        state.mutate { it.copy(bootAutostart = on) }
                        AppLog.log("app", "boot autostart -> $on")
                    }
                },
                path = "settings.bootAutostart"
            )
        }
    }

    // Системный прокси: тумблер есть только там, где есть что
    // включать, -- раздел на платформах без поддержки не показывается.
    if (state.caps.systemProxySupported) {
        PageGroup(tr(Str.SETTINGS_SYSTEM_PROXY)) {
            ZappCard {
                ZappSettingRow(
                    title = tr(Str.SETTINGS_SYSTEM_PROXY),
                    subtitle = tr(Str.SETTINGS_SYSTEM_PROXY_HINT),
                    checked = state.config.systemProxy,
                    onCheckedChange = { on -> state.setSystemProxy(on) },
                    path = "settings.systemProxy"
                )
                Spacer(modifier = Modifier.height(AzraelSpace.xs))
                ZappSettingRow(
                    title = tr(Str.SETTINGS_PROXY_AUTH),
                    subtitle = tr(Str.SETTINGS_PROXY_AUTH_HINT),
                    checked = state.config.vpn.proxyAuthEnabled,
                    onCheckedChange = { on ->
                        state.mutate {
                            it.copy(vpn = it.vpn.copy(proxyAuthEnabled = on))
                        }
                        AppLog.log("app", "proxy auth -> $on")
                    },
                    path = "settings.proxyAuth"
                )
                // Поля живут под тумблером: без включённой авторизации
                // логин и пароль -- просто места для ошибки.
                if (state.config.vpn.proxyAuthEnabled) {
                    ZappTextField(
                        label = tr(Str.SETTINGS_PROXY_USER),
                        value = state.config.vpn.proxyAuthUser,
                        onValueChange = { v ->
                            state.mutate {
                                it.copy(vpn = it.vpn.copy(proxyAuthUser = v))
                            }
                        },
                        path = "settings.proxyAuthUser"
                    )
                    ZappTextField(
                        label = tr(Str.SETTINGS_PROXY_PASS),
                        value = state.config.vpn.proxyAuthPassword,
                        onValueChange = { v ->
                            state.mutate {
                                it.copy(vpn = it.vpn.copy(proxyAuthPassword = v))
                            }
                        },
                        path = "settings.proxyAuthPassword"
                    )
                }
            }
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
    val locks = settingLocks(state)
    val bgAnimForced = forcedValue(locks, SettingKeys.BG_ANIM)
    val bgAnimChecked = bgAnimForced ?: perf.animatedBackground
    PageGroup(tr(Str.SETTINGS_PERF)) {
        ZappCard {
            ZappSettingRow(
                title = tr(Str.PERF_BG_ANIM),
                checked = bgAnimChecked,
                enabled = !isLocked(locks, SettingKeys.BG_ANIM),
                subtitle = lockReason(locks, SettingKeys.BG_ANIM),
                onCheckedChange = { on ->
                    state.mutate { it.copy(perf = it.perf.copy(animatedBackground = on).sanitized()) }
                }
            )
            ZappChoiceRow(
                label = tr(Str.PERF_BG_FPS),
                options = listOf(15, 30, 60).map { Choice(it.toString(), "$it fps") },
                selected = perf.backgroundFps.toString(),
                enabled = !isLocked(locks, SettingKeys.BG_FPS),
                onSelect = { code ->
                    state.mutate {
                        it.copy(perf = it.perf.copy(backgroundFps = code.toInt()).sanitized())
                    }
                }
            )
            if (locks[SettingKeys.BG_FPS] != null) {
                Text(
                    text = lockReason(locks, SettingKeys.BG_FPS) ?: "",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
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

    // Перечитываем при каждом входе на экран: мок-локацию выдают мимо
    // приложения (`appops set` в шелле или экран настроек разработчика),
    // и без этого опроса здесь висело бы «Не дано» до самого force-stop.
    // Возврат из системных настроек ловит resume-слушатель Activity,
    // этот вызов закрывает простое открытие вкладки.
    LaunchedEffect(Unit) { state.permissions.refresh() }

    PageGroup(tr(Str.SETTINGS_PERMISSIONS)) {
        ZappCard {
            if (statuses.isEmpty()) {
                ZappEmptyCard(title = tr(Str.PROBE_UNKNOWN))
            } else {
                statuses.forEach { status ->
                    PermissionRow(
                        status = status,
                        onAction = { state.permissions.request(status.id) {} },
                        path = "settings.permission.${status.id.name.lowercase()}"
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
            // Строки Root и Magisk -- только в рут-сборке. В норуте их
            // не должно быть вообще, а не «Нет»: норут обязан не
            // упоминать root ни в одном месте экрана.
            if (BuildMode.rootAllowed) {
                InfoRow(tr(Str.PROBE_ROOT), tr((probe?.root
                    ?: xyz.azraellab.zapp.core.probe.Probe.UNKNOWN).label))
                InfoRow(tr(Str.PROBE_MAGISK), tr((probe?.magisk
                    ?: xyz.azraellab.zapp.core.probe.Probe.UNKNOWN).label))
            }
            InfoRow(tr(Str.PROBE_VPN), tr((probe?.vpnTunnel
                ?: xyz.azraellab.zapp.core.probe.Probe.UNKNOWN).label))
            // Повторная проверка: политику Magisk выдают вне
            // приложения, и без сброса кэша «Root: Нет» висело бы на
            // экране до самого перезапуска -- без единой подсказки.
            ZappButton(
                text = tr(Str.PROBE_AGAIN),
                onClick = { state.reprobe() },
                role = ButtonRole.GHOST,
                path = "settings.device.reprobe"
            )
        }
    }
}

@Composable
private fun UpdatesSection(state: AppState) {
    val updateState by state.updateState.collectAsState()
    val download by state.updateDownload.collectAsState()
    val release by state.release.collectAsState()
    PageGroup(tr(Str.SETTINGS_UPDATES)) {
        ZappCard {
            InfoRow(tr(Str.ABOUT_VERSION), state.appVersion)
            InfoRow(tr(Str.UPD_CHECK), tr(updateState.labelKey()))
            // Причина неудачи -- под строкой состояния: «Не удалось
            // проверить» без текста исключения ничего не объясняет.
            if (updateState == UpdateState.ERROR) {
                state.updateError?.takeIf { it.isNotBlank() }?.let { cause ->
                    Text(
                        text = cause,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error
                    )
                }
            }
            ZappButtonRow(modifier = Modifier.fillMaxWidth()) {
                ZappButton(
                    text = tr(Str.UPD_CHECK),
                    role = ButtonRole.SECONDARY,
                    enabled = updateState != UpdateState.CHECKING,
                    onClick = { state.checkForUpdates() },
                    modifier = Modifier.weight(1f)
                )
                if (updateState == UpdateState.AVAILABLE && release != null) {
                    ZappButton(
                        text = tr(Str.UPD_OPEN),
                        onClick = { state.openReleasePage() },
                        modifier = Modifier.weight(1f)
                    )
                }
            }

            // Второй ряд появляется только для свежего релиза: качаем и
            // ставим APK штатным установщиком, а на время загрузки кнопка
            // гаснет -- двойной тап ничего не ускорит.
            if (updateState == UpdateState.AVAILABLE && release != null) {
                val snoozed = state.config.updateSnoozeUntilMs > System.currentTimeMillis()
                val apkUrl = release?.apkUrl.orEmpty()
                if (state.caps.apkUpdatesSupported && apkUrl.isNotBlank()) {
                    val apkPath = release?.version?.let { cachedUpdateApk(it) }
                    ZappButtonRow(modifier = Modifier.fillMaxWidth()) {
                        if (download == UpdateDownload.RUNNING) {
                            val percent = state.updateDownloadPercent
                            ZappButton(
                                text = if (percent != null)
                                    "${tr(Str.UPD_DOWNLOADING)} $percent%"
                                else tr(Str.UPD_DOWNLOADING),
                                role = ButtonRole.SECONDARY,
                                enabled = false,
                                onClick = {},
                                modifier = Modifier.weight(1f)
                            )
                        } else if (apkPath != null) {
                            ZappButton(
                                text = tr(Str.UPD_INSTALL),
                                onClick = { state.installUpdate() },
                                modifier = Modifier.weight(1f)
                            )
                        } else {
                            ZappButton(
                                text = tr(Str.UPD_DOWNLOAD),
                                onClick = { state.downloadUpdate() },
                                modifier = Modifier.weight(1f)
                            )
                        }
                        ZappButton(
                            text = tr(Str.UPD_SNOOZE),
                            role = ButtonRole.SECONDARY,
                            enabled = !snoozed,
                            onClick = { state.snoozeUpdates() },
                            modifier = Modifier.weight(1f)
                        )
                    }
                } else if (!snoozed) {
                    ZappButton(
                        text = tr(Str.UPD_SNOOZE),
                        role = ButtonRole.SECONDARY,
                        onClick = { state.snoozeUpdates() }
                    )
                }
                state.updateDownloadError?.takeIf { it.isNotBlank() }?.let { cause ->
                    Text(
                        text = cause,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error
                    )
                }
                if (snoozed) {
                    Text(
                        text = tr(Str.UPD_SNOOZED),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                val changelog = release?.changelog.orEmpty()
                if (changelog.isNotBlank()) {
                    Text(
                        text = tr(Str.UPD_CHANGELOG),
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 8.dp, bottom = 4.dp)
                    )
                    Text(
                        text = changelog,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            }
        }

        // Режимы сравнения -- про будущие теги: пока они числовые, оба
        // режима дают один ответ, но «по смене тега» не пропустит
        // релиз с нечисловым именем.
        ZappCard {
            ZappChoiceRow(
                label = tr(Str.UPD_COMPARE),
                options = listOf(
                    Choice(UPDATE_COMPARE_SEMVER, tr(Str.UPD_COMPARE_SEMVER)),
                    Choice(UPDATE_COMPARE_TAG, tr(Str.UPD_COMPARE_TAG))
                ),
                selected = state.config.updateCompareMode,
                onSelect = { mode -> state.mutate { it.copy(updateCompareMode = mode) } },
                path = "updates.compare"
            )
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
    var confirmReset by rememberSaveable { mutableStateOf(false) }
    var includeProfiles by rememberSaveable { mutableStateOf(false) }

    // Баннер не гаснет по таймеру: раньше исчезал через 3 с, и до кнопки
    // «Загрузить» пользователь не успевал прочитать, что пресет применился.
    // Держится до следующего действия -- любая операция перезаписывает
    // сообщение своей, а уход со страницы убирает его вместе с состоянием.

    PageGroup(tr(Str.SETTINGS_PRESETS)) {
        message.value?.let { text ->
            ZappBanner(
                title = tr(text),
                state = if (failure) ComponentColors.State.DANGER else ComponentColors.State.ACTIVE
            )
        }
        ZappCard {
            if (state.presets.isEmpty()) {
                ZappEmptyCard(title = tr(Str.LIST_EMPTY_PRESETS))
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

            // Галочка выключена по умолчанию: без неё файл пресета
            // несёт настройки, а не ключи тысячи узлов. Подпись показывает,
            // сколько именно коннектов уйдёт внутрь, если включить.
            ZappSettingRow(
                title = tr(Str.PRESET_INCLUDE_PROFILES),
                subtitle = tr(Str.PRESET_RESET_PROFILES)
                    .format(state.config.vpn.profiles.size),
                checked = includeProfiles,
                onCheckedChange = { includeProfiles = it },
                path = "settings.presets.includeProfiles"
            )
            ZappButtonRow(modifier = Modifier.fillMaxWidth()) {
                ZappButton(
                    text = tr(Str.COMMON_EXPORT),
                    role = ButtonRole.SECONDARY,
                    onClick = {
                        scope.launch {
                            val result = state.exportPreset(includeProfiles)
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
                onClick = { confirmReset = true },
                modifier = Modifier.fillMaxWidth()
            )
        }
    }

    // Сброс одним тапом стирал регион, приветственный тур, все профили,
    // домены, kill switch, правила по приложениям и статистику -- разом и
    // без единого слова. Теперь сначала перечисление того, что
    // исчезнет, и только потом необратимое действие.
    if (confirmReset) {
        ZappDialog(
            title = tr(Str.PRESET_RESET_TITLE),
            body = tr(Str.PRESET_RESET_KEEP),
            bullets = listOf(
                tr(Str.PRESET_RESET_REGION),
                tr(Str.PRESET_RESET_PROFILES).format(state.config.vpn.profiles.size),
                tr(Str.PRESET_RESET_POLICY),
                tr(Str.PRESET_RESET_USAGE)
            ),
            confirmText = tr(Str.COMMON_RESET),
            cancelText = tr(Str.COMMON_CANCEL),
            danger = true,
            path = "settings.presets.reset",
            onConfirm = {
                confirmReset = false
                scope.launch {
                    state.resetToDefaults()
                    message.value = Str.ERR_PRESET_APPLIED
                    failure = false
                }
            },
            onDismiss = { confirmReset = false }
        )
    }
}

/** Бэкап: экспорт, импорт с превью, выбор режима восстановления. */
@Composable
private fun BackupSection(state: AppState) {
    val scope = rememberCoroutineScope()
    val message = remember { mutableStateOf<Str?>(null) }
    var failure by rememberSaveable { mutableStateOf(false) }
    var mode by rememberSaveable { mutableStateOf(RestoreMode.FULL.name) }
    val pending = state.pendingBackup

    PageGroup(tr(Str.SETTINGS_BACKUP)) {
        message.value?.let { text ->
            ZappBanner(
                title = tr(text),
                state = if (failure) ComponentColors.State.DANGER else ComponentColors.State.ACTIVE
            )
        }
        ZappCard {
            Text(
                text = tr(Str.SETTINGS_BACKUP_HINT),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(bottom = 12.dp)
            )
            ZappButtonRow(modifier = Modifier.fillMaxWidth()) {
                ZappButton(
                    text = tr(Str.SETTINGS_BACKUP_EXPORT),
                    role = ButtonRole.SECONDARY,
                    onClick = {
                        scope.launch {
                            val result = state.exportBackup()
                            message.value = result.key()
                            failure = !result.ok
                        }
                    },
                    modifier = Modifier.weight(1f)
                )
                ZappButton(
                    text = tr(Str.SETTINGS_BACKUP_IMPORT),
                    role = ButtonRole.SECONDARY,
                    onClick = {
                        scope.launch {
                            val result = state.importBackupPreview()
                            message.value = result.key()
                            failure = !result.ok
                        }
                    },
                    modifier = Modifier.weight(1f)
                )
            }
        }

        if (pending != null) {
            ZappCard {
                Text(
                    text = tr(Str.SETTINGS_BACKUP_PREVIEW),
                    style = MaterialTheme.typography.titleSmall,
                    modifier = Modifier.padding(bottom = 8.dp)
                )
                val manifest = pending.manifest
                Text(
                    text = "${manifest.appVersion} · ${manifest.profileNames.size} · ${manifest.presetNames.size}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(bottom = 12.dp)
                )
                ZappChoiceRow(
                    label = tr(Str.SETTINGS_BACKUP_MODE),
                    options = listOf(
                        Choice(RestoreMode.FULL, tr(Str.SETTINGS_BACKUP_MODE_FULL)),
                        Choice(RestoreMode.CONFIG, tr(Str.SETTINGS_BACKUP_MODE_CONFIG)),
                        Choice(RestoreMode.PRESETS, tr(Str.SETTINGS_BACKUP_MODE_PRESETS))
                    ),
                    selected = RestoreMode.valueOf(mode),
                    onSelect = { mode = it.name },
                    path = "settings.backup.mode"
                )
                ZappButtonRow(modifier = Modifier.fillMaxWidth()) {
                    ZappButton(
                        text = tr(Str.SETTINGS_BACKUP_APPLY),
                        role = ButtonRole.PRIMARY,
                        onClick = {
                            state.applyBackup(RestoreMode.valueOf(mode))
                            message.value = Str.ERR_PRESET_APPLIED
                            failure = false
                        },
                        modifier = Modifier.weight(1f)
                    )
                    ZappButton(
                        text = tr(Str.SETTINGS_BACKUP_CANCEL),
                        role = ButtonRole.GHOST,
                        onClick = { state.cancelBackup() },
                        modifier = Modifier.weight(1f)
                    )
                }
            }
        }

        PageGroup(tr(Str.SETTINGS_BACKUP_DAV)) {
            ZappCard {
                Text(
                    text = tr(Str.SETTINGS_BACKUP_DAV_HINT),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(bottom = 12.dp)
                )
                val dav = state.webDavConfig
                var davUrl by rememberSaveable { mutableStateOf(dav.url) }
                var davLogin by rememberSaveable { mutableStateOf(dav.login) }
                var davPass by rememberSaveable { mutableStateOf("") }
                ZappTextField(
                    value = davUrl,
                    onValueChange = { davUrl = it },
                    label = tr(Str.SETTINGS_BACKUP_DAV_URL),
                    path = "settings.backup.dav.url"
                )
                ZappTextField(
                    value = davLogin,
                    onValueChange = { davLogin = it },
                    label = tr(Str.SETTINGS_BACKUP_DAV_LOGIN),
                    path = "settings.backup.dav.login"
                )
                ZappTextField(
                    value = davPass,
                    onValueChange = { davPass = it },
                    label = tr(Str.SETTINGS_BACKUP_DAV_PASS),
                    path = "settings.backup.dav.pass"
                )
                ZappButtonRow(modifier = Modifier.fillMaxWidth()) {
                    ZappButton(
                        text = tr(Str.SETTINGS_BACKUP_DAV_SAVE),
                        role = ButtonRole.SECONDARY,
                        onClick = { state.saveWebDav(davUrl, davLogin, davPass) },
                        modifier = Modifier.weight(1f)
                    )
                    ZappButton(
                        text = tr(Str.SETTINGS_BACKUP_DAV_UPLOAD),
                        role = ButtonRole.SECONDARY,
                        onClick = {
                            scope.launch {
                                state.saveWebDav(davUrl, davLogin, davPass)
                                val result = state.webDavUpload()
                                message.value = result.key()
                                failure = !result.ok
                            }
                        },
                        modifier = Modifier.weight(1f)
                    )
                }
                ZappButton(
                    text = tr(Str.SETTINGS_BACKUP_DAV_DOWNLOAD),
                    role = ButtonRole.SECONDARY,
                    onClick = {
                        scope.launch {
                            state.saveWebDav(davUrl, davLogin, davPass)
                            val result = state.webDavDownload()
                            message.value = result.key()
                            failure = !result.ok
                        }
                    },
                    modifier = Modifier.fillMaxWidth()
                )
            }
        }
    }
}

/**
 * Поведение туннеля: kill switch, DNS при kill switch, автозапуск.
 *
 * Строки жили в редакторе профиля, где ими пользовались разве что
 * наткнувшись на них среди полей узла: переключатели-то свойства всего
 * туннеля, а не конкретного коннекта. Крышка не трогается во время
 * сессии -- смена kill switch на ходу означала бы мгновенный сброс
 * маршрутов под ногами идущего establish, и до переподключения видна
 * подсказка «Применится при следующем подключении».
 */
@Composable
private fun VpnBehaviorGroup(state: AppState) {
    val vpn = state.config.vpn
    val locks = settingLocks(state)
    val tunnelLive = state.tunnel.state.collectAsState().value.isLive
    PageGroup(tr(Str.VPN_BEHAVIOR)) {
        ZappCard {
            ZappSettingRow(
                title = tr(Str.VPN_PROXY_ONLY),
                subtitle = tr(Str.VPN_PROXY_ONLY_HINT),
                checked = vpn.proxyOnly,
                enabled = !tunnelLive,
                onCheckedChange = { on ->
                    state.mutate { it.copy(vpn = it.vpn.copy(proxyOnly = on)) }
                },
                path = "settings.vpn.proxy_only"
            )
            ZappSettingRow(
                title = tr(Str.VPN_KILL_SWITCH),
                checked = vpn.killSwitch,
                subtitle = if (vpn.proxyOnly) tr(Str.VPN_PROXY_ONLY_HINT)
                else if (tunnelLive) tr(Str.PERAPP_NEXT_CONNECT)
                else lockReason(locks, SettingKeys.KILL_SWITCH),
                enabled = !isLocked(locks, SettingKeys.KILL_SWITCH) && !tunnelLive && !vpn.proxyOnly,
                onCheckedChange = { on ->
                    state.mutate { it.copy(vpn = it.vpn.copy(killSwitch = on)) }
                },
                path = "settings.vpn.kill_switch"
            )
            ZappSettingRow(
                title = tr(Str.VPN_KILL_SWITCH_DNS),
                checked = vpn.killSwitchDns,
                enabled = vpn.killSwitch && !tunnelLive && !vpn.proxyOnly,
                onCheckedChange = { on ->
                    state.mutate { it.copy(vpn = it.vpn.copy(killSwitchDns = on)) }
                },
                path = "settings.vpn.kill_switch_dns"
            )
            ZappSettingRow(
                title = tr(Str.VPN_AUTOSTART),
                checked = vpn.autoStart,
                onCheckedChange = { on ->
                    state.mutate { it.copy(vpn = it.vpn.copy(autoStart = on)) }
                },
                path = "settings.vpn.autostart"
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
    VpnBehaviorGroup(state)
    PageGroup(tr(Str.VPN_ROUTING)) {
        VpnRoutingCard(state)
    }
    PageGroup(tr(Str.ROUTING_SECTIONS)) {
        RouteSectionsCard(state)
        RouteDetectorCard(state)
    }
    PageGroup(tr(Str.ROUTING_CASCADES)) {
        RouteCascadesCard(state)
    }
    PageGroup(tr(Str.ROUTING_URLTEST)) {
        UrlTestFilterCard(state)
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
                ZappTextField(
                    label = "--dpi-desync-autottl",
                    value = config.autoTtl,
                    onValueChange = { v -> state.mutate { it.copy(zapret = it.zapret.copy(autoTtl = v)) } },
                    placeholder = "3:4-10"
                )
                ZappNumberField(
                    label = "--dpi-desync-udplen-increment",
                    value = config.udpLenIncrement,
                    onValueChange = { v ->
                        state.mutate { it.copy(zapret = it.zapret.copy(udpLenIncrement = v)) }
                    },
                    placeholder = "0"
                )
                ZappSettingRow(
                    title = "--dpi-desync-any-protocol",
                    checked = config.anyProtocol,
                    onCheckedChange = { on ->
                        state.mutate { it.copy(zapret = it.zapret.copy(anyProtocol = on)) }
                    }
                )
            }

            // Моды fake ClientHello работают при любом семействе, где есть
            // подмена TLS: поле общей зоны, не привязано к семейству.
            ZappTextField(
                label = "--dpi-desync-fake-tls-mod",
                value = config.fakeTlsMod,
                onValueChange = { v -> state.mutate { it.copy(zapret = it.zapret.copy(fakeTlsMod = v)) } },
                placeholder = "rnd,rndsni,dupsid"
            )

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

            // TTL, фрагментация и фейки: техники, которые есть в нашем
            // бинаре. Подписи -- сами флаги, как в строке команды ниже.
            ZappTextField(
                label = "--auto-ttl",
                value = config.autoTtl,
                onValueChange = { v -> state.mutate { it.copy(goodbyeDpi = it.goodbyeDpi.copy(autoTtl = v)) } },
                placeholder = "3-8-10"
            )
            ZappNumberField(
                label = "--set-ttl",
                value = config.setTtl,
                onValueChange = { v -> state.mutate { it.copy(goodbyeDpi = it.goodbyeDpi.copy(setTtl = v)) } },
                placeholder = "5"
            )
            ZappNumberField(
                label = "--fake-gen",
                value = config.fakeGen,
                onValueChange = { v -> state.mutate { it.copy(goodbyeDpi = it.goodbyeDpi.copy(fakeGen = v)) } },
                placeholder = "3"
            )
            ZappNumberField(
                label = "--fake-resend",
                value = config.fakeResend,
                onValueChange = { v -> state.mutate { it.copy(goodbyeDpi = it.goodbyeDpi.copy(fakeResend = v)) } },
                placeholder = "2"
            )
            ZappNumberField(
                label = "--max-payload",
                value = config.maxPayload,
                onValueChange = { v -> state.mutate { it.copy(goodbyeDpi = it.goodbyeDpi.copy(maxPayload = v)) } },
                placeholder = "1400"
            )
            ZappSettingRow(
                title = "--reverse-frag",
                checked = config.reverseFrag,
                onCheckedChange = { on ->
                    state.mutate { it.copy(goodbyeDpi = it.goodbyeDpi.copy(reverseFrag = on)) }
                }
            )
            ZappSettingRow(
                title = "--frag-by-sni",
                checked = config.fragBySni,
                onCheckedChange = { on ->
                    state.mutate { it.copy(goodbyeDpi = it.goodbyeDpi.copy(fragBySni = on)) }
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
private fun ComponentsSection() {
    val scope = rememberCoroutineScope()

    // Версии настоящие, а не версия приложения: каждый бинарь отвечает
    // сам. `--version` -- это короткий запуск процесса, поэтому проба
    // одна, при открытии окна; не распакованный компонент -- тире.
    var versions by remember {
        mutableStateOf(ZappComponent.entries.map { it.title to "..." })
    }
    LaunchedEffect(Unit) {
        versions = ZappComponent.entries.map { component ->
            component.title to (probeComponentVersion(component) ?: "-")
        }
    }

    PageGroup(tr(Str.COMPONENTS)) {
        ZappCard {
            versions.forEach { (name, version) ->
                InfoRow(label = name, value = version)
            }
            ZappButton(
                text = tr(Str.COMPONENTS_REINSTALL),
                role = ButtonRole.SECONDARY,
                onClick = { scope.launch { NativeBinaries.reinstall() } }
            )
        }
    }
}

// --- Журнал: ошибки и детальный лог с тумблером ---

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

        ErrorLogCard(state)
        NetCheckCard(state)
        ZappCard {
            if (lines.isEmpty()) {
                ZappEmptyCard(title = tr(Str.LOG_EMPTY))
            } else {
                // Хвост ленты: новые записи внизу, экран показывает последние.
                lines.takeLast(100).forEach { line ->
                    // Ошибка -- цветом, чтобы её было видно с ходу.
                    // Признаки общие с журналами демонов (core.log).
                    val isError = looksLikeErrorLine(line)
                    Text(
                        // Окраску снимаем: в строке лежит ANSI из stderr, а
                        // цвет задаём сами по уровню -- дубль не нужен.
                        text = stripAnsi(line),
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

/**
 * Журнал ошибок: ровно то, что нужно для багрепорта.
 *
 * Стоит отдельной карточкой, а не фильтром общего лога: причины сбоев
 * должны читаться одним списком, без строк «connect requested» между
 * ними. Пустое состояние -- не «ничего не записано», а «сбоев не было»,
 * поэтому строка своя и обнадёживающая.
 */
@Composable
private fun ErrorLogCard(state: AppState) {
    val errors by ErrorLog.lines.collectAsState()
    val scope = rememberCoroutineScope()
    ZappCard {
        ZappListRow(
            title = tr(Str.LOG_ERRORS),
            subtitle = tr(Str.LOG_ERRORS_FILE_HINT),
            trailing = if (errors.isEmpty()) null else {
                {
                    Text(
                        text = errors.size.toString(),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.error
                    )
                }
            }
        )
        if (errors.isEmpty()) {
            ZappEmptyCard(title = tr(Str.LOG_ERRORS_EMPTY))
        } else {
            // Только хвост: журнал и так ограничен, а экран -- про последние.
            errors.takeLast(50).forEach { line ->
                Text(
                    text = stripAnsi(line),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.fillMaxWidth()
                )
            }
            ZappButton(
                text = tr(Str.LOG_ERRORS_CLEAR),
                role = ButtonRole.DANGER,
                onClick = { ErrorLog.clear() },
                modifier = Modifier.fillMaxWidth()
            )
        }
        ZappButton(
            text = tr(Str.LOG_EXPORT),
            role = ButtonRole.SECONDARY,
            onClick = { scope.launch { state.exportDiagnostics() } },
            modifier = Modifier.fillMaxWidth()
        )
    }
}

/**
 * Сканер задержки до узлов: параллельные пробы и самый быстрый узел.
 *
 * Пустое состояние -- «ещё не проверялось», а не «узлов нет»: список
 * узлов задан по умолчанию, поэтому без пробы показывать нечего.
 */
@Composable
private fun NetCheckCard(state: AppState) {
    val results by state.netCheckResults.collectAsState()
    val running by state.netCheckRunning.collectAsState()
    val scope = rememberCoroutineScope()
    ZappCard {
        ZappListRow(
            title = tr(Str.NET_CHECK),
            subtitle = state.netCheckBest?.let { "${it.node.label} · ${it.latencyMs} ms" }
                ?: tr(Str.NET_CHECK_EMPTY)
        )
        if (results.isEmpty()) {
            ZappEmptyCard(title = tr(Str.NET_CHECK_EMPTY))
        } else {
            results.sortedBy { it.latencyMs ?: Int.MAX_VALUE }.forEach { r ->
                val mark = if (r === state.netCheckBest) "★ " else ""
                val tail = when {
                    r.latencyMs != null -> "${r.latencyMs} ms"
                    r.alive -> "—"
                    else -> r.error.ifEmpty { "?" }
                }
                Text(
                    text = "$mark${r.node.label} · $tail",
                    style = MaterialTheme.typography.labelSmall,
                    modifier = Modifier.fillMaxWidth()
                )
            }
        }
        ZappButton(
            text = tr(if (running) Str.NET_CHECK_RUNNING else Str.NET_CHECK_RUN),
            role = ButtonRole.SECONDARY,
            enabled = !running,
            onClick = { scope.launch { state.runNetCheck() } },
            modifier = Modifier.fillMaxWidth()
        )
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

/**
 * «О программе».
 *
 * Было две строки -- версия и платформа, и багрепорт всё равно
 * начинался с «проверь, что у тебя стоит». Теперь здесь же сборка
 * (root-вариант или стандартный), лицензия, сведения об устройстве и
 * проверка обновлений -- не надо уходить для этого в «Обновления».
 */
@Composable
private fun AboutSection(state: AppState) {
    val probe by state.probe.collectAsState()
    val updateState by state.updateState.collectAsState()
    PageGroup(tr(Str.SETTINGS_ABOUT)) {
        ZappCard {
            InfoRow(tr(Str.ABOUT_VERSION), state.appVersion)
            InfoRow(tr(Str.ABOUT_PLATFORM), platformName(state))
            // Метка root-сборки рисуется только когда BuildMode.rootAllowed:
            // норут обязан не упоминать root ни одной видимой строкой.
            InfoRow(
                tr(Str.ABOUT_BUILD),
                tr(if (BuildMode.rootAllowed) Str.ABOUT_BUILD_ROOT else Str.ABOUT_BUILD_PLAIN)
            )
            InfoRow(tr(Str.ABOUT_LICENSE), tr(Str.ABOUT_LICENSE_NAME))
        }
        ZappCard {
            InfoRow(tr(Str.PROBE_SYSTEM), probe?.system.orDash())
            InfoRow(tr(Str.PROBE_API), probe?.apiLevel?.takeIf { it > 0 }?.toString().orDash())
            InfoRow(tr(Str.UPD_CHECK), tr(updateState.labelKey()))
            ZappButton(
                text = tr(Str.UPD_CHECK),
                role = ButtonRole.SECONDARY,
                enabled = updateState != UpdateState.CHECKING,
                onClick = { state.checkForUpdates() },
                path = "settings.about.check_updates"
            )
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
            .padding(AzraelSpace.xs),
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
fun PermissionRow(status: PermissionStatus, onAction: () -> Unit, path: String) {
    val granted = status.state == PermissionState.GRANTED
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .debugPath(path)
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
                onClick = onAction,
                path = "$path.button"
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

/** Подпись режима темы. */
@Composable
private fun themeLabel(mode: ThemeMode): String = when (mode) {
    ThemeMode.SYSTEM -> tr(Str.SETTINGS_THEME_SYSTEM)
    ThemeMode.DARK -> tr(Str.SETTINGS_THEME_DARK)
    ThemeMode.LIGHT -> tr(Str.SETTINGS_THEME_LIGHT)
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
