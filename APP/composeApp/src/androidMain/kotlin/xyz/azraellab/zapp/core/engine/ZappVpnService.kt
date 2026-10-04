package xyz.azraellab.zapp.core.engine

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.LocalServerSocket
import android.net.LocalSocket
import android.net.LocalSocketAddress
import android.net.VpnService
import android.os.Build
import android.os.IBinder
import android.os.ParcelFileDescriptor
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import xyz.azraellab.zapp.core.AppLang
import xyz.azraellab.zapp.core.AppLangStore
import xyz.azraellab.zapp.core.LinkSpeedBus
import xyz.azraellab.zapp.core.Str
import xyz.azraellab.zapp.core.toDisplayString
import java.io.File
import java.net.InetAddress

/**
 * Форграунд-сервис, который владеет туннелем Android.
 *
 * Порядок работы: движок вызывает establish() -> сервис поднимает
 * Builder -> система отдаёт fd -> сервис отдаёт его ядру через unix-сокет
 * (SCM_RIGHTS) -> ядро запускается и забирает fd сокета. Номер fd в
 * окружении не работает: ProcessBuilder закрывает унаследованные
 * дескрипторы при спавне дочернего процесса.
 * Сервис живёт столько же, сколько туннель: без
 * foreground-службы система убьёт соединение, как только приложение
 * уйдёт в фон.
 */
class ZappVpnService : VpnService() {

    @Volatile private var tun: ParcelFileDescriptor? = null
    @Volatile private var tunDup: ParcelFileDescriptor? = null
    @Volatile private var fdServer: LocalServerSocket? = null
    @Volatile private var fdOwner: LocalSocket? = null

    /** Жизнь тикера скорости: свой scope, чтобы не трогать корутины ядра. */
    private val speedScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var speedJob: Job? = null

    override fun onCreate() {
        super.onCreate()
        instance = this
    }

    override fun onBind(intent: Intent?): IBinder? = super.onBind(intent)

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startAsForeground()

        val params = VpnTunnel.params
        if (params == null) {
            VpnTunnel.fail("no tunnel parameters")
            stopSelf()
            return START_NOT_STICKY
        }

        // Повторный старт: старый туннель и сокет закрываются, иначе fd утекут.
        if (tun != null) closeTunnel()

        val pfd = runCatching { openTunnel(params) }.getOrElse { cause ->
            VpnTunnel.fail("cannot establish tunnel: ${cause.message ?: cause::class.simpleName}")
            stopSelf()
            return START_NOT_STICKY
        }

        tun = pfd
        // Дубль без CLOEXEC: штатный дескриптор VpnService нес этот флаг,
        // а дочернему box наследуются только «чистые» fd.
        val dup = try {
            pfd.dup()
        } catch (e: Exception) {
            pfd
        }
        tunDup = dup
        startFdSender(dup)
        VpnTunnel.deliver(dup.fd)
        return START_NOT_STICKY
    }

    override fun onRevoke() {
        VpnTunnel.fail("VPN permission revoked")
        closeTunnel()
        stopSelf()
    }

    override fun onDestroy() {
        instance = null
        stopSpeedTicker()
        // Scope живёт ровно столько, сколько сервис: после onDestroy
        // инстанса больше нет, и возобновить его некому.
        speedScope.cancel()
        closeTunnel()
        super.onDestroy()
    }

    /**
     * Рвёт туннель снаружи -- из движка, не дожидаясь onDestroy.
     *
     * Система держит сервис живым, пока есть биндинг на активный
     * VpnService: stopService в таком случае не уничтожает сервис,
     * onDestroy не вызывается и fd /dev/tun остаётся открытым. Мёртвый
     * tun0 перехватывает весь трафик устройства (чёрная дыра сети),
     * пока fd не закрыт. Поэтому сначала закрываем fd сами, а сервис
     * останавливаем отдельно.
     */
    fun teardown() {
        stopSpeedTicker()
        closeTunnel()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            stopForeground(STOP_FOREGROUND_REMOVE)
        } else {
            @Suppress("DEPRECATION")
            stopForeground(true)
        }
        stopSelf()
    }

    /**
     * Плашка в уведомлении: пока подключены, в тексте живёт скорость
     * туннеля (↓/↑, обновление раз в секунду от общего канала).
     *
     * Подписка, а не таймер: канал сам шлёт новое значение, а при
     * отключении шлёт null -- уведомление возвращается к базовому
     * тексту и сервис гаснет вместе с туннелем.
     */
    private fun startSpeedTicker() {
        if (speedJob?.isActive == true) return
        speedJob = speedScope.launch {
            LinkSpeedBus.current
                // Уведомление перерисовывается только при смене текста:
                // публикация новой скорости каждую секунду -- не повод
                // дёргать системный UI, когда цифры не изменились.
                .map { speed ->
                    speed?.toDisplayString()
                        ?: Str.NOTIFY_VPN_ON.of(AppLang.of(runCatching { AppLangStore.read() }.getOrNull()) ?: AppLang.EN)
                }
                .distinctUntilChanged()
                .collect { text -> showNotification(text) }
        }
    }

    /**
     * Подписка снимается, но scope остаётся: teardown может случиться
     * при живом сервисе (биндинг системы), и тогда следующий СТАРТ
     * должен снова подписаться на тот же scope.
     */
    private fun stopSpeedTicker() {
        speedJob?.cancel()
        speedJob = null
    }

    private fun openTunnel(params: VpnTunnelParams): ParcelFileDescriptor {
        val builder = Builder()
            .setSession(params.session)
        if (params.mtu in 576..9000) builder.setMtu(params.mtu)

        for (address in params.addresses) {
            val (host, prefix) = splitHostPrefix(address, fallbackPrefix = 32)
            builder.addAddress(InetAddress.getByName(host), prefix)
        }
        for (route in params.routes) {
            val (host, prefix) = splitHostPrefix(route, fallbackPrefix = 0)
            builder.addRoute(InetAddress.getByName(host), prefix)
        }
        for (dns in params.dns) builder.addDnsServer(dns)

        // Свой трафик мимо туннеля обязателен: сокеты box ходят напрямую,
        // иначе их пакеты входят в tun и уходят сами в себя -- петля.
        builder.addDisallowedApplication(packageName)
        for (pkg in params.bypassApps) {
            runCatching { builder.addDisallowedApplication(pkg) }
        }

        return builder.establish()
            ?: error("establish() returned null (permission revoked?)")
    }

    private fun startAsForeground() {
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

        val lang = AppLang.of(runCatching { AppLangStore.read() }.getOrNull())
            ?: AppLang.EN

        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(
                NOTIFICATION_ID,
                buildNotification(Str.NOTIFY_VPN_ON.of(lang)),
                ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
            )
        } else {
            startForeground(NOTIFICATION_ID, buildNotification(Str.NOTIFY_VPN_ON.of(lang)))
        }
        // Дальше уведомление ведёт подписка на скорость: она и обновляет
        // плашку, и возвращает базовый текст при отключении.
        startSpeedTicker()
    }

    /** Одно уведомление на все случаи: заголовок -- имя продукта, текст -- подпись. */
    private fun buildNotification(contentText: String): Notification {
        val icon = resources
            .getIdentifier("ic_launcher", "mipmap", packageName)
            .takeIf { it != 0 } ?: android.R.drawable.ic_dialog_info
        val lang = AppLang.of(runCatching { AppLangStore.read() }.getOrNull())
            ?: AppLang.EN
        return Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(icon)
            .setContentTitle(Str.APP_NAME.of(lang))
            .setContentText(contentText)
            .setOngoing(true)
            .build()
    }

    /**
     * Обновление текста уже показанного уведомления.
     *
     * Тот же id, что у foreground: новая нотификация заменяет старую
     * без дубля в шторке. После teardown подписка снята, поэтому сюда
     * после остановки туннеля ничего не приходит.
     */
    private fun showNotification(contentText: String) {
        val manager = getSystemService(NotificationManager::class.java) ?: return
        manager.notify(NOTIFICATION_ID, buildNotification(contentText))
    }

    /**
     * Раздаёт fd туннеля через unix-сокет: ядро (box) подключается и
     * получает дескриптор сообщением SCM_RIGHTS. Номер fd в окружении
     * бесполезен -- ProcessBuilder вырезает унаследованные fd при спавне.
     *
     * Сокет файловый (в приватном каталоге приложения), а не abstract:
     * abstract-namespace доступен любому приложению, и fd туннеля утёк бы.
     */
    private fun startFdSender(dup: ParcelFileDescriptor) {
        val path = VpnTunnel.fdSocketPath ?: return
        runCatching { File(path).delete() }

        val owner = LocalSocket()
        val bound = runCatching {
            owner.bind(LocalSocketAddress(path, LocalSocketAddress.Namespace.FILESYSTEM))
        }
        bound.exceptionOrNull()?.let { cause ->
            runCatching { owner.close() }
            VpnTunnel.fail("cannot bind fd socket: ${cause.message ?: cause::class.simpleName}")
            closeTunnel()
            stopSelf()
            return
        }
        // LocalServerSocket(fd) вызывает listen() на уже bound сокете;
        // закрывать нужно owner -- у обёртки close() пустует.
        val server = runCatching { LocalServerSocket(owner.fileDescriptor) }
            .getOrElse { cause ->
                runCatching { owner.close() }
                VpnTunnel.fail("cannot listen fd socket: ${cause.message ?: cause::class.simpleName}")
                closeTunnel()
                stopSelf()
                return
            }
        fdOwner = owner
        fdServer = server

        Thread({
            try {
                while (true) {
                    val client = server.accept() ?: break
                    try {
                        client.setFileDescriptorsForSend(arrayOf(dup.fileDescriptor))
                        client.outputStream.write(0)
                        client.outputStream.flush()
                    } finally {
                        runCatching { client.close() }
                    }
                }
            } catch (_: Exception) {
                // Сокет закрыт -- сервис останавливается.
            }
        }, "zapp-tun-fd").apply {
            isDaemon = true
            start()
        }
    }

    private fun closeTunnel() {
        runCatching { fdServer?.close() }
        fdServer = null
        runCatching { fdOwner?.close() }
        fdOwner = null
        VpnTunnel.fdSocketPath?.let { path -> runCatching { File(path).delete() } }
        runCatching { tunDup?.close() }
        tunDup = null
        runCatching { tun?.close() }
        tun = null
    }

    private fun splitHostPrefix(value: String, fallbackPrefix: Int): Pair<String, Int> {
        val slash = value.lastIndexOf('/')
        if (slash <= 0) return value to fallbackPrefix
        val host = value.substring(0, slash)
        val prefix = value.substring(slash + 1).toIntOrNull() ?: fallbackPrefix
        return host to prefix
    }

    internal companion object {
        private const val CHANNEL_ID = "zapp-vpn"
        private const val NOTIFICATION_ID = 43

        /** Живой сервис для teardown() из движка. */
        @Volatile
        internal var instance: ZappVpnService? = null
    }
}
