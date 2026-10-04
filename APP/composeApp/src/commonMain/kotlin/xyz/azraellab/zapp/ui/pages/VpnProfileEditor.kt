package xyz.azraellab.zapp.ui.pages

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import xyz.azraellab.zapp.core.AppLang
import xyz.azraellab.zapp.core.AppState
import xyz.azraellab.zapp.core.Str
import xyz.azraellab.zapp.core.VpnProfile
import xyz.azraellab.zapp.core.VpnProtocol
import xyz.azraellab.zapp.core.VpnTransport
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
        ZappSettingRow(
            title = tr(Str.VPN_KILL_SWITCH),
            checked = state.config.vpn.killSwitch,
            onCheckedChange = { on ->
                state.mutate { it.copy(vpn = it.vpn.copy(killSwitch = on)) }
            }
        )
        ZappSettingRow(
            title = tr(Str.VPN_AUTOSTART),
            checked = state.config.vpn.autoStart,
            onCheckedChange = { on ->
                state.mutate { it.copy(vpn = it.vpn.copy(autoStart = on)) }
            }
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

        Row(horizontalArrangement = Arrangement.spacedBy(AzraelSpace.md)) {
            ZappButton(
                text = tr(Str.VPN_ADD_PROFILE),
                role = ButtonRole.SECONDARY,
                onClick = { addProfile(state) },
                modifier = Modifier.weight(1f)
            )
            ZappButton(
                text = tr(Str.COMMON_DELETE),
                role = ButtonRole.DANGER,
                onClick = { state.deleteProfiles(listOf(profile.id)) },
                modifier = Modifier.weight(1f)
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
