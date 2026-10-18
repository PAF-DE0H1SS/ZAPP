package xyz.azraellab.zapp.core.tile

import xyz.azraellab.zapp.core.VpnProfile
import xyz.azraellab.zapp.core.engine.TunnelState

/** Модель виджета рабочего стола: горит ли и что подписывать. */
data class VpnWidgetModel(
    /** Жива ли не-tor сессия: виджет горит. */
    val active: Boolean,
    /** Имя профиля для подписи; пусто, если не горит. */
    val profileName: String
)

/**
 * Строит модель виджета VPN из состояния туннеля и профиля.
 *
 * Логика статуса и выбора профиля -- общая с плиткой шторки ([vpnTileActive],
 * [vpnTileProfile]): виджет и плитка отвечают на один и тот же вопрос «горит
 * ли VPN», и расхождение между ними было бы поломкой. Отдельная чистая функция
 * -- чтобы решение, что показывает виджет, проверялось без Android.
 */
fun vpnWidgetModel(tunnel: TunnelState, profile: VpnProfile?): VpnWidgetModel {
    val active = vpnTileActive(tunnel, profile)
    return VpnWidgetModel(
        active = active,
        profileName = if (active) profile?.name.orEmpty() else ""
    )
}

/** Тап по виджету: тот же переключатель, что у плитки VPN. */
fun vpnWidgetToggle(tunnel: TunnelState, profile: VpnProfile?): TileToggle =
    vpnTileToggle(tunnel, profile)
