package xyz.azraellab.zapp.ui.pages

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import xyz.azraellab.zapp.core.AppState
import xyz.azraellab.zapp.core.LinkSpeedBus
import xyz.azraellab.zapp.core.Str
import xyz.azraellab.zapp.core.toDisplayString
import xyz.azraellab.zapp.core.TorBridges
import xyz.azraellab.zapp.core.VpnConnects
import xyz.azraellab.zapp.core.VpnProtocol
import xyz.azraellab.zapp.core.engine.TunnelState
import xyz.azraellab.zapp.ui.components.ComponentColors.ButtonRole
import xyz.azraellab.zapp.ui.components.Choice
import xyz.azraellab.zapp.ui.components.ZappButton
import xyz.azraellab.zapp.ui.components.ZappCard
import xyz.azraellab.zapp.ui.components.ZappChoiceRow
import xyz.azraellab.zapp.ui.components.ZappEmptyState
import xyz.azraellab.zapp.ui.components.ZappExpandableCard
import xyz.azraellab.zapp.ui.components.ZappListRow
import xyz.azraellab.zapp.ui.theme.AzraelSpace
import xyz.azraellab.zapp.ui.tr

/**
 * Страница Tor.
 *
 * Tor живёт отдельной вкладкой до VPN: это самостоятельный способ выйти
 * в сеть, а не пункт списка коннектов -- мост выбирается здесь, статус
 * туннеля виден здесь, и переход на вкладку VPN не нужен, чтобы понять,
 * работает ли Tor. Страница отвечает на четыре вопроса: поднят ли
 * туннель, каким мостом, живы ли мосты и какой SOCKS-порт открыт.
 *
 * Логика подключения -- `AppState.connectTor()`: она сама выбирает
 * активный мост и снимает чужое подключение, если оно есть; здесь только
 * вызов и отображение. Список -- чистые функции [VpnConnects], покрытые
 * тестами.
 */
@Composable
fun TorPage(state: AppState) {
    val tunnelState by state.tunnel.state.collectAsState()
    val tunnelLog by state.tunnel.log.collectAsState()
    val checkProgress by state.checkProgress.collectAsState()

    PageScaffold(title = tr(Str.TAB_TOR)) {
        // --- Как это работает: одна строка, всегда на виду ---
        ZappCard {
            Text(
                text = tr(Str.TOR_PAGE_HINT),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        // --- Туннель: статус и кнопка ---
        if (state.tunnel.supported) {
            ZappCard {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(AzraelSpace.md),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = tr(tunnelState.labelKey()),
                        style = MaterialTheme.typography.titleMedium,
                        color = when (tunnelState) {
                            TunnelState.CONNECTED -> MaterialTheme.colorScheme.primary
                            TunnelState.ERROR -> MaterialTheme.colorScheme.error
                            TunnelState.CONNECTING -> MaterialTheme.colorScheme.tertiary
                            TunnelState.DISCONNECTED -> MaterialTheme.colorScheme.onSurface
                        },
                        modifier = Modifier.weight(1f)
                    )
                    if (tunnelState == TunnelState.CONNECTING || tunnelState == TunnelState.CONNECTED) {
                        ZappButton(
                            text = tr(Str.COMMON_STOP),
                            role = ButtonRole.DANGER,
                            onClick = { state.disconnectVpn() }
                        )
                    } else {
                        ZappButton(
                            text = tr(Str.COMMON_START),
                            onClick = { state.connectTor() }
                        )
                    }
                }
                if (tunnelState == TunnelState.ERROR) {
                    val reason = tunnelLog.lastOrNull { it.startsWith("error") || it.startsWith("box failed") }
                        ?: tunnelLog.lastOrNull()
                    if (reason != null) {
                        Text(
                            text = reason,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error
                        )
                    }
                }
                // Чем именно подключены: под статусом, чтобы не гадать.
                state.config.vpn.activeProfile()?.let { active ->
                    if (active.protocol == VpnProtocol.TOR.code) {
                        Text(
                            text = tr(Str.TOR_ACTIVE_HINT),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
                // Скорость туннеля и активные режимы: живут, пока
                // подключены, обновляются раз в секунду отдельным опросом
                // счётчиков. Режимы -- только работающие.
                val linkSpeed by LinkSpeedBus.current.collectAsState()
                val modes = activeModes(state, tunnelState)
                val speedLine = when {
                    tunnelState == TunnelState.CONNECTED && modes.isNotEmpty() ->
                        (linkSpeed?.toDisplayString() ?: "↓ …  ↑ …") + "  ($modes)"
                    tunnelState == TunnelState.CONNECTED ->
                        linkSpeed?.toDisplayString() ?: "↓ …  ↑ …"
                    modes.isNotEmpty() -> "($modes)"
                    else -> ""
                }
                if (speedLine.isNotEmpty()) {
                    Text(
                        text = speedLine,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.primary
                    )
                }
            }
        }

        // --- Мосты: выбор и проверка ---
        BridgesExpandable(state, checkProgress)

        // --- Источники мостов: только подписки с kind == "tor" ---
        SourcesExpandable(
            state = state,
            title = Str.TOR_SOURCES,
            kind = "tor",
            sources = remember(state.config.vpn.sources) {
                VpnConnects.sourcesByKind(state.config.vpn.sources, "tor")
            }
        )

        // --- Порт: только показ, само число живёт в Настройках > Tor ---
        ZappCard {
            ZappListRow(
                title = tr(Str.VPN_TOR_SOCKS),
                subtitle = "127.0.0.1:${state.config.vpn.torSocksPort}"
            )
        }

        // --- Журнал туннеля ---
        JournalExpandable(tunnelLog)
    }
}

/**
 * Мосты: сам список мостов активного источника.
 *
 * Пользователь пришёл за мостами, а не за файлами подписок, поэтому в
 * развёрнутом виде показываются разобранные адреса ([TorBridges.parse]):
 * транспорт и endpoint. Источники остаются выбором чипами (их мало,
 * при этом именно они определяют, куда уйдёт трафик при СТАРТ), а
 * «Проверить все» гоняет пинг по выбранным источникам.
 *
 * Мёртвые мосты тоже здесь: архив VPN стал чисто VPN-списком, и связь
 * «проверили, отказал» не должна пропасть -- она видна в подписи
 * источника. Пагинация та же, что на VPN: страница из настроек
 * производительности.
 */
@Composable
private fun BridgesExpandable(state: AppState, checkProgress: AppState.CheckProgress) {
    val config = state.config.vpn
    val pageSize = state.config.perf.sanitized().listPageSize

    var limit by rememberSaveable(pageSize) { mutableIntStateOf(pageSize) }
    // "" -- пользователь ещё не выбирал: активным считается то, чем
    // подключены (activeProfileId).
    var selectedId by rememberSaveable { mutableStateOf("") }

    val sources = remember(config.profiles) { VpnConnects.torBridges(config.profiles) }
    val source = sources.firstOrNull { it.id == selectedId }
        ?: sources.firstOrNull { it.id == config.activeProfileId }
        ?: sources.firstOrNull()
    // Смена источника = смена активного профиля: и подключение, и подпись
    // под статусом смотрят на activeProfileId.
    val pick: (String) -> Unit = { id ->
        selectedId = id
        state.selectProfile(id)
    }

    val bridges = remember(source) { source?.let { TorBridges.parse(it.rawConfig) }.orEmpty() }
    val shown = bridges.take(limit)

    ZappExpandableCard(
        title = tr(Str.TOR_BRIDGES),
        badge = if (bridges.isEmpty()) null else bridges.size.toString(),
        subtitle = source?.name?.takeIf { it.isNotBlank() }
    ) {
        if (sources.size > 1) {
            ZappChoiceRow(
                label = tr(Str.TOR_BRIDGE_SOURCE),
                options = sources.map { profile ->
                    val count = remember(profile) { TorBridges.parse(profile.rawConfig).size }
                    val dead = profile.health.alive == false
                    Choice(
                        profile.id,
                        "${profile.name.ifBlank { profile.id }} ($count)" +
                            if (dead) " · ${tr(Str.VPN_STATUS_DEAD)}" else ""
                    )
                },
                selected = source?.id,
                onSelect = { id -> if (id != null) pick(id) }
            )
        }
        if (sources.isNotEmpty()) {
            ZappButton(
                text = if (checkProgress.running) {
                    tr(Str.VPN_CHECKING) + " ${checkProgress.done}/${checkProgress.total}"
                } else {
                    tr(Str.VPN_CHECK_ALL)
                },
                enabled = !checkProgress.running,
                onClick = { state.checkLinks(sources.map { it.id }) },
                modifier = Modifier.fillMaxWidth()
            )
        }

        if (shown.isEmpty()) {
            ZappCard {
                ZappEmptyState(title = tr(Str.TOR_NO_BRIDGES))
            }
        } else {
            ZappCard {
                shown.forEach { bridge ->
                    ZappListRow(
                        title = bridge.endpoint,
                        subtitle = bridge.transport.ifBlank { null }
                    )
                }
            }
            if (shown.size < bridges.size) {
                ZappButton(
                    text = tr(Str.VPN_SHOW_MORE) + " (${bridges.size - shown.size})",
                    role = ButtonRole.GHOST,
                    onClick = { limit += pageSize },
                    modifier = Modifier.fillMaxWidth()
                )
            }
        }
    }
}
