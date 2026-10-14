package xyz.azraellab.zapp.core.dev

import xyz.azraellab.zapp.core.engine.SessionKind
import xyz.azraellab.zapp.core.engine.TunnelState
import xyz.azraellab.zapp.core.engine.shownFor

/**
 * Состояние туннеля глазами вкладки.
 *
 * Туннель один, а вкладок, которые его показывают, две (VPN и Tor), плюс
 * пассивный релей обхода на сборках без root. Раньше обе страницы рисовали
 * одно состояние как своё: подключённый VPN выглядел подключённым Tor,
 * запущенный mост -- подключённым VPN, а релей зажигал обе сразу. Проверяется
 * чистая функция [shownFor] -- та самая, что стоит в карточке, в плашке
 * режимов и в подписи строки состояния.
 */
internal fun sessionTests(out: MutableList<DevTest>) {
    val cat = DevCategory.VPN

    out += DevTest("session/own-shown", cat, "Своя сессия видна целиком") {
        for (kind in listOf(SessionKind.VPN, SessionKind.TOR)) {
            for (state in TunnelState.entries) {
                checkEq(state, state.shownFor(kind, kind), "$kind/$state: своя сессия")
            }
        }
    }

    out += DevTest("session/foreign-hidden", cat, "Чужая сессия не показывается") {
        // Каждая вкладка видит сессию только своей природы: остальные
        // состояния -- чужие и обязаны гаснуть, иначе поднятый
        // VPN светится и на вкладке Tor, и наоборот. Сбой -- отдельная
        // история: он виден на любой вкладке, см. error-shown-anywhere.
        val pairs = listOf(
            SessionKind.VPN to SessionKind.TOR,
            SessionKind.TOR to SessionKind.VPN,
            SessionKind.VPN to SessionKind.DPI,
            SessionKind.TOR to SessionKind.DPI
        )
        for ((tab, owner) in pairs) {
            for (state in TunnelState.entries) {
                val expected = when {
                    state == TunnelState.DISCONNECTED -> state
                    state == TunnelState.ERROR && owner != SessionKind.DPI -> state
                    else -> TunnelState.DISCONNECTED
                }
                checkEq(expected, state.shownFor(tab, owner), "$owner на вкладке $tab/$state")
            }
        }
    }

    out += DevTest("session/error-shown-anywhere", cat, "Сбой виден на любой вкладке") {
        // Пользователь мог уйти на другую вкладку после сбоя, и оставшееся
        // «Выключено» без причины ничего не объясняло: строка с причиной
        // рисуется только при TunnelState.ERROR, поэтому она обязана
        // доиграть до любой активной вкладки.
        for (tab in listOf(SessionKind.VPN, SessionKind.TOR)) {
            for (owner in listOf(SessionKind.VPN, SessionKind.TOR)) {
                checkEq(
                    TunnelState.ERROR,
                    TunnelState.ERROR.shownFor(tab, owner),
                    "сбой $owner виден на вкладке $tab"
                )
            }
        }
    }

    out += DevTest("session/relay-not-a-connection", cat, "Релей обхода не выдаётся за чью-то сессию") {
        // Пассивную сессию поднимает автоматика, а не пользователь: её
        // не должна показывать ни одна из вкладок, иначе включённый zapret
        // выглядит как одновременно включённые VPN и Tor.
        for (state in TunnelState.entries) {
            checkEq(
                TunnelState.DISCONNECTED,
                state.shownFor(SessionKind.VPN, SessionKind.DPI),
                "релей на вкладке VPN/$state"
            )
            checkEq(
                TunnelState.DISCONNECTED,
                state.shownFor(SessionKind.TOR, SessionKind.DPI),
                "релей на вкладке Tor/$state"
            )
        }
    }
}
