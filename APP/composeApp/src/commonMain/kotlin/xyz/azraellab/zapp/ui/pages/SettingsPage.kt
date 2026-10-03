package xyz.azraellab.zapp.ui.pages

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.runtime.saveable.rememberSaveable
import kotlinx.coroutines.launch
import xyz.azraellab.zapp.core.AppConfig
import xyz.azraellab.zapp.core.AppLang
import androidx.compose.runtime.collectAsState
import xyz.azraellab.zapp.core.AppState
import xyz.azraellab.zapp.core.Str
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
import xyz.azraellab.zapp.ui.components.ZappSectionTitle
import xyz.azraellab.zapp.ui.components.ZappNumberField
import xyz.azraellab.zapp.ui.components.ZappSettingRow
import xyz.azraellab.zapp.ui.components.ZappTextField
import xyz.azraellab.zapp.ui.tr

/**
 * Страница настроек.
 *
 * Здесь только то, что не имеет собственного раздела: язык, мониторинг
 * трафика, пресеты и справка. VPN, обход DPI и GPS живут на своих вкладках,
 * и дублировать их поля здесь незачем.
 */
@Composable
fun SettingsPage(state: AppState) {
    val scope = rememberCoroutineScope()
    val message = remember { mutableStateOf<Str?>(null) }
    var failure by rememberSaveable { mutableStateOf(false) }

    // Баннер гаснет сам: сообщение о выгрузке пресета -- это событие, а не
    // состояние, и висеть вечно ему незачем.
    LaunchedEffect(message.value) {
        if (message.value != null) {
            kotlinx.coroutines.delay(3000)
            message.value = null
        }
    }

    PageScaffold(title = tr(Str.TAB_SETTINGS)) {
        message.value?.let { text ->
            ZappBanner(
                title = tr(text),
                state = if (failure) ComponentColors.State.DANGER else ComponentColors.State.ACTIVE
            )
        }

        // --- Язык ---
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

        // --- Разрешения ---
        PageGroup(tr(Str.SETTINGS_PERMISSIONS)) {
            val statuses by state.permissions.statuses.collectAsState()
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

        // --- Устройство ---
        PageGroup(tr(Str.SETTINGS_DEVICE)) {
            val probe by state.probe.collectAsState()
            ZappCard {
                InfoRow(tr(Str.PROBE_MODEL), probe?.model.orDash())
                InfoRow(tr(Str.PROBE_SYSTEM), probe?.system.orDash())
                InfoRow(tr(Str.PROBE_KERNEL), probe?.kernel.orDash())
                InfoRow(
                    tr(Str.PROBE_API),
                    probe?.apiLevel?.takeIf { it > 0 }?.toString().orDash()
                )
                InfoRow(tr(Str.PROBE_ROOT), tr((probe?.root
                    ?: xyz.azraellab.zapp.core.probe.Probe.UNKNOWN).label))
                InfoRow(tr(Str.PROBE_MAGISK), tr((probe?.magisk
                    ?: xyz.azraellab.zapp.core.probe.Probe.UNKNOWN).label))
                InfoRow(tr(Str.PROBE_VPN), tr((probe?.vpnTunnel
                    ?: xyz.azraellab.zapp.core.probe.Probe.UNKNOWN).label))
            }
        }

        // --- Обновления ---
        PageGroup(tr(Str.SETTINGS_UPDATES)) {
            val updateState by state.updateState.collectAsState()
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

        // --- Мониторинг трафика ---
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

        // --- Пресеты ---
        PageGroup(tr(Str.SETTINGS_PRESETS)) {
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

        // --- VPN: мелочи, которые не нужны на основном экране ---
        val vpn = state.config.vpn
        PageGroup(tr(Str.TAB_VPN)) {
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

        // --- Компоненты: распакованные нативные бинари ---
        val installed = NativeBinaries.installed()
        if (installed.isNotEmpty()) {
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

        // --- Справка ---
        PageGroup(tr(Str.SETTINGS_ABOUT)) {
            ZappCard {
                InfoRow(label = tr(Str.ABOUT_VERSION), value = "0.1.0")
                InfoRow(label = tr(Str.ABOUT_PLATFORM), value = platformName(state))
            }
        }
    }
}

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
private fun PermissionRow(status: PermissionStatus, onAction: () -> Unit) {
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
        // На «через настройки» нужна своя кнопка: диалога не будет,
        // и без перехода пользователь застрянет на этом состоянии.
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
