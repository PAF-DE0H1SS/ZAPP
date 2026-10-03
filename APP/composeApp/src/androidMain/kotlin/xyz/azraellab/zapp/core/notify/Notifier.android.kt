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

actual fun createNotifier(): Notifier = AndroidNotifier()

/**
 * Уведомления Android.
 *
 * Канал один на всё приложение: события статуса -- не переписка, и
 * разделять их на «важные» и «фоновые» было бы пустой настройкой.
 */
private class AndroidNotifier : Notifier {

    override val supported: Boolean
        get() = AndroidCtx.current != null

    override fun show(title: String, body: String, tag: String) {
        val context = AndroidCtx.current ?: return

        // Разрешение не выдано -- показ молча пропускается: уведомление не
        // стоит системного диалога, тем более что его можно дать в настройках.
        val granted = ContextCompat.checkSelfPermission(
            context, Manifest.permission.POST_NOTIFICATIONS
        ) == PackageManager.PERMISSION_GRANTED
        if (!granted) return

        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        ensureChannel(context, manager)

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

        val notification = android.app.Notification.Builder(context, CHANNEL_ID)
            .setSmallIcon(icon)
            .setContentTitle(title)
            .apply { if (body.isNotBlank()) setContentText(body) }
            .apply { if (contentIntent != null) setContentIntent(contentIntent) }
            .setAutoCancel(true)
            .build()

        runCatching { manager.notify(tag, TAG_ID, notification) }
    }

    private fun ensureChannel(context: Context, manager: NotificationManager) {
        val existing = runCatching { manager.getNotificationChannel(CHANNEL_ID) }.getOrNull()
        if (existing != null) return
        // Имя канала -- название продукта: оно одинаково на всех языках
        // и не требует перевода в общей библиотеке.
        val channel = NotificationChannel(
            CHANNEL_ID,
            "ZAPP",
            NotificationManager.IMPORTANCE_DEFAULT
        )
        runCatching { manager.createNotificationChannel(channel) }
    }

    private companion object {
        const val CHANNEL_ID = "zapp-status"

        /** Один id на все события: уведомление обновляется, а не плодится. */
        const val TAG_ID = 42
    }
}
