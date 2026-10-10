package xyz.azraellab.zapp.core.tile

import xyz.azraellab.zapp.core.VpnProfile
import xyz.azraellab.zapp.core.VpnProtocol
import xyz.azraellab.zapp.core.daemon.DaemonState
import xyz.azraellab.zapp.core.engine.TunnelState
import xyz.azraellab.zapp.core.engine.isLive

/**
 * Логика плиток быстрого доступа (шторка уведомлений).
 *
 * Три плитки -- VPN, Tor и Zapret: каждая отвечает за свой кусок строки
 * режимов «T|V|Z» и переключает его тапом. Решение «горит / гаснет» и
 * «что делать при тапе» живёт здесь, отдельно от TileService: плитка --
 * системный класс, который невозможно проверить без устройства, а эта
 * логика обязана быть проверяемой.
 */

/** Что делает тап по плитке в текущем состоянии. */
enum class TileToggle {
    /** Поднять VPN-сессию (не через Tor). */
    START_VPN,

    /** Поднять Tor-сессию. */
    START_TOR,

    /** Снять живую туннель-сессию (общая кнопка остановки). */
    STOP_TUNNEL,

    /** Запустить zapret. */
    START_ZAPRET,

    /** Остановить zapret. */
    STOP_ZAPRET
}

/** Активна ли сессия вообще: туннель жив. */
private fun TunnelState.live(): Boolean = isLive

/** Текущая сессия -- через Tor: активный профиль tor-мост. */
fun torSession(profile: VpnProfile?): Boolean =
    profile?.protocol == VpnProtocol.TOR.code

/** Плитка VPN горит, пока живёт не-tor сессия. */
fun vpnTileActive(tunnel: TunnelState, profile: VpnProfile?): Boolean =
    tunnel.live() && !torSession(profile)

/** Плитка Tor горит, пока живёт tor-сессия. */
fun torTileActive(tunnel: TunnelState, profile: VpnProfile?): Boolean =
    tunnel.live() && torSession(profile)

/** Плитка Zapret горит, пока демон стартует или работает. */
fun zapretTileActive(state: DaemonState): Boolean =
    state == DaemonState.STARTING || state == DaemonState.RUNNING

/** Тап по плитке VPN: остановка живой не-tor сессии, иначе -- подъём VPN. */
fun vpnTileToggle(tunnel: TunnelState, profile: VpnProfile?): TileToggle =
    if (vpnTileActive(tunnel, profile)) TileToggle.STOP_TUNNEL else TileToggle.START_VPN

/** Тап по плитке Tor: остановка живой tor-сессии, иначе -- подъём Tor. */
fun torTileToggle(tunnel: TunnelState, profile: VpnProfile?): TileToggle =
    if (torTileActive(tunnel, profile)) TileToggle.STOP_TUNNEL else TileToggle.START_TOR

/** Тап по плитке Zapret: остановка работающего демона, иначе -- старт. */
fun zapretTileToggle(state: DaemonState): TileToggle =
    if (zapretTileActive(state)) TileToggle.STOP_ZAPRET else TileToggle.START_ZAPRET

/**
 * Профиль, с которого стартует плитка VPN.
 *
 * Активный профиль берётся, если он не tor: под «VPN» пользователь ждёт
 * обычный коннект, а не мост. Иначе -- первый не-tor профиль, живые
 * вперёд: активный мост мог остаться от вкладки Tor, и плитка не должна
 * переключать сессию на мост, который никто не выбирал.
 *
 * null -- не-tor профилей нет вовсе; вызывающий в этом случае стартует
 * как есть (единственный профиль -- мост, лучше его, чем ничего).
 */
fun vpnTileProfile(profiles: List<VpnProfile>, activeId: String): String? {
    val active = profiles.firstOrNull { it.id == activeId }
    if (active != null && !torSession(active)) return active.id
    val candidates = profiles.filter { !torSession(it) }
    if (candidates.isEmpty()) return null
    return candidates
        .sortedBy {
            when (it.health.alive) {
                true -> 0
                false -> 2
                null -> 1
            }
        }
        .first()
        .id
}
