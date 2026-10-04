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
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import kotlinx.coroutines.launch
import xyz.azraellab.zapp.core.AppLang
import xyz.azraellab.zapp.core.AppState
import xyz.azraellab.zapp.core.LinkSpeedBus
import xyz.azraellab.zapp.core.Str
import xyz.azraellab.zapp.core.VpnConnects
import xyz.azraellab.zapp.core.VpnProfile
import xyz.azraellab.zapp.core.VpnProtocol
import xyz.azraellab.zapp.core.VpnSource
import xyz.azraellab.zapp.core.daemon.DaemonState
import xyz.azraellab.zapp.core.engine.TunnelState
import xyz.azraellab.zapp.core.toDisplayString
import xyz.azraellab.zapp.ui.AppLangState
import xyz.azraellab.zapp.ui.components.Choice
import xyz.azraellab.zapp.ui.components.ComponentColors.ButtonRole
import xyz.azraellab.zapp.ui.components.ZappBanner
import xyz.azraellab.zapp.ui.components.ZappButton
import xyz.azraellab.zapp.ui.components.ZappButtonRow
import xyz.azraellab.zapp.ui.components.ZappCard
import xyz.azraellab.zapp.ui.components.ZappChoiceRow
import xyz.azraellab.zapp.ui.components.ZappEmptyState
import xyz.azraellab.zapp.ui.components.ZappExpandableCard
import xyz.azraellab.zapp.ui.components.ZappListRow
import xyz.azraellab.zapp.ui.components.ZappSettingRow
import xyz.azraellab.zapp.ui.components.ZappTextField
import xyz.azraellab.zapp.ui.theme.AzraelSpace
import xyz.azraellab.zapp.ui.tr

/**
 * Страница VPN.
 *
 * Главный экран отвечает на три вопроса: работает ли туннель, каким
 * коннектом и откуда он взялся. Всё, что меняют раз в месяц (маршруты,
 * DNS, ключи профиля), живёт в Настройках, а здесь -- только выдвижные
 * секции: заголовок с числом на виду, содержимое раскрывается по нажатию
 * и не занимает места, пока не открыли.
 */
@Composable
fun VpnPage(state: AppState) {
    val config = state.config.vpn
    val tunnelState by state.tunnel.state.collectAsState()
    val tunnelLog by state.tunnel.log.collectAsState()
    val checkProgress by state.checkProgress.collectAsState()
    val notice = state.vpnNotice

    PageScaffold(title = tr(Str.TAB_VPN)) {
        // --- Туннель: статус и кнопка в одной карточке ---
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
                // Скорость туннеля и активные режимы: живут, пока
                // подключены, обновляются раз в секунду отдельным опросом
                // счётчиков. Режимы -- только работающие: неактивные в
                // строку не попадают вовсе.
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
                // Причина сбоя -- под статусом, не только в журнале внизу.
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
            }
        } else {
            ZappCard { ZappEmptyState(title = tr(Str.ERR_TUNNEL_UNSUPPORTED)) }
        }

        // --- Сообщения импорта и обновлений ---
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

        // --- Коннекты: выбор и проверка ---
        ConnectsExpandable(state, checkProgress)

        // --- Источники: откуда берутся коннекты ---
        // Только обычные подписки: мостовые (kind == "tor") живут на
        // вкладке Tor -- разделы не смешивают свои темы.
        SourcesExpandable(
            state = state,
            title = Str.VPN_SOURCES,
            kind = "proxy",
            sources = remember(config.sources) {
                VpnConnects.sourcesByKind(config.sources, "proxy")
            }
        )

        // --- Архив: мёртвые коннекты с причиной ---
        ArchiveExpandable(state)

        // --- Журнал туннеля ---
        JournalExpandable(tunnelLog)
    }
}

/**
 * Коннекты: выбранный узел сверху, ниже -- сортировка, фильтр и сам список.
 *
 * Список ограничивается страницей из настроек производительности
 * (`perf.listPageSize`, по умолчанию 30) с кнопкой «Показать ещё»: 300
 * карточек в колонке внутри прокрутки -- это и лишняя композиция на
 * каждый кадр, и нечитаемая лента, когда нужен один живой узел.
 * Сортировка и фильтр -- чистые функции [VpnConnects], они же покрыты
 * тестами; здесь только вызов и `remember`, чтобы пересчёт шёл при
 * смене данных, а не на каждой рекомпозиции.
 */
@Composable
private fun ConnectsExpandable(state: AppState, checkProgress: AppState.CheckProgress) {
    val config = state.config.vpn
    val pageSize = state.config.perf.sanitized().listPageSize
    val lang = AppLangState.current
    val scope = rememberCoroutineScope()
    val active = config.activeProfile()

    var byPing by rememberSaveable { mutableStateOf(true) }
    var onlyAlive by rememberSaveable { mutableStateOf(false) }
    var query by rememberSaveable { mutableStateOf("") }
    var limit by rememberSaveable(pageSize) { mutableIntStateOf(pageSize) }
    var importOpen by rememberSaveable { mutableStateOf(false) }
    var importText by remember { mutableStateOf("") }

    val visible = remember(config.profiles) { state.visibleVpnProfiles() }
    val alive = visible.count { it.health.alive == true }
    val sorted = remember(visible, byPing) { VpnConnects.sort(visible, byPing) }
    val filtered = remember(sorted, query, onlyAlive) { VpnConnects.filter(sorted, onlyAlive, query) }
    val shown = filtered.take(limit)

    val subtitle = buildString {
        append("${visible.size} ${tr(Str.VPN_PROFILES).lowercase()}")
        if (alive > 0) append(" · $alive ${tr(Str.VPN_ALIVE_COUNT).lowercase()}")
        active?.let { a ->
            append(" · ")
            append(a.name.ifBlank { tr(VpnProtocol.of(a.protocol).strKey) })
        }
    }

    ZappExpandableCard(
        title = tr(Str.VPN_PROFILES),
        badge = if (alive > 0) alive.toString() else null,
        subtitle = subtitle
    ) {
        // Выбранный коннект -- одной строкой: понятно, чем подключимся.
        active?.let { a ->
            ZappCard {
                ZappListRow(
                    title = "● ${a.name.ifBlank { tr(VpnProtocol.of(a.protocol).strKey) }}",
                    subtitle = VpnProtocol.of(a.protocol).label(lang) +
                        if (a.health.latencyMs >= 0) " · ${a.health.latencyMs} ms" else ""
                )
            }
        }

        // Автовыбор и ручной выбор -- рядом со списком: это одна задача.
        ZappCard {
            ZappSettingRow(
                title = tr(Str.VPN_GROUP_MODE),
                subtitle = tr(Str.VPN_ALIVE_COUNT),
                checked = config.groupMode,
                onCheckedChange = { on ->
                    state.mutate { it.copy(vpn = it.vpn.copy(groupMode = on)) }
                }
            )
        }

        // Сортировка и фильтр -- всегда на виду: это то, чем пользуются
        // при каждом открытии списка.
        ZappChoiceRow(
            label = tr(Str.VPN_SORT_PING),
            options = listOf(
                Choice(true, tr(Str.VPN_SORT_PING)),
                Choice(false, tr(Str.VPN_SORT_NAME))
            ),
            selected = byPing,
            onSelect = { byPing = it }
        )
        ZappSettingRow(
            title = tr(Str.VPN_ONLY_ALIVE),
            checked = onlyAlive,
            onCheckedChange = { onlyAlive = it }
        )
        ZappTextField(
            label = tr(Str.VPN_SEARCH),
            value = query,
            onValueChange = { query = it; limit = pageSize },
            placeholder = "Sweden, Moscow, ..."
        )

        ZappButtonRow {
            ZappButton(
                text = if (checkProgress.running) {
                    tr(Str.VPN_CHECKING) + " ${checkProgress.done}/${checkProgress.total}"
                } else {
                    tr(Str.VPN_CHECK_ALL)
                },
                enabled = !checkProgress.running,
                onClick = { state.checkAllVisible() },
                modifier = Modifier.weight(1f)
            )
            ZappButton(
                text = tr(Str.VPN_ADD_LINKS),
                role = ButtonRole.SECONDARY,
                onClick = { importOpen = !importOpen },
                modifier = Modifier.weight(1f)
            )
        }
        ZappButton(
            text = tr(Str.VPN_ADD_PROFILE),
            role = ButtonRole.GHOST,
            onClick = { addProfile(state) },
            modifier = Modifier.fillMaxWidth()
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

        if (shown.isEmpty()) {
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
                shown.forEach { profile ->
                    ConnectRow(
                        profile = profile,
                        active = profile.id == config.activeProfileId,
                        lang = lang,
                        onClick = { state.selectProfile(profile.id) }
                    )
                }
            }
            if (VpnConnects.hasMore(filtered, shown.size)) {
                ZappButton(
                    text = tr(Str.VPN_SHOW_MORE) + " (${filtered.size - shown.size})",
                    role = ButtonRole.GHOST,
                    onClick = { limit += pageSize },
                    modifier = Modifier.fillMaxWidth()
                )
            }
        }
    }
}

/** Одна строка коннекта: имя, протокол и результат последней проверки. */
@Composable
internal fun ConnectRow(
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

    ZappListRow(
        title = (if (active) "● " else "") + profile.name.ifBlank { tr(protocol.strKey) },
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
 * Источники: выдвижной список репозиториев.
 *
 * Заголовок показывает число источников, каждый раскрытый элемент --
 * переключатель, статус и свои кнопки. Форма добавления лежит здесь же,
 * а не отдельной секцией: добавляют источник один раз за жизнь.
 *
 * Общая для VPN и Tor: различаются только [title] и [kind] -- раздел
 * получает подписки своей темы, новая подписка сохраняется с тем же
 * [kind], чтобы не уехала в чужий список.
 */
@Composable
internal fun SourcesExpandable(
    state: AppState,
    title: Str,
    kind: String,
    sources: List<VpnSource>
) {
    val sourceStatus by state.sourceStatus.collectAsState()
    var adding by rememberSaveable { mutableStateOf(false) }
    var newName by remember { mutableStateOf("") }
    var newUrl by remember { mutableStateOf("") }

    ZappExpandableCard(
        title = tr(title),
        badge = sources.size.toString(),
        subtitle = sources.count { it.enabled }.toString()
    ) {
        ZappButtonRow {
            ZappButton(
                text = tr(Str.VPN_SOURCE_ADD),
                role = if (adding) ButtonRole.GHOST else ButtonRole.SECONDARY,
                onClick = { adding = !adding },
                modifier = Modifier.weight(1f)
            )
            ZappButton(
                text = tr(Str.VPN_SOURCE_REFRESH),
                role = ButtonRole.SECONDARY,
                onClick = { state.refreshSources() },
                modifier = Modifier.weight(1f)
            )
        }

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
                ZappButtonRow {
                    ZappButton(
                        text = tr(Str.COMMON_APPLY),
                        onClick = {
                            if (state.addSource(newUrl, newName, kind)) {
                                newName = ""
                                newUrl = ""
                                adding = false
                            }
                        },
                        modifier = Modifier.weight(1f)
                    )
                    ZappButton(
                        text = tr(Str.COMMON_CLOSE),
                        role = ButtonRole.GHOST,
                        onClick = { adding = false },
                        modifier = Modifier.weight(1f)
                    )
                }
            }
        }

        sources.forEach { source ->
            val status = sourceStatus[source.id]
            ZappCard {
                ZappSettingRow(
                    title = source.name,
                    subtitle = sourceSubtitle(status),
                    checked = source.enabled,
                    onCheckedChange = { on -> state.setSourceEnabled(source.id, on) }
                )
                ZappButtonRow {
                    ZappButton(
                        text = tr(Str.VPN_SOURCE_REFRESH),
                        role = ButtonRole.GHOST,
                        onClick = { state.refreshSource(source.id) },
                        modifier = Modifier.weight(1f)
                    )
                    ZappButton(
                        text = tr(Str.COMMON_DELETE),
                        role = ButtonRole.GHOST,
                        onClick = { state.removeSource(source.id) },
                        modifier = Modifier.weight(1f)
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
 * Архив: коннекты, у которых проверка ответила отказом.
 *
 * Заголовок с числом всегда на виду («а вдруг вернулся»), список
 * раскрывается и не мешает выбирать живые.
 */
@Composable
private fun ArchiveExpandable(state: AppState) {
    val archived = state.config.vpn.archivedProfiles()
    if (archived.isEmpty()) return

    val lang = AppLangState.current
    ZappExpandableCard(
        title = tr(Str.VPN_ARCHIVE),
        badge = archived.size.toString()
    ) {
        ZappButtonRow {
            ZappButton(
                text = tr(Str.VPN_RECHECK),
                onClick = { state.recheckArchive() },
                modifier = Modifier.weight(1f)
            )
            ZappButton(
                text = tr(Str.COMMON_DELETE),
                role = ButtonRole.GHOST,
                onClick = { state.deleteProfiles(archived.map { it.id }) },
                modifier = Modifier.weight(1f)
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

/** Журнал туннеля: раскрывается по нажатию, последние строки внизу. */
@Composable
internal fun JournalExpandable(tunnelLog: List<String>) {
    ZappExpandableCard(
        title = tr(Str.ENGINE_LOG),
        badge = if (tunnelLog.isEmpty()) null else tunnelLog.size.toString()
    ) {
        if (tunnelLog.isEmpty()) {
            Text(
                text = tr(Str.ENGINE_LOG_EMPTY),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        } else {
            tunnelLog.takeLast(12).forEach { line ->
                val isError = line.startsWith("error") ||
                    line.contains("failed", ignoreCase = true) ||
                    line.contains("panic", ignoreCase = true)
                Text(
                    text = line,
                    style = MaterialTheme.typography.bodySmall,
                    color = if (isError) MaterialTheme.colorScheme.error
                    else MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

/**
 * Новый пустой профиль.
 *
 * Идентификатор подбирается свободным, чтобы не перетирать существующий
 * профиль после его удаления.
 */
fun addProfile(state: AppState) {
    state.mutate { current ->
        val used = current.vpn.profiles.map { it.id }.toSet()
        val id = generateSequence(1) { it + 1 }
            .map { "profile-$it" }
            .first { it !in used }
        val profiles = current.vpn.profiles + VpnProfile(id = id, name = "")
        current.copy(vpn = current.vpn.copy(profiles = profiles, activeProfileId = id))
    }
}

/** Состояние туннеля -- ключ строки для перевода. */
internal fun TunnelState.labelKey(): Str = when (this) {
    TunnelState.DISCONNECTED -> Str.ENGINE_STATE_DISCONNECTED
    TunnelState.CONNECTING -> Str.ENGINE_STATE_CONNECTING
    TunnelState.CONNECTED -> Str.ENGINE_STATE_CONNECTED
    TunnelState.ERROR -> Str.ENGINE_STATE_ERROR
}

/**
 * Активные режимы одной строкой для плашки: «T|V|Z|GDPI».
 *
 * Собирается только из того, кто реально работает: туннель (T -- через
 * мост Tor, V -- обычный VPN), zapret (Z) и goodbyeDPI (GDPI). Неактивные
 * в строку не попадают вовсе, поэтому скобки пустыми не бывают: вызывающий
 * показывает плашку только при непустом результате.
 */
@Composable
internal fun activeModes(state: AppState, tunnelState: TunnelState): String {
    val zapret by state.zapretDaemon.state.collectAsState()
    val dpi by state.dpiDaemon.state.collectAsState()
    val parts = buildList {
        if (tunnelState == TunnelState.CONNECTED) {
            val viaTor = state.config.vpn.activeProfile()?.protocol == VpnProtocol.TOR.code
            add(if (viaTor) "T" else "V")
        }
        if (zapret == DaemonState.RUNNING) add("Z")
        if (dpi == DaemonState.RUNNING) add("GDPI")
    }
    return parts.joinToString("|")
}

@Composable
internal fun VpnProtocol.label(lang: AppLang): String = if (lang == AppLang.RU) ru else en
