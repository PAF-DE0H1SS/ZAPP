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
        // четыре состояния -- чужие и обязаны гаснуть, иначе поднятый
        // VPN светится и на вкладке Tor, и наоборот.
        val pairs = listOf(
            SessionKind.VPN to SessionKind.TOR,
            SessionKind.TOR to SessionKind.VPN,
            SessionKind.VPN to SessionKind.DPI,
            SessionKind.TOR to SessionKind.DPI
        )
        for ((tab, owner) in pairs) {
            for (state in TunnelState.entries) {
                val expected = if (state == TunnelState.DISCONNECTED) {
                    state
                } else {
                    TunnelState.DISCONNECTED
                }
                checkEq(expected, state.shownFor(tab, owner), "$owner на вкладке $tab/$state")
            }
        }
    }

    out += DevTest("session/error-stays-its-own", cat, "Сбой остаётся своей вкладке") {
        // Сбой чужого подключения не должен выпадать на чужую вкладку --
        // там висела бы ошибка сессии, которую пользователь не запускал.
        checkEq(
            TunnelState.ERROR,
            TunnelState.ERROR.shownFor(SessionKind.VPN, SessionKind.VPN),
            "свой сбой виден"
        )
        checkEq(
            TunnelState.DISCONNECTED,
            TunnelState.ERROR.shownFor(SessionKind.TOR, SessionKind.VPN),
            "чужой сбой не виден"
        )
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
