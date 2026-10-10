package xyz.azraellab.zapp.core.dev

import xyz.azraellab.zapp.core.LinkHealth
import xyz.azraellab.zapp.core.VpnProfile
import xyz.azraellab.zapp.core.VpnProtocol
import xyz.azraellab.zapp.core.daemon.DaemonState
import xyz.azraellab.zapp.core.engine.TunnelState
import xyz.azraellab.zapp.core.tile.TileToggle
import xyz.azraellab.zapp.core.tile.torSession
import xyz.azraellab.zapp.core.tile.torTileActive
import xyz.azraellab.zapp.core.tile.torTileToggle
import xyz.azraellab.zapp.core.tile.vpnTileActive
import xyz.azraellab.zapp.core.tile.vpnTileProfile
import xyz.azraellab.zapp.core.tile.vpnTileToggle
import xyz.azraellab.zapp.core.tile.zapretTileActive
import xyz.azraellab.zapp.core.tile.zapretTileToggle

/**
 * Плитки шторки: когда горят, что делает тап, какой профиль берёт VPN.
 *
 * Логика живёт в core/tile (без Android), поэтому здесь проверяется
 * целиком; сам TileService -- тонкая обвязка над этими решениями.
 */
internal fun tileTests(out: MutableList<DevTest>) {

    fun profile(id: String, protocol: String, alive: Boolean? = null) = VpnProfile(
        id = id,
        name = id,
        protocol = protocol,
        health = LinkHealth(alive = alive)
    )

    val wg = profile("wg", VpnProtocol.WIREGUARD.code, alive = true)
    val wgDead = profile("wg-dead", VpnProtocol.WIREGUARD.code, alive = false)
    val wgUnknown = profile("wg-new", VpnProtocol.WIREGUARD.code)
    val torAlive = profile("tor", VpnProtocol.TOR.code, alive = true)
    val torDead = profile("tor-dead", VpnProtocol.TOR.code, alive = false)

    // --- Сессия ---

    out += DevTest("tile/tor-session", DevCategory.UI, "Плитки: tor-сессия узнаётся по активному профилю") {
        checkTrue(torSession(torAlive), "tor-мост -- tor-сессия")
        checkTrue(torSession(torDead), "мёртвый мост всё равно мост")
        checkFalse(torSession(wg), "обычный профиль -- не tor")
        checkFalse(torSession(null), "без профиля -- не tor")
    }

    // --- Состояния плиток VPN/Tor ---

    out += DevTest("tile/vpn-active", DevCategory.UI, "Плитка VPN: горит на живой не-tor сессии") {
        checkTrue(vpnTileActive(TunnelState.CONNECTED, wg), "подключено -- горит")
        checkTrue(vpnTileActive(TunnelState.CONNECTING, wg), "подъём -- горит")
        checkTrue(vpnTileActive(TunnelState.RECONNECTING, wg), "переподключение -- горит")
        checkFalse(vpnTileActive(TunnelState.DISCONNECTED, wg), "разъединено -- гаснет")
        checkFalse(vpnTileActive(TunnelState.ERROR, wg), "ошибка -- гаснет")
        checkFalse(vpnTileActive(TunnelState.CONNECTED, torAlive), "tor-сессия -- не её плитка")
        checkTrue(vpnTileActive(TunnelState.CONNECTED, null), "без профиля -- мост не доказан, обычная сессия")
    }

    out += DevTest("tile/tor-active", DevCategory.UI, "Плитка Tor: горит на живой tor-сессии") {
        checkTrue(torTileActive(TunnelState.CONNECTED, torAlive), "tor подключён -- горит")
        checkTrue(torTileActive(TunnelState.RECONNECTING, torDead), "переподключение моста -- горит")
        checkFalse(torTileActive(TunnelState.CONNECTED, wg), "не-tor сессия -- гаснет")
        checkFalse(torTileActive(TunnelState.DISCONNECTED, torAlive), "разъединено -- гаснет")
        checkFalse(torTileActive(TunnelState.ERROR, torAlive), "ошибка -- гаснет")
    }

    // --- Состояние и тап плитки Zapret ---

    out += DevTest("tile/zapret-active", DevCategory.UI, "Плитка Zapret: горит на старте и работе") {
        checkTrue(zapretTileActive(DaemonState.STARTING), "стартует -- горит")
        checkTrue(zapretTileActive(DaemonState.RUNNING), "работает -- горит")
        checkFalse(zapretTileActive(DaemonState.STOPPED), "стоит -- гаснет")
        checkFalse(zapretTileActive(DaemonState.ERROR), "упал -- гаснет")
    }

    out += DevTest("tile/toggles", DevCategory.UI, "Плитки: тап ставит и снимает") {
        checkEq(TileToggle.STOP_TUNNEL, vpnTileToggle(TunnelState.CONNECTED, wg), "VPN жив -- снять")
        checkEq(TileToggle.START_VPN, vpnTileToggle(TunnelState.DISCONNECTED, wg), "VPN стоит -- поднять")
        checkEq(
            TileToggle.START_VPN,
            vpnTileToggle(TunnelState.CONNECTED, torAlive),
            "живой tor при тапе VPN -- сменить на VPN"
        )
        checkEq(TileToggle.STOP_TUNNEL, torTileToggle(TunnelState.CONNECTED, torAlive), "Tor жив -- снять")
        checkEq(TileToggle.START_TOR, torTileToggle(TunnelState.DISCONNECTED, torAlive), "Tor стоит -- поднять")
        checkEq(
            TileToggle.START_TOR,
            torTileToggle(TunnelState.CONNECTED, wg),
            "живой VPN при тапе Tor -- сменить на Tor"
        )
        checkEq(TileToggle.START_ZAPRET, zapretTileToggle(DaemonState.STOPPED), "заперт -- старт")
        checkEq(TileToggle.START_ZAPRET, zapretTileToggle(DaemonState.ERROR), "ошибка -- рестарт")
        checkEq(TileToggle.STOP_ZAPRET, zapretTileToggle(DaemonState.RUNNING), "работает -- стоп")
        checkEq(TileToggle.STOP_ZAPRET, zapretTileToggle(DaemonState.STARTING), "стартует -- стоп")
    }

    // --- Выбор профиля для плитки VPN ---

    out += DevTest("tile/profile-pick", DevCategory.UI, "Плитка VPN: свой профиль, мост не берётся") {
        // Активный не-tor остаётся: выбор пользователя плитке не мешает.
        checkEq(wgUnknown.id, vpnTileProfile(listOf(wgDead, wgUnknown, torAlive), wgUnknown.id), "свой профиль")

        // Активный мост (за тор со вкладки Tor) -> первый живой не-tor.
        checkEq(wg.id, vpnTileProfile(listOf(wgDead, wgUnknown, wg, torAlive), torAlive.id), "живые вперёд")

        // Не проверенный -- лучше мёртвого, но хуже живого.
        checkEq(
            wgUnknown.id,
            vpnTileProfile(listOf(wgDead, wgUnknown), torAlive.id),
            "null лучше false"
        )

        // Все -- мосты: возвращается null, старт идёт как есть.
        checkEq(null, vpnTileProfile(listOf(torAlive, torDead), torAlive.id), "только мосты")

        // Пустой список -- нечего выбирать.
        checkEq(null, vpnTileProfile(emptyList(), ""), "нет профилей")
    }
}
