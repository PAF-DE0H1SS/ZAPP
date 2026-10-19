package xyz.azraellab.zapp.core.sleep

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import xyz.azraellab.zapp.core.AndroidCtx
import xyz.azraellab.zapp.core.log.AppLog

/**
 * Android: системные рассылки экрана, регистрация на всё время процесса.
 *
 * Ресивер системный, поэтому RECEIVER_NOT_EXPORTED: рассылки SCREEN_ON/OFF
 * приходят от системы и в «не экспортированный» приёмник, а чужим
 * приложениям перехватывать свои события мы не отдаём.
 */
actual object SleepWatch {

    private const val TAG = "sleep"

    private var receiver: BroadcastReceiver? = null

    actual fun start(onSleep: () -> Unit, onWake: () -> Unit) {
        val context = AndroidCtx.current ?: return
        stop()
        val rc = object : BroadcastReceiver() {
            override fun onReceive(ctx: Context, intent: Intent) {
                when (intent.action) {
                    Intent.ACTION_SCREEN_OFF -> onSleep()
                    Intent.ACTION_SCREEN_ON -> onWake()
                }
            }
        }
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_OFF)
            addAction(Intent.ACTION_SCREEN_ON)
        }
        runCatching { context.registerReceiver(rc, filter, Context.RECEIVER_NOT_EXPORTED) }
            .onFailure { AppLog.log(TAG, "register failed: ${it.message}") }
        receiver = rc
    }

    actual fun stop() {
        val context = AndroidCtx.current ?: return
        receiver?.let { rc -> runCatching { context.unregisterReceiver(rc) } }
        receiver = null
    }
}
