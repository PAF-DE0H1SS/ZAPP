package xyz.azraellab.zapp.ui.pages

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import xyz.azraellab.zapp.core.AppState
import xyz.azraellab.zapp.core.Str
import xyz.azraellab.zapp.core.routing.ProxyCascade
import xyz.azraellab.zapp.core.routing.RouteDetector
import xyz.azraellab.zapp.core.routing.RouteSection
import xyz.azraellab.zapp.core.routing.RouteTrace
import xyz.azraellab.zapp.core.routing.SectionAction
import xyz.azraellab.zapp.core.routing.SectionPresets
import xyz.azraellab.zapp.core.routing.TraceAction
import xyz.azraellab.zapp.core.routing.UrlTestFilter
import xyz.azraellab.zapp.core.routing.durationSeconds
import xyz.azraellab.zapp.core.routing.durationString
import xyz.azraellab.zapp.ui.components.ComponentColors.ButtonRole
import xyz.azraellab.zapp.ui.components.Choice
import xyz.azraellab.zapp.ui.components.ZappButton
import xyz.azraellab.zapp.ui.components.ZappButtonRow
import xyz.azraellab.zapp.ui.components.ZappCard
import xyz.azraellab.zapp.ui.components.ZappChoiceRow
import xyz.azraellab.zapp.ui.components.ZappListRow
import xyz.azraellab.zapp.ui.components.ZappNumberField
import xyz.azraellab.zapp.ui.components.ZappSettingRow
import xyz.azraellab.zapp.ui.components.ZappTextField
import xyz.azraellab.zapp.ui.tr

/**
 * Детальная настройка маршрутизации из Настроек VPN.
 *
 * Три независимых карточки: секции (правила по доменам/сетям/портам),
 * каскады (цепочки узлов) и фильтр автоподбора. Каждая -- свой
 * PageGroup в окне VPN: редкие настройки не смешиваются с профилем и
 * базовыми маршрутами.
 */

// --- Секции маршрутизации ---

@Composable
fun RouteSectionsCard(state: AppState) {
    val sections = state.config.vpn.routing.sections

    if (sections.isEmpty()) {
        ZappCard {
            ZappListRow(
                title = tr(Str.ROUTING_SECTIONS),
                subtitle = tr(Str.ROUTING_SECTION_ALL_TRAFFIC)
            )
        }
    }
    sections.forEachIndexed { index, section ->
        RouteSectionEditor(state, index, section)
    }
    ZappButton(
        text = tr(Str.ROUTING_SECTION_ADD),
        role = ButtonRole.SECONDARY,
        onClick = {
            state.mutate { config ->
                val added = config.vpn.routing.sections + RouteSection(
                    label = "section-${config.vpn.routing.sections.size + 1}"
                )
                config.copy(vpn = config.vpn.copy(routing = config.vpn.routing.copy(sections = added)))
            }
        },
        path = "routing.add_section"
    )

    // Готовые правила: одна кнопка -- одна секция, дальше редактируется
    // как рукописная. Порядок вставки -- в конец списка, порядок правил
    // меняет только сам пользователь.
    val presets = listOf(
        tr(Str.ROUTING_PRESET_ADS) to SectionPresets::ads,
        tr(Str.ROUTING_PRESET_RU) to SectionPresets::russia,
        tr(Str.ROUTING_PRESET_LOCAL) to SectionPresets::local
    )
    ZappListRow(title = tr(Str.ROUTING_PRESETS))
    ZappButtonRow(modifier = Modifier.fillMaxWidth()) {
        presets.forEachIndexed { index, (title, build) ->
            ZappButton(
                text = title,
                role = ButtonRole.SECONDARY,
                onClick = {
                    state.mutate { config ->
                        val added = SectionPresets.add(config.vpn.routing.sections, build(title))
                        config.copy(vpn = config.vpn.copy(routing = config.vpn.routing.copy(sections = added)))
                    }
                },
                modifier = Modifier.weight(1f),
                path = "routing.preset.$index"
            )
        }
    }
}

// --- Детектор правила ---

@Composable
fun RouteDetectorCard(state: AppState) {
    var address by remember { mutableStateOf("") }
    var port by remember { mutableStateOf("") }
    var trace by remember { mutableStateOf<RouteTrace?>(null) }

    ZappCard {
        ZappListRow(title = tr(Str.ROUTE_DETECTOR), subtitle = tr(Str.ROUTE_DETECTOR_HINT))
        ZappTextField(
            label = tr(Str.ROUTE_DETECTOR_ADDRESS),
            value = address,
            onValueChange = { address = it },
            placeholder = "example.com",
            path = "routing.detector.address"
        )
        ZappTextField(
            label = tr(Str.ROUTE_DETECTOR_PORT),
            value = port,
            onValueChange = { v -> port = v.filter { it.isDigit() }.take(5) },
            placeholder = "443",
            path = "routing.detector.port"
        )
        ZappButton(
            text = tr(Str.ROUTE_DETECTOR_RUN),
            role = ButtonRole.PRIMARY,
            onClick = {
                trace = RouteDetector.trace(
                    vpn = state.config.vpn,
                    query = address,
                    port = port.toIntOrNull()?.takeIf { it in 1..65535 }
                )
            },
            path = "routing.detector.run"
        )

        val result = trace ?: return@ZappCard
        val actionLabel = when (result.action) {
            TraceAction.TUNNEL -> tr(Str.ROUTE_DETECTOR_TUNNEL)
            TraceAction.DIRECT -> tr(Str.ROUTE_DETECTOR_DIRECT)
            TraceAction.BLOCK -> tr(Str.ROUTE_DETECTOR_BLOCK)
        }
        val winner = if (result.by == RouteDetector.RULE_DEFAULT) tr(Str.ROUTE_DETECTOR_DEFAULT) else result.by
        ZappListRow(
            title = "$actionLabel · $winner",
            subtitle = result.reason.ifBlank { null }
        )
        result.steps.forEach { step ->
            val mark = when {
                step.matched -> "✓"
                !step.verified -> "~"
                else -> "✗"
            }
            val name = when (step.rule) {
                RouteDetector.RULE_PRIVATE -> tr(Str.ROUTE_DETECTOR_PRIVATE)
                RouteDetector.RULE_MULTICAST -> tr(Str.ROUTE_DETECTOR_MULTICAST)
                RouteDetector.RULE_V6 -> tr(Str.ROUTE_DETECTOR_V6_OFF)
                RouteDetector.RULE_QUIC -> tr(Str.ROUTE_DETECTOR_QUIC)
                else -> step.rule
            }
            ZappListRow(title = "$mark $name", subtitle = step.detail, path = "routing.detector.step")
        }
    }
}

@Composable
private fun RouteSectionEditor(state: AppState, index: Int, section: RouteSection) {
    fun update(block: (RouteSection) -> RouteSection) {
        state.mutate { config ->
            val sections = config.vpn.routing.sections.mapIndexed { i, current ->
                if (i == index) block(current) else current
            }
            config.copy(vpn = config.vpn.copy(routing = config.vpn.routing.copy(sections = sections)))
        }
    }

    ZappCard {
        ZappSettingRow(
            title = tr(Str.ROUTING_SECTIONS),
            checked = section.enabled,
            subtitle = section.label.ifBlank { null },
            onCheckedChange = { on -> update { it.copy(enabled = on) } },
            path = "routing.section.enabled.$index"
        )
        ZappTextField(
            label = tr(Str.ROUTING_SECTION_LABEL),
            value = section.label,
            onValueChange = { v -> update { it.copy(label = v) } },
            placeholder = "section-${index + 1}",
            path = "routing.section.label.$index"
        )
        ZappChoiceRow(
            label = tr(Str.ROUTING_SECTION_ACTION),
            options = listOf(
                Choice(SectionAction.CONNECTION, tr(Str.ROUTING_ACTION_CONNECTION)),
                Choice(SectionAction.DIRECT, tr(Str.ROUTING_ACTION_DIRECT)),
                Choice(SectionAction.BLOCK, tr(Str.ROUTING_ACTION_BLOCK)),
                Choice(SectionAction.DNS, tr(Str.ROUTING_ACTION_DNS))
            ),
            selected = section.action,
            onSelect = { action -> update { it.copy(action = action) } },
            path = "routing.section.action.$index"
        )

        if (section.action == SectionAction.DNS) {
            ZappChoiceRow(
                label = tr(Str.ROUTING_DNS_TYPE),
                options = dnsTypeOptions(),
                selected = section.dnsType,
                onSelect = { type -> update { it.copy(dnsType = type) } },
                path = "routing.section.dnstype.$index"
            )
            ZappTextField(
                label = tr(Str.ROUTING_DNS_SERVER),
                value = section.dnsServer,
                onValueChange = { v -> update { it.copy(dnsServer = v) } },
                placeholder = "dns.example.org",
                path = "routing.section.dnsserver.$index"
            )
        } else {
            ZappTextField(
                label = tr(Str.ROUTING_DOMAINS),
                value = section.domains,
                onValueChange = { v -> update { it.copy(domains = v) } },
                placeholder = "ads.example.com, full:exact.host, regex:^ru\\.",
                singleLine = false,
                minLines = 2,
                supportingText = if (section.domains.isBlank() && section.ipCidrs.isBlank() &&
                    section.ports.isBlank() && section.network == "any"
                ) {
                    tr(Str.ROUTING_SECTION_ALL_TRAFFIC)
                } else {
                    tr(Str.ROUTING_DOMAINS_HINT)
                },
                path = "routing.section.domains.$index"
            )
            ZappTextField(
                label = tr(Str.ROUTING_IPS),
                value = section.ipCidrs,
                onValueChange = { v -> update { it.copy(ipCidrs = v) } },
                placeholder = "10.0.0.0/8, 192.168.1.1",
                path = "routing.section.ips.$index"
            )
            ZappTextField(
                label = tr(Str.ROUTING_PORTS),
                value = section.ports,
                onValueChange = { v -> update { it.copy(ports = v) } },
                placeholder = "443, 8000-9000",
                path = "routing.section.ports.$index"
            )
            ZappChoiceRow(
                label = tr(Str.ROUTING_NETWORK),
                options = networkOptions(),
                selected = section.network,
                onSelect = { network -> update { it.copy(network = network) } },
                path = "routing.section.network.$index"
            )
            if (section.action == SectionAction.CONNECTION) {
                ZappSettingRow(
                    title = tr(Str.ROUTING_RESOLVE_IP),
                    checked = section.resolveRealIp,
                    onCheckedChange = { on -> update { it.copy(resolveRealIp = on) } },
                    path = "routing.section.resolve.$index"
                )
            }
        }
        ZappButton(
            text = tr(Str.COMMON_DELETE),
            role = ButtonRole.DANGER,
            onClick = {
                state.mutate { config ->
                    val sections = config.vpn.routing.sections.filterIndexed { i, _ -> i != index }
                    config.copy(vpn = config.vpn.copy(routing = config.vpn.routing.copy(sections = sections)))
                }
            },
            path = "routing.section.delete.$index"
        )
    }
}

// --- Каскады ---

@Composable
fun RouteCascadesCard(state: AppState) {
    val routing = state.config.vpn.routing

    ZappCard {
        ZappChoiceRow(
            label = tr(Str.ROUTING_CASCADE_ACTIVE),
            options = listOf(Choice("", tr(Str.ROUTING_CASCADE_OFF))) +
                routing.cascades.filter { it.label.isNotBlank() }
                    .map { Choice(it.label, it.label) },
            selected = routing.activeCascade,
            onSelect = { label ->
                state.mutate { config ->
                    config.copy(vpn = config.vpn.copy(
                        routing = config.vpn.routing.copy(activeCascade = label)
                    ))
                }
            },
            path = "routing.cascade_active"
        )
    }

    routing.cascades.forEachIndexed { index, cascade ->
        CascadeEditor(state, index, cascade)
    }
    ZappButton(
        text = tr(Str.ROUTING_CASCADE_ADD),
        role = ButtonRole.SECONDARY,
        onClick = {
            state.mutate { config ->
                val added = config.vpn.routing.cascades + ProxyCascade(
                    label = "cascade-${config.vpn.routing.cascades.size + 1}"
                )
                config.copy(vpn = config.vpn.copy(routing = config.vpn.routing.copy(cascades = added)))
            }
        },
        path = "routing.add_cascade"
    )
}

@Composable
private fun CascadeEditor(state: AppState, index: Int, cascade: ProxyCascade) {
    fun update(block: (ProxyCascade) -> ProxyCascade) {
        state.mutate { config ->
            val cascades = config.vpn.routing.cascades.mapIndexed { i, current ->
                if (i == index) block(current) else current
            }
            config.copy(vpn = config.vpn.copy(routing = config.vpn.routing.copy(cascades = cascades)))
        }
    }

    ZappCard {
        ZappTextField(
            label = tr(Str.ROUTING_CASCADE_LABEL),
            value = cascade.label,
            onValueChange = { v -> update { it.copy(label = v) } },
            placeholder = "cascade-${index + 1}",
            path = "routing.cascade.label.$index"
        )
        ZappTextField(
            label = tr(Str.ROUTING_CASCADE_HOPS),
            value = cascade.hops,
            onValueChange = { v -> update { it.copy(hops = v) } },
            placeholder = "node-entry, node-exit",
            singleLine = false,
            minLines = 2,
            supportingText = tr(Str.ROUTING_CASCADE_HINT),
            path = "routing.cascade.hops.$index"
        )
        ZappButton(
            text = tr(Str.COMMON_DELETE),
            role = ButtonRole.DANGER,
            onClick = {
                state.mutate { config ->
                    val cascades = config.vpn.routing.cascades.filterIndexed { i, _ -> i != index }
                    val routing = config.vpn.routing.copy(
                        cascades = cascades,
                        activeCascade = if (config.vpn.routing.activeCascade == cascade.label) {
                            ""
                        } else {
                            config.vpn.routing.activeCascade
                        }
                    )
                    config.copy(vpn = config.vpn.copy(routing = routing))
                }
            },
            path = "routing.cascade.delete.$index"
        )
    }
}

// --- Фильтр автоподбора ---

@Composable
fun UrlTestFilterCard(state: AppState) {
    val filter = state.config.vpn.urlTest

    fun update(block: (UrlTestFilter) -> UrlTestFilter) {
        state.mutate { config -> config.copy(vpn = config.vpn.copy(urlTest = block(config.vpn.urlTest))) }
    }

    val modeUsed = filter.mode
    ZappCard {
        ZappChoiceRow(
            label = tr(Str.ROUTING_URLTEST_MODE),
            options = listOf(
                Choice(UrlTestFilter.MODE_DISABLED, tr(Str.ROUTING_MODE_DISABLED)),
                Choice(UrlTestFilter.MODE_INCLUDE, tr(Str.ROUTING_MODE_INCLUDE)),
                Choice(UrlTestFilter.MODE_EXCLUDE, tr(Str.ROUTING_MODE_EXCLUDE)),
                Choice(UrlTestFilter.MODE_MIXED, tr(Str.ROUTING_MODE_MIXED))
            ),
            selected = modeUsed,
            onSelect = { mode -> update { it.copy(mode = mode) } },
            path = "routing.urltest.mode"
        )
        if (modeUsed == UrlTestFilter.MODE_INCLUDE || modeUsed == UrlTestFilter.MODE_MIXED) {
            ZappTextField(
                label = tr(Str.ROUTING_INCLUDE_NAMES),
                value = filter.includeNames,
                onValueChange = { v -> update { it.copy(includeNames = v) } },
                placeholder = "NL, DE",
                path = "routing.urltest.include_names"
            )
            ZappTextField(
                label = tr(Str.ROUTING_INCLUDE_REGEX),
                value = filter.includeRegex,
                onValueChange = { v -> update { it.copy(includeRegex = v) } },
                placeholder = "NL|DE",
                path = "routing.urltest.include_regex"
            )
            ZappTextField(
                label = tr(Str.ROUTING_INCLUDE_COUNTRIES),
                value = filter.includeCountries,
                onValueChange = { v -> update { it.copy(includeCountries = v) } },
                placeholder = "NL, DE",
                path = "routing.urltest.include_countries"
            )
        }
        if (modeUsed == UrlTestFilter.MODE_EXCLUDE || modeUsed == UrlTestFilter.MODE_MIXED) {
            ZappTextField(
                label = tr(Str.ROUTING_EXCLUDE_NAMES),
                value = filter.excludeNames,
                onValueChange = { v -> update { it.copy(excludeNames = v) } },
                path = "routing.urltest.exclude_names"
            )
            ZappTextField(
                label = tr(Str.ROUTING_EXCLUDE_REGEX),
                value = filter.excludeRegex,
                onValueChange = { v -> update { it.copy(excludeRegex = v) } },
                path = "routing.urltest.exclude_regex"
            )
            ZappTextField(
                label = tr(Str.ROUTING_EXCLUDE_COUNTRIES),
                value = filter.excludeCountries,
                onValueChange = { v -> update { it.copy(excludeCountries = v) } },
                path = "routing.urltest.exclude_countries"
            )
        }
        ZappTextField(
            label = tr(Str.ROUTING_TEST_URL),
            value = filter.testingUrl,
            onValueChange = { v -> update { it.copy(testingUrl = v.trim()) } },
            placeholder = "https://www.gstatic.com/generate_204",
            path = "routing.urltest.test_url"
        )
        ZappTextField(
            label = tr(Str.ROUTING_INTERVAL),
            value = filter.interval,
            onValueChange = { v -> update { it.copy(interval = v) } },
            placeholder = "1m",
            path = "routing.urltest.interval"
        )
        ZappNumberField(
            label = tr(Str.ROUTING_TOLERANCE),
            value = filter.toleranceMs,
            onValueChange = { v -> update { it.copy(toleranceMs = (v ?: 10).coerceIn(0, 60_000)) } },
            placeholder = "10",
            path = "routing.urltest.tolerance"
        )
        ZappNumberField(
            label = tr(Str.ROUTING_IDLE),
            value = durationSeconds(filter.idleTimeout),
            onValueChange = { v ->
                update { it.copy(idleTimeout = if (v == null || v <= 0) "" else durationString(v)) }
            },
            placeholder = "0",
            path = "routing.urltest.idle"
        )
    }
}

// --- Общие списки выбора ---

@Composable
private fun networkOptions(): List<Choice<String>> = listOf(
    Choice("any", tr(Str.ROUTING_NETWORK_ANY)),
    Choice("tcp", tr(Str.ROUTING_NETWORK_TCP)),
    Choice("udp", tr(Str.ROUTING_NETWORK_UDP))
)

@Composable
private fun dnsTypeOptions(): List<Choice<String>> = listOf(
    Choice("udp", "UDP"),
    Choice("tcp", "TCP"),
    Choice("tls", "DoT"),
    Choice("https", "DoH")
)
