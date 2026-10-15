package xyz.azraellab.zapp.ui.pages

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import xyz.azraellab.zapp.core.AppLang
import xyz.azraellab.zapp.core.AppState
import xyz.azraellab.zapp.core.RegionPolicies
import xyz.azraellab.zapp.core.Str
import xyz.azraellab.zapp.core.VpnProfile
import xyz.azraellab.zapp.core.VpnProtocol
import xyz.azraellab.zapp.core.VpnTransport
import xyz.azraellab.zapp.core.consistency.SettingKeys
import xyz.azraellab.zapp.ui.AppLangState
import xyz.azraellab.zapp.ui.components.Choice
import xyz.azraellab.zapp.ui.components.ComponentColors.ButtonRole
import xyz.azraellab.zapp.ui.components.ZappButton
import xyz.azraellab.zapp.ui.components.ZappCard
import xyz.azraellab.zapp.ui.components.ZappChoiceRow
import xyz.azraellab.zapp.ui.components.ZappListRow
import xyz.azraellab.zapp.ui.components.ZappNumberField
import xyz.azraellab.zapp.ui.components.ZappSettingRow
import xyz.azraellab.zapp.ui.components.ZappTextField
import xyz.azraellab.zapp.ui.consistency.isLocked
import xyz.azraellab.zapp.ui.consistency.lockReason
import xyz.azraellab.zapp.ui.consistency.settingLocks
import xyz.azraellab.zapp.ui.theme.AzraelSpace
import xyz.azraellab.zapp.ui.tr

/**
 * Редактор выбранного профиля и его тонкие настройки.
 *
 * Живёт в Настройках, а не на главном экране VPN: ключи, MTU и
 * переключатели маршрутов меняют один раз при настройке, а главный экран
 * отвечает только за «работает/выбрать/старт».
 */
@Composable
fun VpnProfileEditor(state: AppState) {
    val profile = state.config.vpn.activeProfile()
    if (profile == null) {
        ZappCard {
            ZappListRow(title = tr(Str.LIST_EMPTY_PROFILES), subtitle = tr(Str.VPN_SOURCE_EMPTY))
        }
        return
    }
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
                onSelect = { id -> state.selectProfile(id) },
                path = "editor.profile"
            )
        }
        ZappTextField(
            label = tr(Str.VPN_NAME),
            value = profile.name,
            onValueChange = { v -> updateProfile(state, profile.id) { it.copy(name = v) } },
            placeholder = profile.id,
            path = "editor.name"
        )
        ZappChoiceRow(
            label = tr(Str.VPN_PROTOCOL),
            options = VpnProtocol.entries
                .filter { !RegionPolicies.isHidden(state.config.region, it.code) }
                .map { Choice(it.code, it.label(lang)) },
            selected = profile.protocol,
            onSelect = { code -> updateProfile(state, profile.id) { it.copy(protocol = code) } },
            path = "editor.protocol"
        )
        ZappChoiceRow(
            label = tr(Str.VPN_TRANSPORT),
            options = VpnTransport.entries.map { Choice(it.code, it.label(lang)) },
            selected = profile.transport,
            onSelect = { code -> updateProfile(state, profile.id) { it.copy(transport = code) } },
            path = "editor.transport"
        )
        ZappTextField(
            label = tr(Str.VPN_GATEWAY),
            value = profile.gateway,
            onValueChange = { v -> updateProfile(state, profile.id) { it.copy(gateway = v) } },
            placeholder = "vpn.example.org",
            path = "editor.gateway"
        )
        ZappNumberField(
            label = tr(Str.VPN_PORT),
            value = profile.gatewayPort,
            onValueChange = { v -> updateProfile(state, profile.id) { it.copy(gatewayPort = v ?: 0) } },
            placeholder = "51820",
            path = "editor.port"
        )
        ZappTextField(
            label = tr(Str.VPN_ADDRESS),
            value = profile.address,
            onValueChange = { v -> updateProfile(state, profile.id) { it.copy(address = v) } },
            placeholder = "10.0.0.2/32",
            path = "editor.address"
        )
        ZappTextField(
            label = tr(Str.VPN_PRIVATE_KEY),
            value = profile.privateKey,
            onValueChange = { v -> updateProfile(state, profile.id) { it.copy(privateKey = v) } },
            path = "editor.private_key"
        )
        ZappTextField(
            label = tr(Str.VPN_PEER_KEY),
            value = profile.publicKey,
            onValueChange = { v -> updateProfile(state, profile.id) { it.copy(publicKey = v) } },
            path = "editor.peer_key"
        )
        ZappTextField(
            label = tr(Str.VPN_PRESHARED_KEY),
            value = profile.presharedKey,
            onValueChange = { v -> updateProfile(state, profile.id) { it.copy(presharedKey = v) } },
            path = "editor.preshared_key"
        )
        ZappNumberField(
            label = tr(Str.VPN_MTU),
            value = profile.mtu,
            onValueChange = { v -> updateProfile(state, profile.id) { it.copy(mtu = v ?: 1420) } },
            placeholder = "1420",
            path = "editor.mtu"
        )
        ZappNumberField(
            label = tr(Str.VPN_KEEPALIVE),
            value = profile.keepaliveSeconds,
            onValueChange = { v -> updateProfile(state, profile.id) { it.copy(keepaliveSeconds = v ?: 25) } },
            placeholder = "25",
            path = "editor.keepalive"
        )
        ZappSettingRow(
            title = tr(Str.VPN_AUTO_RECONNECT),
            checked = profile.autoReconnect,
            onCheckedChange = { on -> updateProfile(state, profile.id) { it.copy(autoReconnect = on) } },
            path = "editor.auto_reconnect"
        )
        // Kill switch и автозапуск -- свойства туннеля, а не узла: они
        // вынесены в отдельную группу настроек VPN (C3), в редакторе
        // остались только поля конкретного профиля.

        // Исходный текст конфига виден всегда, когда он есть: переимпорт,
        // сверка версий и «что вообще уехало в бэкенд» без него -- гадание.
        if (profile.rawConfig.isNotBlank()) {
            ZappTextField(
                label = tr(Str.COMMON_ADVANCED),
                value = profile.rawConfig,
                onValueChange = { v -> updateProfile(state, profile.id) { it.copy(rawConfig = v) } },
                singleLine = false,
                minLines = 4,
                path = "editor.raw_config"
            )
        }

        Row(horizontalArrangement = Arrangement.spacedBy(AzraelSpace.md)) {
            ZappButton(
                text = tr(Str.VPN_ADD_PROFILE),
                role = ButtonRole.SECONDARY,
                onClick = { addProfile(state) },
                modifier = Modifier.weight(1f),
                path = "editor.add_profile"
            )
            ZappButton(
                text = tr(Str.COMMON_DELETE),
                role = ButtonRole.DANGER,
                onClick = { state.deleteProfiles(listOf(profile.id)) },
                modifier = Modifier.weight(1f),
                path = "editor.delete_profile"
            )
        }
    }
}

/**
 * Маршруты и DNS: тонкие переключатели туннеля.
 *
 * Отдельная карточка для Настроек: DNS-перехват и исключение LAN понадобятся
 * раз при разборе «почему не открывается», и держать их на главном экране --
 * значит показывать стену редко нужного.
 */
@Composable
fun VpnRoutingCard(state: AppState) {
    val config = state.config.vpn
    val locks = settingLocks(state)
    ZappCard {
        ZappSettingRow(
            title = tr(Str.VPN_ROUTES_ALL),
            checked = config.routing.routesAllTraffic,
            onCheckedChange = { on ->
                state.mutate { it.copy(vpn = it.vpn.copy(routing = it.vpn.routing.copy(routesAllTraffic = on))) }
            },
            path = "routing.all_traffic"
        )
        ZappSettingRow(
            title = tr(Str.VPN_INTERCEPT_DNS),
            subtitle = tr(Str.VPN_ROUTING_HINT_DNS),
            checked = config.routing.interceptDns,
            onCheckedChange = { on ->
                state.mutate { it.copy(vpn = it.vpn.copy(routing = it.vpn.routing.copy(interceptDns = on))) }
            },
            path = "routing.intercept_dns"
        )
        ZappSettingRow(
            title = tr(Str.VPN_BYPASS_LAN),
            checked = config.routing.excludePrivateRanges,
            onCheckedChange = { on ->
                state.mutate {
                    it.copy(vpn = it.vpn.copy(routing = it.vpn.routing.copy(excludePrivateRanges = on)))
                }
            },
            path = "routing.bypass_lan"
        )
        ZappSettingRow(
            title = tr(Str.VPN_DNS_FAKE_IP),
            checked = config.dnsFakeIp,
            subtitle = lockReason(locks, SettingKeys.DNS_FAKE_IP),
            enabled = !isLocked(locks, SettingKeys.DNS_FAKE_IP),
            onCheckedChange = { on ->
                state.mutate { it.copy(vpn = it.vpn.copy(dnsFakeIp = on)) }
            },
            path = "routing.dns_fake_ip"
        )
        ZappSettingRow(
            title = tr(Str.VPN_DNS_VIA_PROXY),
            checked = config.dnsViaProxy,
            subtitle = lockReason(locks, SettingKeys.DNS_VIA_PROXY),
            enabled = !isLocked(locks, SettingKeys.DNS_VIA_PROXY),
            onCheckedChange = { on ->
                state.mutate { it.copy(vpn = it.vpn.copy(dnsViaProxy = on)) }
            },
            path = "routing.dns_via_proxy"
        )
        ZappSettingRow(
            title = tr(Str.VPN_SPLIT_TUNNELING),
            checked = config.routing.allowPerAppBypass,
            onCheckedChange = { on ->
                state.mutate {
                    it.copy(vpn = it.vpn.copy(routing = it.vpn.routing.copy(allowPerAppBypass = on)))
                }
            },
            path = "routing.split_tunneling"
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
                placeholder = "com.android.chrome",
                path = "routing.bypass_apps"
            )
        }
        ZappTextField(
            label = tr(Str.VPN_DNS),
            value = config.dnsServers,
            onValueChange = { v -> state.mutate { it.copy(vpn = it.vpn.copy(dnsServers = v)) } },
            placeholder = "1.1.1.1, 9.9.9.9",
            path = "routing.dns"
        )

        // --- Тонкая настройка DNS-серверов ---
        ZappChoiceRow(
            label = tr(Str.ROUTING_DNS_TYPE),
            options = listOf(
                Choice("udp", "UDP"),
                Choice("tcp", "TCP"),
                Choice("tls", "DoT"),
                Choice("https", "DoH")
            ),
            selected = config.dnsType,
            onSelect = { type -> state.mutate { it.copy(vpn = it.vpn.copy(dnsType = type)) } },
            path = "routing.dns_type"
        )
        ZappTextField(
            label = tr(Str.ROUTING_DNS_BOOTSTRAP),
            value = config.dnsBootstrap,
            onValueChange = { v -> state.mutate { it.copy(vpn = it.vpn.copy(dnsBootstrap = v.trim())) } },
            placeholder = "77.88.8.8",
            path = "routing.dns_bootstrap"
        )
        ZappChoiceRow(
            label = tr(Str.ROUTING_DNS_STRATEGY),
            options = listOf(
                Choice("off", tr(Str.ROUTING_DNS_STRATEGY_OFF)),
                Choice("prefer_ipv4", tr(Str.ROUTING_STRATEGY_PREFER_V4)),
                Choice("prefer_ipv6", tr(Str.ROUTING_STRATEGY_PREFER_V6)),
                Choice("disable_ipv6", tr(Str.ROUTING_STRATEGY_DISABLE_V6))
            ),
            selected = config.dnsStrategy,
            onSelect = { strategy -> state.mutate { it.copy(vpn = it.vpn.copy(dnsStrategy = strategy)) } },
            path = "routing.dns_strategy"
        )
        ZappNumberField(
            label = tr(Str.ROUTING_DNS_REWRITE_TTL),
            value = config.dnsRewriteTtl,
            onValueChange = { v ->
                state.mutate { it.copy(vpn = it.vpn.copy(dnsRewriteTtl = (v ?: 60).coerceIn(0, 86_400))) }
            },
            placeholder = "60",
            path = "routing.dns_rewrite_ttl"
        )
    }
}

/** Точечное изменение одного профиля. */
fun updateProfile(
    state: AppState,
    id: String,
    block: (VpnProfile) -> VpnProfile
) {
    state.mutate { current ->
        val updated = current.vpn.profiles.map { if (it.id == id) block(it) else it }
        current.copy(vpn = current.vpn.copy(profiles = updated))
    }
}

// VpnProtocol.label общий (internal в VpnPage.kt): одна реализация на
// все страницы, две копии дали бы ambiguity при вызове.

@Composable
private fun VpnTransport.label(lang: AppLang): String = if (lang == AppLang.RU) ru else en
