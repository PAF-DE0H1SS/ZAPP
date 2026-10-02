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
import xyz.azraellab.zapp.core.AppState
import xyz.azraellab.zapp.core.Str
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
import xyz.azraellab.zapp.ui.components.ZappSettingRow
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

/** Название языка на его собственном языке. */
private fun labelOf(lang: AppLang): String = when (lang) {
    AppLang.EN -> "English"
    AppLang.RU -> "Русский"
    AppLang.ZH -> "中文"
}
