package xyz.azraellab.zapp.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.VpnService
import android.widget.RemoteViews
import xyz.azraellab.zapp.R
import xyz.azraellab.zapp.core.AppLang
import xyz.azraellab.zapp.core.AppLangStore
import xyz.azraellab.zapp.core.AppState
import xyz.azraellab.zapp.core.Str
import xyz.azraellab.zapp.core.resolveLang
import xyz.azraellab.zapp.core.tile.TileToggle
import xyz.azraellab.zapp.core.tile.vpnTileProfile
import xyz.azraellab.zapp.core.tile.vpnWidgetModel
import xyz.azraellab.zapp.core.tile.vpnWidgetToggle

/**
 * Виджет рабочего стола: статус VPN и быстрое переключение без окна.
 *
 * Живёт вне Activity, как и плитка шторки: процесс может подняться из
 * виджета, поэтому тап работает и без открытого окна. Логика «горит /
 * гаснет» и что делать при тапе -- общая с плиткой (чистая
 * [vpnWidgetModel] / [vpnWidgetToggle]), здесь только рендер в RemoteViews
 * и переключение через [AppState].
 */
class VpnWidgetProvider : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == ACTION_TOGGLE) {
            runCatching { performToggle(context) }
            refresh(context)
            return
        }
        if (intent.action == AppWidgetManager.ACTION_APPWIDGET_UPDATE) {
            renderAll(context)
            return
        }
    }

    /** Горит ли виджет и что подписывать; рендер одного id. */
    private fun renderAll(context: Context) {
        val state = AppState.obtain().also { it.load() }
        val model = vpnWidgetModel(state.tunnel.state.value, state.config.vpn.activeProfile())
        val lang = resolveLang(AppLang.of(AppLangStore.read()), AppLangStore.systemCode())
        val status = if (model.active) model.profileName else Str.WIDGET_OFF.of(lang)
        val views = RemoteViews(context.packageName, R.layout.widget_vpn).apply {
            setTextViewText(R.id.widget_status, status)
            setTextColor(
                R.id.widget_title,
                if (model.active) ACTIVE_COLOR else IDLE_COLOR
            )
            setOnClickPendingIntent(R.id.widget_toggle, togglePending(context))
            setOnClickPendingIntent(R.id.widget_root, togglePending(context))
        }
        val manager = AppWidgetManager.getInstance(context)
        val ids = manager.getAppWidgetIds(ComponentName(context, VpnWidgetProvider::class.java))
        ids.forEach { manager.updateAppWidget(it, views) }
    }

    /** Переключение: тот же путь, что у плитки VPN, с проверкой consent. */
    private fun performToggle(context: Context) {
        val state = AppState.obtain().also { it.load() }
        val action = vpnWidgetToggle(state.tunnel.state.value, state.config.vpn.activeProfile())
        when (action) {
            TileToggle.STOP_TUNNEL -> state.disconnectVpn()
            TileToggle.START_VPN -> {
                vpnTileProfile(state.config.vpn.profiles, state.config.vpn.activeProfileId)
                    ?.let { id -> if (id != state.config.vpn.activeProfileId) state.selectProfile(id) }
                consentThen(context) { state.connectVpn() }
            }

            else -> Unit
        }
    }

    private fun consentThen(context: Context, start: () -> Unit) {
        val needs = try {
            VpnService.prepare(context) != null
        } catch (_: Exception) {
            true
        }
        if (needs) openApp(context) else start()
    }

    private fun openApp(context: Context) {
        val launch = context.packageManager.getLaunchIntentForPackage(context.packageName) ?: return
        launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        runCatching { context.startActivity(launch) }
    }

    private fun togglePending(context: Context): PendingIntent {
        val intent = Intent(context, VpnWidgetProvider::class.java).setAction(ACTION_TOGGLE)
        return PendingIntent.getBroadcast(
            context, 0, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    companion object {
        private const val ACTION_TOGGLE = "xyz.azraellab.zapp.widget.TOGGLE"

        private const val ACTIVE_COLOR = 0xFF4CAF50.toInt()

        private const val IDLE_COLOR = 0xFF9E9E9E.toInt()

        /** Перерисовывает все виджеты; зовётся при смене состояния. */
        fun refresh(context: Context) {
            val intent = Intent(context, VpnWidgetProvider::class.java)
                .setAction(AppWidgetManager.ACTION_APPWIDGET_UPDATE)
            val manager = AppWidgetManager.getInstance(context)
            val ids = manager.getAppWidgetIds(ComponentName(context, VpnWidgetProvider::class.java))
            if (ids.isEmpty()) return
            intent.putExtra(AppWidgetManager.EXTRA_APPWIDGET_IDS, ids)
            context.sendBroadcast(intent)
        }
    }
}
