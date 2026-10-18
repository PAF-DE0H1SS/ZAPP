package xyz.azraellab.zapp.core.tile

import xyz.azraellab.zapp.core.VpnProfile
import xyz.azraellab.zapp.core.VpnProtocol
import xyz.azraellab.zapp.core.engine.TunnelState
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Виджет рабочего стола: модель статуса и тап, общие с плиткой VPN. */
class WidgetLogicTest {

    private fun profile(name: String, protocol: String = VpnProtocol.WIREGUARD.code) =
        VpnProfile(id = name, name = name, protocol = protocol)

    @Test
    fun activeShowsProfileName() {
        val model = vpnWidgetModel(TunnelState.CONNECTED, profile("Home"))
        assertTrue(model.active)
        assertEquals("Home", model.profileName)
    }

    @Test
    fun disconnectedHidesProfile() {
        val model = vpnWidgetModel(TunnelState.DISCONNECTED, profile("Home"))
        assertFalse(model.active)
        assertEquals("", model.profileName)
    }

    @Test
    fun torSessionIsNotVpnWidget() {
        // tor-мост жив, но виджет VPN за него не отвечает
        val model = vpnWidgetModel(TunnelState.CONNECTED, profile("Bridge", VpnProtocol.TOR.code))
        assertFalse(model.active)
        assertEquals("", model.profileName)
    }

    @Test
    fun connectedWithUnknownProfileStillActive() {
        // туннель поднят -- виджет горит, даже если имя профиля неизвестно
        val model = vpnWidgetModel(TunnelState.CONNECTED, null)
        assertTrue(model.active)
        assertEquals("", model.profileName)
    }

    @Test
    fun tapOnLiveSessionStops() {
        val action = vpnWidgetToggle(TunnelState.CONNECTED, profile("Home"))
        assertEquals(TileToggle.STOP_TUNNEL, action)
    }

    @Test
    fun tapWhenDownStartsVpn() {
        val action = vpnWidgetToggle(TunnelState.DISCONNECTED, profile("Home"))
        assertEquals(TileToggle.START_VPN, action)
    }
}
