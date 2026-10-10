package xyz.azraellab.zapp.core.notify

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import androidx.core.content.ContextCompat
import xyz.azraellab.zapp.core.AndroidCtx
import xyz.azraellab.zapp.ui.nav.AppNavRequest

actual fun createNotifier(): Notifier = AndroidNotifier()

/**
 * Уведомления Android.
 *
 * Два канала: обычные события статуса идут в «zapp-status» (как раньше,
 * без всплывания), а уведомление с действием -- в «zapp-alerts» с
 * IMPORTANCE_HIGH: heads-up -- это свойство канала, а не отдельный
 * вызов API, и отказ с кнопкой обязан всплыть, а не ждать шторку.
 */
private class AndroidNotifier : Notifier {

    override val supported: Boolean
        get() = AndroidCtx.current != null

    override fun show(
        title: String,
        body: String,
        tag: String,
        action: NotifierAction?,
        actionLabel: String
    ) {
        val context = AndroidCtx.current ?: return

        // Разрешение не выдано -- показ молча пропускается: уведомление не
        // стоит системного диалога, тем более что его можно дать в настройках.
        val granted = ContextCompat.checkSelfPermission(
            context, Manifest.permission.POST_NOTIFICATIONS
        ) == PackageManager.PERMISSION_GRANTED
        if (!granted) return

        val manager = context.getSystemService(NotificationManager::class.java) ?: return

        val withAction = action != null && actionLabel.isNotBlank()
        val channelId = if (withAction) CHANNEL_ALERT else CHANNEL_STATUS
        ensureChannel(context, manager, channelId, high = withAction)

        // Иконка берётся из ресурсов по имени: общее приложение не знает
        // номера ресурса приложения-обёртки, но знает его имя.
        val icon = context.resources
            .getIdentifier("ic_launcher", "mipmap", context.packageName)
            .takeIf { it != 0 } ?: android.R.drawable.ic_dialog_info

        // Открытие приложения по тапу -- через launch-intent, чтобы не
        // ссылаться на Activity из библиотечного модуля.
        val launch = context.packageManager.getLaunchIntentForPackage(context.packageName)
        val contentIntent = launch?.let {
            PendingIntent.getActivity(
                context, 0, it,
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            )
        }

        val notification = android.app.Notification.Builder(context, channelId)
            .setSmallIcon(icon)
            .setContentTitle(title)
            .apply { if (body.isNotBlank()) setContentText(body) }
            .apply { if (contentIntent != null) setContentIntent(contentIntent) }
            .apply {
                if (withAction && launch != null) {
                    // Копия launch-intent с флагом: мутация оригинала из
                    // PackageManager ломает его для всех последующих тапов.
                    val target = Intent(launch).apply {
                        putExtra(AppNavRequest.EXTRA_OPEN_SETTINGS, true)
                        addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP)
                    }
                    val pending = PendingIntent.getActivity(
                        context, 1, target,
                        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
                    )
                    addAction(
                        // Иконка 0: с API 26 кнопка действия рисуется
                        // системной, значку из старого API там не место.
                        android.app.Notification.Action.Builder(
                            0, actionLabel, pending
                        ).build()
                    )
                }
            }
            .setAutoCancel(true)
            .build()

        runCatching { manager.notify(tag, TAG_ID, notification) }
    }

    private fun ensureChannel(
        context: Context,
        manager: NotificationManager,
        id: String,
        high: Boolean
    ) {
        val existing = runCatching { manager.getNotificationChannel(id) }.getOrNull()
        if (existing != null) return
        // Имя канала -- название продукта: оно одинаково на всех языках
        // и не требует перевода в общей библиотеке.
        val channel = NotificationChannel(
            id,
            if (high) "ZAPP alerts" else "ZAPP",
            if (high) NotificationManager.IMPORTANCE_HIGH
            else NotificationManager.IMPORTANCE_DEFAULT
        )
        runCatching { manager.createNotificationChannel(channel) }
    }

    private companion object {
        const val CHANNEL_STATUS = "zapp-status"

        /** Канал heads-up: уведомления с действием требуют внимания сразу. */
        const val CHANNEL_ALERT = "zapp-alerts"

        /** Один id на все события: уведомление обновляется, а не плодится. */
        const val TAG_ID = 42
    }
}
