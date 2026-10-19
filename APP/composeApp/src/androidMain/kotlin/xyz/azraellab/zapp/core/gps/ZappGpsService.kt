package xyz.azraellab.zapp.core.gps

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.app.ForegroundServiceStartNotAllowedException
import androidx.core.content.ContextCompat
import xyz.azraellab.zapp.core.AppLang
import xyz.azraellab.zapp.core.AppLangStore
import xyz.azraellab.zapp.core.AppState
import xyz.azraellab.zapp.core.Str
import xyz.azraellab.zapp.core.log.AppLog

/**
 * Foreground-сервис подмены координат.
 *
 * Цикл подмены живёт в движке, но без foreground-службы система убьёт
 * процесс, как только приложение уйдёт в фон: подмена молча закончится,
 * а пользователь об этом не узнает. Сервис держит процесс живым и
 * показывает одну строку в шторке -- текущие координаты -- с кнопкой
 * СТОП, чтобы снять подмену, не открывая приложение.
 *
 * Живёт в общей библиотеке рядом с движком (как ZappVpnService), а
 * объявление -- в манифесте приложения.
 */
class ZappGpsService : Service() {

    override fun onCreate() {
        super.onCreate()
        instance = this
        // Контракт startForegroundService закрывается как можно раньше:
        // если сервис подняли, а старт-намерение задержалось, onCreate --
        // единственное место, где startForeground гарантированно вызван.
        startAsForeground(pendingText)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startAsForeground(pendingText)

        // Кнопка СТОП в уведомлении: тап приходит отдельным намерением и
        // обрабатывается до всего остального -- стоп ничего не поднимает.
        if (intent?.action == ACTION_STOP) {
            runCatching { AppState.obtain().stopGps() }
            stopSelf()
            return START_NOT_STICKY
        }

        val text = intent?.getStringExtra(EXTRA_TEXT)
        if (text != null) {
            pendingText = text
            updateNotification(text)
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        instance = null
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    // --- Уведомление ---

    private fun startAsForeground(text: String) {
        val manager = getSystemService(NotificationManager::class.java) ?: return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O &&
            manager.getNotificationChannel(CHANNEL_ID) == null
        ) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                // Имя канала -- название продукта, оно не переводится.
                "ZAPP",
                NotificationManager.IMPORTANCE_LOW
            )
            channel.setShowBadge(false)
            manager.createNotificationChannel(channel)
        }

        // Паттерн VPNHotspot (ServiceNotification): Android 12+ может
        // запретить foreground-старт из фона. Обновление уведомления --
        // молча (notify и так в runCatching), а вот отказ стартовать
        // глушить нельзя: подмена продолжает жить в процессе, но служба
        // умрёт, и причина обязана быть видна в журнале.
        try {
            if (Build.VERSION.SDK_INT >= 34) {
                startForeground(
                    NOTIFICATION_ID,
                    buildNotification(text),
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
                )
            } else {
                startForeground(NOTIFICATION_ID, buildNotification(text))
            }
        } catch (e: ForegroundServiceStartNotAllowedException) {
            AppLog.log("gps", "foreground refused: ${e::class.simpleName}")
        }
    }

    /** Обновление текста уже показанного уведомления: тот же id без дубля. */
    private fun updateNotification(text: String) {
        val manager = getSystemService(NotificationManager::class.java) ?: return
        runCatching { manager.notify(NOTIFICATION_ID, buildNotification(text)) }
    }

    private fun buildNotification(contentText: String): Notification {
        val icon = resources
            .getIdentifier("ic_launcher", "mipmap", packageName)
            .takeIf { it != 0 } ?: android.R.drawable.ic_dialog_info
        val lang = AppLang.of(runCatching { AppLangStore.read() }.getOrNull())
            ?: AppLang.EN
        // Кнопка СТОП -- единственный путь снять подмену из шторки.
        val stopPending = PendingIntent.getService(
            this,
            REQUEST_STOP,
            Intent(this, ZappGpsService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE
        )
        return Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(icon)
            .setContentTitle(Str.APP_NAME.of(lang))
            .apply { if (contentText.isNotBlank()) setContentText(contentText) }
            .setOngoing(true)
            // Координаты не должны читаться с экрана блокировки.
            .setVisibility(Notification.VISIBILITY_SECRET)
            .addAction(
                // Иконка 0: кнопка действия рисуется системной.
                android.app.Notification.Action.Builder(
                    0, Str.COMMON_STOP.of(lang), stopPending
                ).build()
            )
            .build()
    }

    companion object {
        private const val CHANNEL_ID = "zapp-gps"
        private const val NOTIFICATION_ID = 44
        private const val ACTION_STOP = "xyz.azraellab.zapp.action.STOP_GPS"
        private const val EXTRA_TEXT = "text"
        private const val REQUEST_STOP = 1

        @Volatile
        private var instance: ZappGpsService? = null

        /** Последний текст: сервис мог быть поднят до первой точки. */
        @Volatile
        private var pendingText: String = ""

        /**
         * Поднимает сервис с первым текстом.
         *
         * Вызов молча игнорируется, если система не даёт поднять службу
         * (фоновый старт на Android 12+): подмена при этом продолжает
         * работать, пока жив процесс, -- служба её страхует, а не включает.
         */
        fun start(context: Context, text: String) {
            pendingText = text
            val intent = Intent(context, ZappGpsService::class.java)
                .putExtra(EXTRA_TEXT, text)
            runCatching {
                ContextCompat.startForegroundService(context, intent)
            }
        }

        /** Обновляет координаты в уже показанном уведомлении. */
        fun update(context: Context, text: String) {
            pendingText = text
            val service = instance ?: return
            runCatching { service.updateNotification(text) }
        }

        /** Снимает уведомление и гасит службу. */
        fun stop(context: Context) {
            runCatching { context.stopService(Intent(context, ZappGpsService::class.java)) }
        }
    }
}
