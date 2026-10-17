package xyz.azraellab.zapp.ui.pages

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.width
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.animation.core.animateFloatAsState
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
import xyz.azraellab.zapp.core.LinkSpeed
import xyz.azraellab.zapp.core.LinkSpeedBus
import xyz.azraellab.zapp.core.PingBus
import xyz.azraellab.zapp.core.RouteHealth
import xyz.azraellab.zapp.core.log.stripAnsi
import xyz.azraellab.zapp.core.route
import xyz.azraellab.zapp.core.Str
import xyz.azraellab.zapp.core.TrafficFormat
import xyz.azraellab.zapp.core.VpnConnects
import xyz.azraellab.zapp.core.VpnProfile
import xyz.azraellab.zapp.core.VpnProtocol
import xyz.azraellab.zapp.core.VpnSource
import xyz.azraellab.zapp.core.SourceFreshness
import xyz.azraellab.zapp.core.sourceAgeText
import xyz.azraellab.zapp.core.sourceFreshness
import xyz.azraellab.zapp.core.daemon.DaemonState
import xyz.azraellab.zapp.core.engine.SessionKind
import xyz.azraellab.zapp.core.engine.TunnelState
import xyz.azraellab.zapp.core.engine.isLive
import xyz.azraellab.zapp.core.engine.shownFor
import xyz.azraellab.zapp.core.log.bootstrapProgress
import xyz.azraellab.zapp.core.log.bootstrapStageKey
import xyz.azraellab.zapp.core.log.errorLine
import xyz.azraellab.zapp.core.log.looksLikeErrorLine
import xyz.azraellab.zapp.core.statusLine
import xyz.azraellab.zapp.ui.AppLangState
import androidx.compose.material3.AlertDialog
import xyz.azraellab.zapp.core.qr.encodeQr
import xyz.azraellab.zapp.ui.components.QrImage
import xyz.azraellab.zapp.ui.components.Choice
import xyz.azraellab.zapp.ui.components.ComponentColors.ButtonRole
import xyz.azraellab.zapp.ui.components.ConnectionOrb
import xyz.azraellab.zapp.ui.components.TrafficSparkline
import xyz.azraellab.zapp.ui.components.ZappBanner
import xyz.azraellab.zapp.ui.components.ZappButton
import xyz.azraellab.zapp.ui.components.ZappButtonRow
import xyz.azraellab.zapp.ui.components.ZappCard
import xyz.azraellab.zapp.ui.components.ZappChoiceRow
import xyz.azraellab.zapp.ui.components.ZappEmptyCard
import xyz.azraellab.zapp.ui.components.ZappEmptyState
import xyz.azraellab.zapp.ui.components.ZappExpandableCard
import xyz.azraellab.zapp.ui.components.ZappListRow
import xyz.azraellab.zapp.ui.components.ZappSettingRow
import xyz.azraellab.zapp.ui.components.ZappTextField
import xyz.azraellab.zapp.ui.theme.AzraelInfo
import xyz.azraellab.zapp.ui.theme.AzraelMotion
import xyz.azraellab.zapp.ui.theme.AzraelSpace
import xyz.azraellab.zapp.ui.theme.monoData
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
    // Чужая сессия здесь не показывается: туннель один, а вкладок две,
    // и запущенный мост Tor не должен выглядеть подключённым VPN -- и
    // наоборот, пассивный релей обхода вообще не выдаётся за чью-то
    // сессию. Нажатие орба при чужой сессии -- это СТАРТ, и поднимается
    // уже своя: движок снимает чужую сам.
    val ownState = tunnelState.shownFor(SessionKind.VPN, state.sessionOwner)

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
                        text = tr(ownState.labelKey()),
                        style = MaterialTheme.typography.titleMedium,
                        color = when (ownState) {
                            TunnelState.CONNECTED -> MaterialTheme.colorScheme.primary
                            TunnelState.ERROR -> MaterialTheme.colorScheme.error
                            TunnelState.CONNECTING, TunnelState.RECONNECTING ->
                                MaterialTheme.colorScheme.tertiary
                            TunnelState.DISCONNECTED -> MaterialTheme.colorScheme.onSurface
                        },
                        modifier = Modifier.weight(1f)
                    )
                    // Орб -- и статус, и кнопка: тон по состоянию туннеля,
                    // нажатие ставит сессию или снимает её.
                    ConnectionOrb(
                        state = ownState,
                        label = tr(if (ownState.isLive) Str.COMMON_STOP else Str.COMMON_START),
                        animate = state.config.perf.animatedBackground,
                        fps = state.config.perf.backgroundFps,
                        onClick = {
                            if (ownState.isLive) state.disconnectVpn()
                            else state.connectVpn()
                        }
                    )
                }
                // Скорость туннеля, пинг и активные режимы: живут, пока
                // подключены, обновляются фоновыми опросами. Режимы --
                // только работающие: неактивные в строку не попадают.
                // На подъёме вместо них -- процент сборки Tor.
                BootstrapLine(tunnelLog, ownState)
                SpeedLine(state, ownState)
                // Причина сбоя -- под статусом, не только в журнале внизу.
                if (ownState == TunnelState.ERROR) {
                    val reason = tunnelLog.errorLine()
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
                        onClick = { state.clearVpnNotice() },
                        path = "vpn.notice_close"
                    )
                }
            )
        }

        // --- Коннекты: выбор и проверка ---
        ConnectsExpandable(state, checkProgress)

        // Простой режим: источники, архив и журнал -- продвинутая
        // диагностика. Карточка туннекта и список коннектов остаются.
        if (!state.config.noviceMode) {
            // --- Источники: откуда берутся коннекты ---
            // Только обычные подписки: мостовые (kind == "tor") живут на
            // вкладке Tor -- разделы не смешивают свои темы.
            SourcesExpandable(
                state = state,
                title = Str.VPN_SOURCES,
                kind = "proxy",
                prefix = "vpn",
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
    var warpBusy by remember { mutableStateOf(false) }
    var qrOpen by remember { mutableStateOf(false) }

    val visible = remember(config.profiles) { state.visibleVpnProfiles() }
    // Достижимость, а не живость: проба -- это TCP/UDP-доступ до сервера
    // (см. probeLink), она не гоняет через узел ни байта. Замерено на
    // устройстве: профиль висел `alive: true, latencyMs: 55` от вчерашнего
    // дня, пока туннель через него не пропускал вообще ничего. Поэтому
    // в подписи «доступных», а не «живых» -- честнее ровно то, что измеряется.
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
            // Имя повторяется у десятков узлов (все «Sweden»), и по
            // субтитру не отличить выбранный: протокол добавляет
            // различимость без лишнего текста.
            append(" · ")
            append(VpnProtocol.of(a.protocol).label(lang))
        }
    }

    ZappExpandableCard(
        title = tr(Str.VPN_PROFILES),
        badge = if (alive > 0) alive.toString() else null,
        subtitle = subtitle,
        path = "vpn.profiles.expand"
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
                subtitle = tr(Str.VPN_GROUP_MODE_SUB),
                checked = config.groupMode,
                onCheckedChange = { on ->
                    state.mutate { it.copy(vpn = it.vpn.copy(groupMode = on)) }
                },
                path = "vpn.autoselect"
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
            onSelect = { byPing = it },
            path = "vpn.sort"
        )
        ZappSettingRow(
            title = tr(Str.VPN_ONLY_ALIVE),
            checked = onlyAlive,
            onCheckedChange = { onlyAlive = it },
            path = "vpn.only_alive"
        )
        ZappTextField(
            label = tr(Str.VPN_SEARCH),
            value = query,
            onValueChange = { query = it; limit = pageSize },
            placeholder = "Sweden, Moscow, ...",
            path = "vpn.search"
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
                modifier = Modifier.weight(1f),
                path = "vpn.check_all"
            )
            ZappButton(
                text = tr(Str.VPN_ADD_LINKS),
                role = ButtonRole.SECONDARY,
                onClick = { importOpen = !importOpen },
                modifier = Modifier.weight(1f),
                path = "vpn.add_links"
            )
        }
        // Прогресс проверки виден полосой, а не только цифрой в подписи
        // кнопки: N/M у строки есть, у заполнения -- не было.
        CheckProgressLine(progress = checkProgress)
        ZappButton(
            text = tr(Str.VPN_ADD_PROFILE),
            role = ButtonRole.GHOST,
            onClick = { addProfile(state) },
            modifier = Modifier.fillMaxWidth(),
            path = "vpn.add_profile"
        )
        ZappButton(
            text = tr(Str.VPN_WARP),
            role = ButtonRole.GHOST,
            enabled = !warpBusy,
            onClick = {
                warpBusy = true
                scope.launch {
                    state.registerWarp()
                    warpBusy = false
                }
            },
            modifier = Modifier.fillMaxWidth(),
            path = "vpn.warp"
        )
        ZappButton(
            text = tr(Str.VPN_QR),
            role = ButtonRole.GHOST,
            enabled = active?.rawConfig?.isNotBlank() == true,
            onClick = { qrOpen = true },
            modifier = Modifier.fillMaxWidth(),
            path = "vpn.qr"
        )

        if (qrOpen && active != null) {
            QrExportDialog(profile = active, onDismiss = { qrOpen = false })
        }

        if (importOpen) {
            ZappCard {
                ZappTextField(
                    label = tr(Str.VPN_ADD_LINKS),
                    value = importText,
                    onValueChange = { importText = it },
                    singleLine = false,
                    minLines = 3,
                    placeholder = "vless://... \ntrojan://... \n[Interface] ... \nproxies: ...",
                    path = "vpn.import_text"
                )
                ZappButton(
                    text = tr(Str.COMMON_IMPORT),
                    onClick = {
                        val text = importText
                        importText = ""
                        importOpen = false
                        scope.launch { state.importVpnText(text) }
                    },
                    path = "vpn.import"
                )
            }
        }

        if (shown.isEmpty()) {
            // Две разные пустоты, которые раньше выглядели одинаково:
            // коннектов нет вообще (ссылки не добавлены) и коннекты есть,
            // но фильтр или поиск их весь отсеял. Сообщение «В источнике
            // нет ссылок» при 574 узлах под поиском ввело в заблуждение.
            val nothingFiltered = visible.isEmpty()
            ZappCard {
                ZappEmptyState(
                    title = if (nothingFiltered) tr(Str.LIST_EMPTY_PROFILES) else tr(Str.COMMON_NOTHING_FOUND),
                    body = if (nothingFiltered) tr(Str.VPN_SOURCE_EMPTY) else tr(Str.COMMON_SEARCH),
                    action = {
                        if (nothingFiltered) {
                            ZappButton(
                                text = tr(Str.VPN_ADD_LINKS),
                                onClick = { importOpen = true },
                                path = "vpn.add_links_empty"
                            )
                        } else {
                            // Сброс фильтра -- единственный разумный выход,
                            // когда список спрятан самими настройками.
                            ZappButton(
                                text = tr(Str.COMMON_RESET),
                                onClick = {
                                    query = ""
                                    onlyAlive = false
                                },
                                path = "vpn.filter_reset"
                            )
                        }
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
                        onClick = { state.selectProfile(profile.id) },
                        path = "vpn.connect.${profile.id}"
                    )
                }
            }
            if (VpnConnects.hasMore(filtered, shown.size)) {
                ZappButton(
                    text = tr(Str.VPN_SHOW_MORE) + " (${filtered.size - shown.size})",
                    role = ButtonRole.GHOST,
                    onClick = { limit += pageSize },
                    modifier = Modifier.fillMaxWidth(),
                    path = "vpn.show_more"
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
    onClick: () -> Unit,
    path: String? = null
) {
    val protocol = VpnProtocol.of(profile.protocol)
    // Тройство здоровья маршрута -- явный enum: «не проверялось», «живо»
    // и «мёртвое» -- три разных состояния, и путать их через булево с null
    // легко, а через when по RouteHealth -- нет.
    val status = when (profile.health.route) {
        RouteHealth.UNKNOWN -> tr(Str.VPN_STATUS_UNKNOWN)
        RouteHealth.HEALTHY -> if (profile.health.latencyMs >= 0) "${profile.health.latencyMs} ms"
        else tr(Str.VPN_STATUS_ALIVE)
        RouteHealth.UNHEALTHY -> tr(Str.VPN_STATUS_DEAD) + if (profile.health.error.isNotEmpty()) {
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
                color = when (profile.health.route) {
                    RouteHealth.HEALTHY -> MaterialTheme.colorScheme.primary
                    RouteHealth.UNHEALTHY -> MaterialTheme.colorScheme.error
                    RouteHealth.UNKNOWN -> MaterialTheme.colorScheme.onSurfaceVariant
                }
            )
        },
        path = path
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
    prefix: String = "sources",
    sources: List<VpnSource>
) {
    val sourceStatus by state.sourceStatus.collectAsState()
    var adding by rememberSaveable { mutableStateOf(false) }
    var newName by remember { mutableStateOf("") }
    var newUrl by remember { mutableStateOf("") }

    ZappExpandableCard(
        title = tr(title),
        badge = sources.size.toString(),
        // Подпись -- какие источники включены, а не голое число: бейдж и так
        // несёт всего, а «2» под ним ничего не объясняет. Имена свои,
        // пользовательские, переводить их нечего.
        subtitle = sources.filter { it.enabled }
            .joinToString(", ") { it.name.ifBlank { it.url } }
            .ifBlank { "—" },
        path = "$prefix.sources.expand"
    ) {
        ZappButtonRow {
            // Веса по длине подписи, а не поровну: «Добавить источник»
            // (17 знаков) в половине карточки упирался в минимальный кегль
            // и обрезался до «Добавить ис…», тогда как короткое
            // «Обновить» отдавало половину ширины впустую.
            val addWeight = tr(Str.VPN_SOURCE_ADD).length.toFloat().coerceAtLeast(1f)
            val refreshWeight = tr(Str.VPN_SOURCE_REFRESH).length.toFloat().coerceAtLeast(1f)
            ZappButton(
                text = tr(Str.VPN_SOURCE_ADD),
                role = if (adding) ButtonRole.GHOST else ButtonRole.SECONDARY,
                onClick = { adding = !adding },
                modifier = Modifier.weight(addWeight),
                path = "$prefix.sources.add"
            )
            ZappButton(
                text = tr(Str.VPN_SOURCE_REFRESH),
                role = ButtonRole.SECONDARY,
                onClick = { state.refreshSources() },
                modifier = Modifier.weight(refreshWeight),
                path = "$prefix.sources.refresh_all"
            )
        }

        if (adding) {
            ZappCard {
                ZappTextField(
                    label = tr(Str.VPN_SOURCE_NAME),
                    value = newName,
                    onValueChange = { newName = it },
                    path = "$prefix.sources.name"
                )
                ZappTextField(
                    label = tr(Str.VPN_SOURCE_URL),
                    value = newUrl,
                    onValueChange = { newUrl = it },
                    placeholder = "https://example.com/sub.txt",
                    path = "$prefix.sources.url"
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
                        modifier = Modifier.weight(1f),
                        path = "$prefix.sources.apply"
                    )
                    ZappButton(
                        text = tr(Str.COMMON_CLOSE),
                        role = ButtonRole.GHOST,
                        onClick = { adding = false },
                        modifier = Modifier.weight(1f),
                        path = "$prefix.sources.close"
                    )
                }
            }
        }

        if (sources.isEmpty()) {
            // Пусто -- не молчим: раньше здесь вообще не рисовалось ничего,
            // кроме бейджа «0» и кнопок. Иллюстрация + подпись объясняют,
            // что делать.
            ZappEmptyCard(
                title = tr(Str.SOURCES_EMPTY_TITLE),
                body = tr(Str.SOURCES_EMPTY_BODY)
            )
        }

        sources.forEach { source ->
            val status = sourceStatus[source.id]
            ZappCard {
                ZappSettingRow(
                    title = source.name,
                    subtitle = sourceSubtitle(source, status),
                    checked = source.enabled,
                    onCheckedChange = { on -> state.setSourceEnabled(source.id, on) },
                    path = "$prefix.sources.toggle.${source.id}"
                )
                ZappButtonRow {
                    ZappButton(
                        text = tr(Str.VPN_SOURCE_REFRESH),
                        role = ButtonRole.GHOST,
                        onClick = { state.refreshSource(source.id) },
                        modifier = Modifier.weight(1f),
                        path = "$prefix.sources.refresh.${source.id}"
                    )
                    ZappButton(
                        text = tr(Str.COMMON_DELETE),
                        role = ButtonRole.GHOST,
                        onClick = { state.removeSource(source.id) },
                        modifier = Modifier.weight(1f),
                        path = "$prefix.sources.delete.${source.id}"
                    )
                }
            }
        }
    }
}

/**
 * Полоса прогресса проверки коннектов.
 *
 * Цифры `done/total` были только в подписи кнопки -- маленький текст,
 * который легко не заметить. Полоса под строкой кнопок показывает заполнение
 * плавно (animateFloatAsState внутри LinearProgressIndicator) и гаснет,
 * когда проверка кончилась: компонент не рисует ничего при останове.
 */
@Composable
internal fun CheckProgressLine(progress: AppState.CheckProgress) {
    if (!progress.running) return
    val fraction by animateFloatAsState(
        targetValue = if (progress.total > 0) progress.done.toFloat() / progress.total else 0f,
        animationSpec = AzraelMotion.spec(AzraelMotion.base),
        label = "check-progress"
    )
    LinearProgressIndicator(
        progress = { fraction },
        modifier = Modifier.fillMaxWidth()
    )
}

/**
 * Подпись состояния источника: загрузка, число коннектов или ошибка.
 *
 * Пока источник молчит (IDLE), подпись несёт свежесть по времени
 * последнего обновления -- иначе список выглядит застывшим, и непонятно,
 * автообновитель вообще работает. Устаревший источник получает пометку
 * вдобавок к состоянию: три периода молчания -- это уже не пауза ноутбука.
 */
@Composable
private fun sourceSubtitle(source: VpnSource, status: AppState.SourceStatus?): String {
    val now = System.currentTimeMillis()
    val age = sourceAgeText(now, source.lastRefreshAtMs)
    val stale = sourceFreshness(now, source.lastRefreshAtMs, source.refreshSeconds) ==
        SourceFreshness.STALE
    val base = when (status?.state) {
        null, AppState.SourceState.IDLE -> when {
            source.lastRefreshAtMs == 0L -> tr(Str.SOURCE_NEVER)
            else -> tr(Str.SOURCE_UPDATED) + " $age"
        }
        AppState.SourceState.LOADING -> tr(Str.VPN_CHECKING)
        AppState.SourceState.OK -> tr(Str.VPN_SOURCE_REFRESHED) + ": ${status.links}"
        AppState.SourceState.ERROR -> tr(Str.VPN_SOURCE_FAILED) + ": ${status.error}"
    }
    return if (stale) base + " · " + tr(Str.SOURCE_STALE) else base
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
        badge = archived.size.toString(),
        path = "vpn.archive.expand"
    ) {
        ZappButtonRow {
            ZappButton(
                text = tr(Str.VPN_RECHECK),
                onClick = { state.recheckArchive() },
                modifier = Modifier.weight(1f),
                path = "vpn.archive.recheck"
            )
            ZappButton(
                text = tr(Str.COMMON_DELETE),
                role = ButtonRole.GHOST,
                onClick = { state.deleteProfiles(archived.map { it.id }) },
                modifier = Modifier.weight(1f),
                path = "vpn.archive.delete"
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
internal fun JournalExpandable(tunnelLog: List<String>, path: String = "log.expand") {
    ZappExpandableCard(
        title = tr(Str.ENGINE_LOG),
        badge = if (tunnelLog.isEmpty()) null else tunnelLog.size.toString(),
        path = path
    ) {
        if (tunnelLog.isEmpty()) {
            // Пусто -- не голая строка: иллюстрация объясняет, что
            // журнал ещё не заполнен.
            ZappEmptyCard(title = tr(Str.ENGINE_LOG_EMPTY))
        } else {
            tunnelLog.takeLast(12).forEach { line ->
                val isError = looksLikeErrorLine(line)
                Text(
                    text = stripAnsi(line),
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
    TunnelState.RECONNECTING -> Str.ENGINE_STATE_RECONNECTING
    TunnelState.ERROR -> Str.ENGINE_STATE_ERROR
}

/**
 * Прогресс сборки Tor под статусом.
 *
 * Пока туннель поднимается, статус «Подключение...» висит минутами без
 * изменений, а в журнале уже идут проценты и стадии -- они просто никем не
 * показывались. Строка берёт разбор из чистой [bootstrapProgress] и
 * показывается только на подъёме: после CONNECTED ей нечего сообщать.
 *
 * Общая для VPN и Tor: мост -- это тоже туннель, и на вкладке VPN такая
 * сессия живёт так же, как на своей.
 */
@Composable
internal fun BootstrapLine(tunnelLog: List<String>, tunnelState: TunnelState) {
    if (tunnelState != TunnelState.CONNECTING && tunnelState != TunnelState.RECONNECTING) return
    val progress = tunnelLog.bootstrapProgress() ?: return
    val stage = bootstrapStageKey(progress.stage)?.let { " · " + tr(it) }
    Text(
        text = "${tr(Str.TOR_BOOTSTRAP)} ${progress.percent}%${stage ?: ""}",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.tertiary
    )
}

/**
 * Строка под статусом: скорость, пинг и активные режимы.
 *
 * Общая для вкладок VPN и Tor: строки статуса у них разные, а эта --
 * нет, и расхождение в буквах или разделителях читалось бы как баг.
 * Данные приходят из общих каналов ([LinkSpeedBus], [PingBus]), а
 * сборка строки -- чистая ([statusLine]) и покрыта тестами.
 */
@Composable
internal fun SpeedLine(state: AppState, tunnelState: TunnelState) {
    val linkSpeed by LinkSpeedBus.current.collectAsState()
    val ping by PingBus.current.collectAsState()
    val history by state.trafficHistory.collectAsState()
    val summary by state.trafficSummaryFlow.collectAsState()
    val connected = tunnelState == TunnelState.CONNECTED
    // Плавный счётчик: опрос даёт новое значение раз в секунду, и
    // без сглаживания цифры прыгали скачком. Значение доезжает до цели
    // за base (220 мс) -- глаз видит изменение, а не подмену. Считаем
    // в байтах/с (Float достаточно: речь о сотнях килобайт), форматирование
    // и так чистая функция statusLine.
    val downTarget = (linkSpeed?.downBps ?: 0L).toFloat()
    val upTarget = (linkSpeed?.upBps ?: 0L).toFloat()
    val down by animateFloatAsState(
        targetValue = downTarget,
        animationSpec = AzraelMotion.spec(AzraelMotion.base),
        label = "speed-down"
    )
    val up by animateFloatAsState(
        targetValue = upTarget,
        animationSpec = AzraelMotion.spec(AzraelMotion.base),
        label = "speed-up"
    )
    // Скорость есть только пока туннель поднят и опрос крутится: null --
    // это «нет данных», и прятать его сглаживанием в ноль нельзя.
    val smoothed = linkSpeed?.let { LinkSpeed(down.toLong(), up.toLong()) }
    val line = statusLine(
        connected = connected,
        speed = smoothed,
        pingMs = ping,
        modes = activeModes(state, tunnelState)
    )
    if (line.isNotEmpty()) {
        Text(
            text = line,
            style = monoData(),
            color = MaterialTheme.colorScheme.primary
        )
    }
    // Мини-график под строкой: только пока подключены и есть что показывать.
    if (connected) {
        TrafficSparkline(history = history)
        // Счётчик трафика за сеанс: скачано и отправлено с начала опроса.
        // Цвета строк совпадают с линиями спарклайна (rx -- красный,
        // tx -- синий), чтобы читать график и числа одной парой глаз.
        val trafficConfig = state.config.traffic
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = "↓ ${TrafficFormat.bytes(summary.rxBytes, trafficConfig)}",
                style = monoData(),
                color = MaterialTheme.colorScheme.error
            )
            Spacer(modifier = Modifier.width(AzraelSpace.md))
            Text(
                text = "↑ ${TrafficFormat.bytes(summary.txBytes, trafficConfig)}",
                style = monoData(),
                color = AzraelInfo
            )
        }
    }
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
            // Буква по владельцу сессии, а не по активному профилю: тот
            // меняется выбором в списке и не обязан совпадать с тем, что
            // реально поднято. Пассивный релей обхода буквы не получает --
            // это не пользовательский VPN.
            val letter = when (state.sessionOwner) {
                SessionKind.TOR -> "T"
                SessionKind.VPN -> "V"
                SessionKind.NONE, SessionKind.DPI -> null
            }
            if (letter != null) add(letter)
        }
        if (zapret == DaemonState.RUNNING) add("Z")
        if (dpi == DaemonState.RUNNING) add("GDPI")
    }
    return parts.joinToString("|")
}

@Composable
internal fun VpnProtocol.label(lang: AppLang): String = if (lang == AppLang.RU) ru else en

/** QR профиля для передачи на другое устройство. */
@Composable
private fun QrExportDialog(profile: VpnProfile, onDismiss: () -> Unit) {
    val text = profile.rawConfig
    val matrix = remember(text) { encodeQr(text) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                text = profile.name,
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface
            )
        },
        text = {
            if (matrix != null) {
                QrImage(matrix = matrix)
            } else {
                Text(
                    text = tr(Str.VPN_QR_EMPTY),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        },
        confirmButton = {
            ZappButton(text = tr(Str.COMMON_CLOSE), onClick = onDismiss)
        }
    )
}
