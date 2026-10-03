package xyz.azraellab.zapp.ui.pages

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import kotlinx.coroutines.launch
import xyz.azraellab.zapp.core.AppLang
import xyz.azraellab.zapp.core.AppState
import xyz.azraellab.zapp.core.Str
import xyz.azraellab.zapp.core.VpnProfile
import xyz.azraellab.zapp.core.VpnProtocol
import xyz.azraellab.zapp.core.VpnTransport
import xyz.azraellab.zapp.core.engine.TunnelState
import xyz.azraellab.zapp.ui.AppLangState
import xyz.azraellab.zapp.ui.components.Choice
import xyz.azraellab.zapp.ui.components.ComponentColors.ButtonRole
import xyz.azraellab.zapp.ui.components.ZappBanner
import xyz.azraellab.zapp.ui.components.ZappButton
import xyz.azraellab.zapp.ui.components.ZappCard
import xyz.azraellab.zapp.ui.components.ZappChoiceRow
import xyz.azraellab.zapp.ui.components.ZappEmptyState
import xyz.azraellab.zapp.ui.components.ZappListRow
import xyz.azraellab.zapp.ui.components.ZappNumberField
import xyz.azraellab.zapp.ui.components.ZappSettingRow
import xyz.azraellab.zapp.ui.components.ZappTextField
import xyz.azraellab.zapp.ui.theme.AzraelSpace
import xyz.azraellab.zapp.ui.tr

/**
 * Страница VPN.
 *
 * Три зоны по смыслу: сверху -- «работает ли туннель» и его режимы, в центре
 * -- живой список коннектов с пингом и источниками, снизу -- настройки
 * маршрутизации и журнал. Список отделён от редактора профиля: человек
 * выбирает коннект из сотен, а руками правит один -- смешивать их в одном
 * блоке значит не находить нужное.
 *
 * Мёртвые коннекты не пропадают молча: они уезжают в архив с причиной и
 * кнопкой перепроверки, потому что «список стал короче» -- плохая
 * обратная связь, а обрыв сети -- не повод терять настройки.
 */
@Composable
fun VpnPage(state: AppState) {
    val config = state.config.vpn
    val active = config.activeProfile()
    val tunnelState by state.tunnel.state.collectAsState()
    val tunnelLog by state.tunnel.log.collectAsState()
    val sourceStatus by state.sourceStatus.collectAsState()
    val checkProgress by state.checkProgress.collectAsState()

    PageScaffold(title = tr(Str.TAB_VPN)) {
        // --- Управление и состояние ---
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
                            onClick = { state.connectVpn() }
                        )
                    }
                }
            }

            // Причина ошибки -- сразу под статусом, а не только в журнале внизу.
            if (tunnelState == TunnelState.ERROR) {
                val reason = tunnelLog.lastOrNull { it.startsWith("error") || it.startsWith("box failed") }
                    ?: tunnelLog.lastOrNull()
                if (reason != null) {
                    ZappBanner(title = tr(Str.ERR_TUNNEL_ERROR), body = reason)
                }
            }

            // --- Режимы: до выбора коннекта, они про подключение в целом ---
            ZappCard {
                ZappSettingRow(
                    title = tr(Str.VPN_GROUP_MODE),
                    subtitle = tr(Str.VPN_ALIVE_COUNT),
                    checked = config.groupMode,
                    onCheckedChange = { on ->
                        state.mutate { it.copy(vpn = it.vpn.copy(groupMode = on)) }
                    }
                )
                ZappSettingRow(
                    title = tr(Str.VPN_TOR_ENABLED),
                    checked = config.torEnabled,
                    onCheckedChange = { on ->
                        state.mutate { it.copy(vpn = it.vpn.copy(torEnabled = on)) }
                    }
                )
                ZappSettingRow(
                    title = tr(Str.VPN_KILL_SWITCH),
                    checked = config.killSwitch,
                    onCheckedChange = { on ->
                        state.mutate { it.copy(vpn = it.vpn.copy(killSwitch = on)) }
                    }
                )
                ZappSettingRow(
                    title = tr(Str.VPN_AUTOSTART),
                    checked = config.autoStart,
                    onCheckedChange = { on ->
                        state.mutate { it.copy(vpn = it.vpn.copy(autoStart = on)) }
                    }
                )
            }
        } else {
            ZappCard {
                ZappEmptyState(title = tr(Str.ERR_TUNNEL_UNSUPPORTED))
            }
        }

        // --- Источники коннектов ---
        SourcesCard(state)

        // --- Сообщения импорта и обновлений ---
        val notice = state.vpnNotice
        if (notice != null) {
            ZappBanner(
                title = tr(notice.key),
                body = notice.arg.takeIf { it.isNotEmpty() },
                trailing = {
                    ZappButton(
                        text = tr(Str.COMMON_CLOSE),
                        role = ButtonRole.GHOST,
                        onClick = { state.clearVpnNotice() }
                    )
                }
            )
        }

        // --- Коннекты ---
        ConnectsCard(state, checkProgress)

        // --- Архив ---
        ArchiveCard(state)

        // --- Редактор выбранного профиля ---
        if (active != null) {
            PageGroup(tr(Str.VPN_PROFILES)) {
                VpnProfileCard(state = state)
            }
        }

        // --- Журнал: ответ бэкенда на подключение и снятие ---
        JournalCard(tunnelLog)

        // --- Маршруты и DNS ---
        PageGroup(tr(Str.SETTINGS_SYSTEM)) {
            ZappCard {
                ZappSettingRow(
                    title = tr(Str.VPN_ROUTES_ALL),
                    checked = config.routing.routesAllTraffic,
                    onCheckedChange = { on ->
                        state.mutate { it.copy(vpn = it.vpn.copy(routing = it.vpn.routing.copy(routesAllTraffic = on))) }
                    }
                )
                ZappSettingRow(
                    title = tr(Str.VPN_INTERCEPT_DNS),
                    subtitle = tr(Str.VPN_ROUTING_HINT_DNS),
                    checked = config.routing.interceptDns,
                    onCheckedChange = { on ->
                        state.mutate { it.copy(vpn = it.vpn.copy(routing = it.vpn.routing.copy(interceptDns = on))) }
                    }
                )
                ZappSettingRow(
                    title = tr(Str.VPN_BYPASS_LAN),
                    checked = config.routing.excludePrivateRanges,
                    onCheckedChange = { on ->
                        state.mutate {
                            it.copy(vpn = it.vpn.copy(routing = it.vpn.routing.copy(excludePrivateRanges = on)))
                        }
                    }
                )
                ZappSettingRow(
                    title = tr(Str.VPN_DNS_FAKE_IP),
                    checked = config.dnsFakeIp,
                    onCheckedChange = { on ->
                        state.mutate { it.copy(vpn = it.vpn.copy(dnsFakeIp = on)) }
                    }
                )
                ZappSettingRow(
                    title = tr(Str.VPN_DNS_VIA_PROXY),
                    checked = config.dnsViaProxy,
                    onCheckedChange = { on ->
                        state.mutate { it.copy(vpn = it.vpn.copy(dnsViaProxy = on)) }
                    }
                )
                ZappSettingRow(
                    title = tr(Str.VPN_SPLIT_TUNNELING),
                    checked = config.routing.allowPerAppBypass,
                    onCheckedChange = { on ->
                        state.mutate {
                            it.copy(vpn = it.vpn.copy(routing = it.vpn.routing.copy(allowPerAppBypass = on)))
                        }
                    }
                )
                if (config.routing.allowPerAppBypass) {
                    // Поле появляется только вместе с переключателем: пока
                    // обход приложений выключен, список исключений ни на что
                    // не влияет.
                    ZappTextField(
                        label = tr(Str.LIST_EXCLUSIONS),
                        value = config.routing.bypassApps,
                        onValueChange = { v ->
                            state.mutate {
                                it.copy(vpn = it.vpn.copy(routing = it.vpn.routing.copy(bypassApps = v)))
                            }
                        },
                        placeholder = "com.android.chrome"
                    )
                }
                ZappTextField(
                    label = tr(Str.VPN_DNS),
                    value = config.dnsServers,
                    onValueChange = { v -> state.mutate { it.copy(vpn = it.vpn.copy(dnsServers = v)) } },
                    placeholder = "1.1.1.1, 9.9.9.9"
                )
            }
        }
    }
}

/** Источники: список репозиториев, их состояние и добавление своего URL. */
@Composable
private fun SourcesCard(state: AppState) {
    val config = state.config.vpn
    val sourceStatus by state.sourceStatus.collectAsState()
    var adding by remember { mutableStateOf(false) }
    var newName by remember { mutableStateOf("") }
    var newUrl by remember { mutableStateOf("") }

    PageGroup(tr(Str.VPN_SOURCES)) {
        ZappButton(
            text = tr(Str.VPN_SOURCE_ADD),
            role = if (adding) ButtonRole.GHOST else ButtonRole.SECONDARY,
            onClick = { adding = !adding }
        )

        if (adding) {
            ZappCard {
                ZappTextField(
                    label = tr(Str.VPN_SOURCE_NAME),
                    value = newName,
                    onValueChange = { newName = it }
                )
                ZappTextField(
                    label = tr(Str.VPN_SOURCE_URL),
                    value = newUrl,
                    onValueChange = { newUrl = it },
                    placeholder = "https://example.com/sub.txt"
                )
                Row(horizontalArrangement = Arrangement.spacedBy(AzraelSpace.md)) {
                    ZappButton(
                        text = tr(Str.COMMON_APPLY),
                        onClick = {
                            if (state.addSource(newUrl, newName)) {
                                newName = ""
                                newUrl = ""
                                adding = false
                            }
                        }
                    )
                    ZappButton(
                        text = tr(Str.COMMON_CLOSE),
                        role = ButtonRole.GHOST,
                        onClick = { adding = false }
                    )
                }
            }
        }

        config.sources.forEach { source ->
            val status = sourceStatus[source.id]
            ZappCard {
                ZappSettingRow(
                    title = source.name,
                    subtitle = sourceSubtitle(status),
                    checked = source.enabled,
                    onCheckedChange = { on -> state.setSourceEnabled(source.id, on) }
                )
                if (source.enabled) {
                    Row(horizontalArrangement = Arrangement.spacedBy(AzraelSpace.md)) {
                        ZappButton(
                            text = tr(Str.VPN_SOURCE_REFRESH),
                            role = ButtonRole.GHOST,
                            onClick = { state.refreshSource(source.id) }
                        )
                        ZappButton(
                            text = tr(Str.COMMON_DELETE),
                            role = ButtonRole.GHOST,
                            onClick = { state.removeSource(source.id) }
                        )
                    }
                } else {
                    ZappButton(
                        text = tr(Str.COMMON_DELETE),
                        role = ButtonRole.GHOST,
                        onClick = { state.removeSource(source.id) }
                    )
                }
            }
        }
    }
}

/** Подпись состояния источника: загрузка, число коннектов или ошибка. */
@Composable
private fun sourceSubtitle(status: AppState.SourceStatus?): String {
    if (status == null) return ""
    return when (status.state) {
        AppState.SourceState.IDLE -> ""
        AppState.SourceState.LOADING -> tr(Str.VPN_CHECKING)
        AppState.SourceState.OK -> tr(Str.VPN_SOURCE_REFRESHED) + ": ${status.links}"
        AppState.SourceState.ERROR -> tr(Str.VPN_SOURCE_FAILED) + ": ${status.error}"
    }
}

/**
 * Список коннектов.
 *
 * Сортировка по пингу стоит первой и выбрана по умолчанию: список живых
 * узлов нужен именно для быстрого выбора, а по имени искать коннект,
 * который «просто работает», бессмысленно.
 */
@Composable
private fun ConnectsCard(state: AppState, checkProgress: AppState.CheckProgress) {
    val config = state.config.vpn
    val lang = AppLangState.current
    val scope = rememberCoroutineScope()
    var byPing by remember { mutableStateOf(true) }
    var importOpen by remember { mutableStateOf(false) }
    var importText by remember { mutableStateOf("") }

    val visible = state.visibleVpnProfiles()
    val sorted = if (byPing) {
        visible.sortedWith(
            compareBy(
                { if (it.health.alive == true) 0 else 1 },
                { if (it.health.latencyMs >= 0) it.health.latencyMs else Int.MAX_VALUE },
                { it.name }
            )
        )
    } else {
        visible.sortedBy { it.name }
    }

    PageGroup(tr(Str.VPN_PROFILES)) {
        if (checkProgress.running) {
            ZappBanner(
                title = tr(Str.VPN_CHECKING),
                body = "${checkProgress.done} / ${checkProgress.total}"
            )
        }

        Row(horizontalArrangement = Arrangement.spacedBy(AzraelSpace.md)) {
            ZappButton(
                text = if (checkProgress.running) {
                    tr(Str.VPN_CHECKING) + " ${checkProgress.done}/${checkProgress.total}"
                } else {
                    tr(Str.VPN_CHECK_ALL)
                },
                enabled = !checkProgress.running,
                onClick = { state.checkAllVisible() }
            )
            ZappButton(
                text = tr(Str.VPN_ADD_LINKS),
                role = ButtonRole.SECONDARY,
                onClick = { importOpen = !importOpen }
            )
            ZappButton(
                text = tr(Str.VPN_ADD_PROFILE),
                role = ButtonRole.SECONDARY,
                onClick = { addProfile(state) }
            )
        }

        ZappChoiceRow(
            label = tr(Str.VPN_SORT_PING),
            options = listOf(
                Choice(true, tr(Str.VPN_SORT_PING)),
                Choice(false, tr(Str.VPN_SORT_NAME))
            ),
            selected = byPing,
            onSelect = { byPing = it }
        )

        if (importOpen) {
            ZappCard {
                ZappTextField(
                    label = tr(Str.VPN_ADD_LINKS),
                    value = importText,
                    onValueChange = { importText = it },
                    singleLine = false,
                    minLines = 3,
                    placeholder = "vless://... \ntrojan://... \n[Interface] ..."
                )
                ZappButton(
                    text = tr(Str.COMMON_IMPORT),
                    onClick = {
                        val text = importText
                        importText = ""
                        importOpen = false
                        scope.launch { state.importVpnText(text) }
                    }
                )
            }
        }

        if (sorted.isEmpty()) {
            ZappCard {
                ZappEmptyState(
                    title = tr(Str.LIST_EMPTY_PROFILES),
                    body = tr(Str.VPN_SOURCE_EMPTY),
                    action = {
                        ZappButton(text = tr(Str.VPN_ADD_LINKS), onClick = { importOpen = true })
                    }
                )
            }
        } else {
            ZappCard {
                sorted.forEach { profile ->
                    val isActive = profile.id == config.activeProfileId
                    ConnectRow(
                        profile = profile,
                        active = isActive,
                        lang = lang,
                        onClick = { state.selectProfile(profile.id) }
                    )
                }
            }
        }
    }
}

/** Одна строка коннекта: имя, протокол и результат последней проверки. */
@Composable
private fun ConnectRow(
    profile: VpnProfile,
    active: Boolean,
    lang: AppLang,
    onClick: () -> Unit
) {
    val protocol = VpnProtocol.of(profile.protocol)
    val status = when (profile.health.alive) {
        null -> tr(Str.VPN_STATUS_UNKNOWN)
        true -> if (profile.health.latencyMs >= 0) "${profile.health.latencyMs} ms"
        else tr(Str.VPN_STATUS_ALIVE)
        false -> tr(Str.VPN_STATUS_DEAD) + if (profile.health.error.isNotEmpty()) {
            ": ${profile.health.error}"
        } else ""
    }
    val marker = if (active) "● " else ""

    ZappListRow(
        title = marker + profile.name.ifBlank { tr(protocol.strKey) },
        subtitle = protocol.label(lang),
        modifier = Modifier.clickable(onClick = onClick),
        trailing = {
            Text(
                text = status,
                style = MaterialTheme.typography.bodySmall,
                color = when (profile.health.alive) {
                    true -> MaterialTheme.colorScheme.primary
                    false -> MaterialTheme.colorScheme.error
                    null -> MaterialTheme.colorScheme.onSurfaceVariant
                }
            )
        }
    )
}

/**
 * Архив: коннекты, у которых проверка ответила отказом.
 *
 * Отдельная секция, а не скрытая вкладка: мёртвый узел должен быть виден
 * и находимым («а вдруг вернулся»), но не должен мешать выбирать живые.
 */
@Composable
private fun ArchiveCard(state: AppState) {
    val archived = state.config.vpn.archivedProfiles()
    if (archived.isEmpty()) return

    val lang = AppLangState.current
    PageGroup("${tr(Str.VPN_ARCHIVE)} (${archived.size})") {
        Row(horizontalArrangement = Arrangement.spacedBy(AzraelSpace.md)) {
            ZappButton(
                text = tr(Str.VPN_RECHECK),
                onClick = { state.recheckArchive() }
            )
            ZappButton(
                text = tr(Str.COMMON_DELETE),
                role = ButtonRole.GHOST,
                onClick = { state.deleteProfiles(archived.map { it.id }) }
            )
        }
        ZappCard {
            archived.forEach { profile ->
                val protocol = VpnProtocol.of(profile.protocol)
                ZappListRow(
                    title = profile.name.ifBlank { tr(protocol.strKey) },
                    subtitle = protocol.label(lang) + " · " + profile.health.error.ifBlank {
                        tr(Str.VPN_STATUS_DEAD)
                    }
                )
            }
        }
    }
}

/** Настройки выбранного профиля. */
@Composable
private fun VpnProfileCard(state: AppState) {
    val profile = state.config.vpn.activeProfile() ?: return
    val lang = AppLangState.current

    ZappCard {
        // Выбор профиля нужен только когда их больше одного: с единственным
        // список настроек -- чистый шум.
        val profiles = state.config.vpn.profiles
        if (profiles.size > 1) {
            ZappChoiceRow(
                label = tr(Str.VPN_PROFILES),
                options = profiles.map { Choice(it.id, it.name.ifBlank { it.id }) },
                selected = profile.id,
                onSelect = { id -> state.selectProfile(id) }
            )
        }
        ZappTextField(
            label = tr(Str.VPN_NAME),
            value = profile.name,
            onValueChange = { v -> updateProfile(state, profile.id) { it.copy(name = v) } },
            placeholder = profile.id
        )
        ZappChoiceRow(
            label = tr(Str.VPN_PROTOCOL),
            options = VpnProtocol.entries.map { Choice(it.code, it.label(lang)) },
            selected = profile.protocol,
            onSelect = { code -> updateProfile(state, profile.id) { it.copy(protocol = code) } }
        )
        ZappChoiceRow(
            label = tr(Str.VPN_TRANSPORT),
            options = VpnTransport.entries.map { Choice(it.code, it.label(lang)) },
            selected = profile.transport,
            onSelect = { code -> updateProfile(state, profile.id) { it.copy(transport = code) } }
        )
        ZappTextField(
            label = tr(Str.VPN_GATEWAY),
            value = profile.gateway,
            onValueChange = { v -> updateProfile(state, profile.id) { it.copy(gateway = v) } },
            placeholder = "vpn.example.org"
        )
        ZappNumberField(
            label = tr(Str.VPN_PORT),
            value = profile.gatewayPort,
            onValueChange = { v -> updateProfile(state, profile.id) { it.copy(gatewayPort = v ?: 0) } },
            placeholder = "51820"
        )
        ZappTextField(
            label = tr(Str.VPN_ADDRESS),
            value = profile.address,
            onValueChange = { v -> updateProfile(state, profile.id) { it.copy(address = v) } },
            placeholder = "10.0.0.2/32"
        )
        ZappTextField(
            label = tr(Str.VPN_PRIVATE_KEY),
            value = profile.privateKey,
            onValueChange = { v -> updateProfile(state, profile.id) { it.copy(privateKey = v) } }
        )
        ZappTextField(
            label = tr(Str.VPN_PEER_KEY),
            value = profile.publicKey,
            onValueChange = { v -> updateProfile(state, profile.id) { it.copy(publicKey = v) } }
        )
        ZappTextField(
            label = tr(Str.VPN_PRESHARED_KEY),
            value = profile.presharedKey,
            onValueChange = { v -> updateProfile(state, profile.id) { it.copy(presharedKey = v) } }
        )
        ZappNumberField(
            label = tr(Str.VPN_MTU),
            value = profile.mtu,
            onValueChange = { v -> updateProfile(state, profile.id) { it.copy(mtu = v ?: 1420) } },
            placeholder = "1420"
        )
        ZappNumberField(
            label = tr(Str.VPN_KEEPALIVE),
            value = profile.keepaliveSeconds,
            onValueChange = { v -> updateProfile(state, profile.id) { it.copy(keepaliveSeconds = v ?: 25) } },
            placeholder = "25"
        )
        ZappSettingRow(
            title = tr(Str.VPN_AUTO_RECONNECT),
            checked = profile.autoReconnect,
            onCheckedChange = { on -> updateProfile(state, profile.id) { it.copy(autoReconnect = on) } }
        )

        // Исходный текст конфига виден всегда, когда он есть: переимпорт,
        // сверка версий и «что вообще уехало в бэкенд» без него -- гадание.
        if (profile.rawConfig.isNotBlank()) {
            ZappTextField(
                label = tr(Str.COMMON_ADVANCED),
                value = profile.rawConfig,
                onValueChange = { v -> updateProfile(state, profile.id) { it.copy(rawConfig = v) } },
                singleLine = false,
                minLines = 4
            )
        }

        ZappButton(
            text = tr(Str.COMMON_DELETE),
            role = ButtonRole.DANGER,
            onClick = { state.deleteProfiles(listOf(profile.id)) }
        )
    }
}

/**
 * Новый пустой профиль.
 *
 * Идентификатор подбирается свободным, чтобы не перетирать существующий
 * профиль после его удаления. Имя оставляется пустым: подставляется
 * идентификатор, а своё имя человек задаёт в поле «Имя».
 */
private fun addProfile(state: AppState) {
    state.mutate { current ->
        val used = current.vpn.profiles.map { it.id }.toSet()
        val id = generateSequence(1) { it + 1 }
            .map { "profile-$it" }
            .first { it !in used }
        val profiles = current.vpn.profiles + VpnProfile(id = id, name = "")
        current.copy(vpn = current.vpn.copy(profiles = profiles, activeProfileId = id))
    }
}

/** Точечное изменение одного профиля. */
private fun updateProfile(
    state: AppState,
    id: String,
    block: (VpnProfile) -> VpnProfile
) {
    state.mutate { current ->
        val updated = current.vpn.profiles.map { if (it.id == id) block(it) else it }
        current.copy(vpn = current.vpn.copy(profiles = updated))
    }
}

/** Состояние туннеля -- ключ строки для перевода. */
private fun TunnelState.labelKey(): Str = when (this) {
    TunnelState.DISCONNECTED -> Str.ENGINE_STATE_DISCONNECTED
    TunnelState.CONNECTING -> Str.ENGINE_STATE_CONNECTING
    TunnelState.CONNECTED -> Str.ENGINE_STATE_CONNECTED
    TunnelState.ERROR -> Str.ENGINE_STATE_ERROR
}

@Composable
private fun VpnProtocol.label(lang: AppLang): String = if (lang == AppLang.RU) ru else en

@Composable
private fun VpnTransport.label(lang: AppLang): String = if (lang == AppLang.RU) ru else en
