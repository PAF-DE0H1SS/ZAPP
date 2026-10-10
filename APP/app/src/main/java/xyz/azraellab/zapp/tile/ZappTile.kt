package xyz.azraellab.zapp.tile

import android.content.Intent
import android.net.VpnService
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import xyz.azraellab.zapp.core.AppState
import xyz.azraellab.zapp.core.engine.isLive
import xyz.azraellab.zapp.core.tile.TileToggle
import xyz.azraellab.zapp.core.tile.torTileActive
import xyz.azraellab.zapp.core.tile.torTileToggle
import xyz.azraellab.zapp.core.tile.vpnTileActive
import xyz.azraellab.zapp.core.tile.vpnTileProfile
import xyz.azraellab.zapp.core.tile.vpnTileToggle
import xyz.azraellab.zapp.core.tile.zapretTileActive
import xyz.azraellab.zapp.core.tile.zapretTileToggle

/**
 * Базовая плитка шторки уведомлений.
 *
 * Плитка живёт отдельно от Activity: процесс может подняться именно
 * из шторки, поэтому тап обязан работать и без открытого окна. Разрешение
 * VPN при этом не спрашивается -- оно читается молча через
 * [VpnService.prepare]; если его нет, окно приложения открывается ровно
 * тогда, когда система готова показать диалог.
 *
 * Состояние пересчитывается на каждом событии туннеля и демона, а выбор
 * профиля в [AppState.config] перечитывается в тот же момент: панель
 * шторки открыта, только пока пользователь не ушёл в приложение.
 */
abstract class ZappTile : TileService() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var listenJob: Job? = null

    override fun onStartListening() {
        super.onStartListening()
        val state = state()
        listenJob = scope.launch {
            combine(state.tunnel.state, state.zapretDaemon.state) { _, _ -> activeNow() }
                .distinctUntilChanged()
                .collect { active -> applyState(active) }
        }
    }

    override fun onStopListening() {
        listenJob?.cancel()
        listenJob = null
        super.onStopListening()
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    override fun onClick() {
        super.onClick()
        // Ошибка тапа не должна ронять сервис: причина и так уйдёт в
        // журнал при открытии приложения.
        runCatching { toggle() }
    }

    /** Горит ли плитка сейчас. */
    protected abstract fun activeNow(): Boolean

    /** Что делает тап. */
    protected abstract fun toggle()

    /** Публикация состояния в систему. */
    protected fun applyState(active: Boolean) {
        val tile = qsTile ?: return
        tile.state = if (active) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
        tile.updateTile()
    }

    /**
     * Состояние процесса, загруженное для работы без окна.
     *
     * Процесс может подняться ровно из шторки: тогда App.kt с его
     * LaunchedEffect ещё не запускался, и без load() конфиг был бы пуст,
     * а демоны -- не подписаны. Повторный вызов дёшев (флаг в load).
     */
    protected fun state(): AppState = AppState.obtain().also { it.load() }

    /** Общий исполнитель решений из общего кода. */
    protected fun toggleFor(action: TileToggle) {
        val s = state()
        when (action) {
            TileToggle.STOP_TUNNEL -> s.disconnectVpn()

            TileToggle.START_VPN -> {
                vpnTileProfile(s.config.vpn.profiles, s.config.vpn.activeProfileId)
                    ?.let { id ->
                        if (id != s.config.vpn.activeProfileId) s.selectProfile(id)
                    }
                consentThen { s.connectVpn() }
            }

            TileToggle.START_TOR -> consentThen { s.connectTor() }
            TileToggle.START_ZAPRET -> s.startZapret()
            TileToggle.STOP_ZAPRET -> s.stopZapret()
        }
    }

    /**
     * Старт туннеля с проверкой consent: без разрешения -- открыть окно.
     *
     * Живая tor-сессия при тапе VPN не мешает: подъём через новый профиль
     * переподнимает туннель сам ([AppState.connectVpn] зовёт `connect()`,
     * а выбор профиля выше уже применён).
     */
    private fun consentThen(start: () -> Unit) {
        if (needsVpnConsent()) openApp() else start()
    }

    /** Разрешение VPN не выдано: показать диалог можно только из окна. */
    private fun needsVpnConsent(): Boolean = try {
        VpnService.prepare(applicationContext) != null
    } catch (_: Exception) {
        true
    }

    /** Открытие приложения: единственный путь задать consent из шторки. */
    private fun openApp() {
        val launch = packageManager.getLaunchIntentForPackage(packageName) ?: return
        launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        runCatching { startActivity(launch) }
    }
}

/** Плитка VPN: обычная сессия, мосты Tor ей чужие. */
class TileVpn : ZappTile() {

    override fun activeNow(): Boolean {
        val s = state()
        return vpnTileActive(s.tunnel.state.value, s.config.vpn.activeProfile())
    }

    override fun toggle() {
        val s = state()
        toggleFor(vpnTileToggle(s.tunnel.state.value, s.config.vpn.activeProfile()))
    }
}

/** Плитка Tor: живая tor-сессия и подъём через выбранный мост. */
class TileTor : ZappTile() {

    override fun activeNow(): Boolean {
        val s = state()
        return torTileActive(s.tunnel.state.value, s.config.vpn.activeProfile())
    }

    override fun toggle() {
        val s = state()
        toggleFor(torTileToggle(s.tunnel.state.value, s.config.vpn.activeProfile()))
    }
}

/** Плитка Zapret: старт/стоп демона обхода. */
class TileZapret : ZappTile() {

    override fun activeNow(): Boolean {
        val s = state()
        return zapretTileActive(s.zapretDaemon.state.value)
    }

    override fun toggle() {
        val s = state()
        toggleFor(zapretTileToggle(s.zapretDaemon.state.value))
    }
}
