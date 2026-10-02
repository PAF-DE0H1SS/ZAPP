package xyz.azraellab.zapp.ui.pages

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import xyz.azraellab.zapp.core.AppLang
import xyz.azraellab.zapp.core.AppState
import xyz.azraellab.zapp.core.Str
import xyz.azraellab.zapp.core.VpnProtocol
import xyz.azraellab.zapp.core.VpnTransport
import xyz.azraellab.zapp.ui.AppLangState
import xyz.azraellab.zapp.ui.components.Choice
import xyz.azraellab.zapp.ui.components.ZappCard
import xyz.azraellab.zapp.ui.components.ZappChoiceRow
import xyz.azraellab.zapp.ui.components.ZappEmptyState
import xyz.azraellab.zapp.ui.components.ZappNumberField
import xyz.azraellab.zapp.ui.components.ZappSettingRow
import xyz.azraellab.zapp.ui.components.ZappTextField
import xyz.azraellab.zapp.ui.tr

/**
 * Страница VPN.
 *
 * Профили показываются списком, а настройки -- только для выбранного. Держать
 * пятьдесят полей от каждого профиля на одном экране бесполезно: человек
 * работает с одним профилем, и остальные только мешают.
 */
@Composable
fun VpnPage(state: AppState) {
    val config = state.config.vpn
    val active = config.activeProfile()

    PageScaffold(title = tr(Str.TAB_VPN)) {
        ZappCard {
            ZappSettingRow(
                title = tr(Str.TAB_VPN),
                checked = config.enabled,
                onCheckedChange = { on -> state.mutate { it.copy(vpn = it.vpn.copy(enabled = on)) } }
            )
            ZappSettingRow(
                title = tr(Str.VPN_KILL_SWITCH),
                checked = config.killSwitch,
                onCheckedChange = { on -> state.mutate { it.copy(vpn = it.vpn.copy(killSwitch = on)) } }
            )
        }

        PageGroup(tr(Str.VPN_PROFILES)) {
            if (active == null) {
                ZappCard {
                    ZappEmptyState(title = tr(Str.LIST_EMPTY_PROFILES))
                }
            } else {
                VpnProfileCard(state = state)
            }
        }

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
                    title = tr(Str.VPN_SPLIT_TUNNELING),
                    checked = config.splitTunneling,
                    onCheckedChange = { on ->
                        state.mutate { it.copy(vpn = it.vpn.copy(splitTunneling = on)) }
                    }
                )
                if (config.splitTunneling) {
                    // Поле появляется только вместе с переключателем: пока
                    // раздельного туннелирования нет, список исключений
                    // ни на что не влияет.
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

/** Настройки выбранного профиля. */
@Composable
private fun VpnProfileCard(state: AppState) {
    val profile = state.config.vpn.activeProfile() ?: return
    val lang = AppLangState.current

    ZappCard {
        ZappChoiceRow(
            label = tr(Str.VPN_PROTOCOL),
            options = VpnProtocol.entries.map { Choice(it.code, it.label(lang)) },
            selected = profile.protocol,
            onSelect = { code ->
                state.mutate { current ->
                    val updated = current.vpn.profiles.map {
                        if (it.id == profile.id) it.copy(protocol = code) else it
                    }
                    current.copy(vpn = current.vpn.copy(profiles = updated))
                }
            }
        )
        ZappChoiceRow(
            label = tr(Str.VPN_TRANSPORT),
            options = VpnTransport.entries.map { Choice(it.code, it.label(lang)) },
            selected = profile.transport,
            onSelect = { code ->
                state.mutate { current ->
                    val updated = current.vpn.profiles.map {
                        if (it.id == profile.id) it.copy(transport = code) else it
                    }
                    current.copy(vpn = current.vpn.copy(profiles = updated))
                }
            }
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
    }
}

/** Точечное изменение одного профиля. */
private fun updateProfile(state: AppState, id: String, block: (xyz.azraellab.zapp.core.VpnProfile) -> xyz.azraellab.zapp.core.VpnProfile) {
    state.mutate { current ->
        val updated = current.vpn.profiles.map { if (it.id == id) block(it) else it }
        current.copy(vpn = current.vpn.copy(profiles = updated))
    }
}

@Composable
private fun VpnProtocol.label(lang: AppLang): String = if (lang == AppLang.RU) ru else en

@Composable
private fun VpnTransport.label(lang: AppLang): String = if (lang == AppLang.RU) ru else en
