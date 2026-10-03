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
import xyz.azraellab.zapp.core.AppLang
import xyz.azraellab.zapp.core.AppLangStore
import xyz.azraellab.zapp.core.Str
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

    private var tun: ParcelFileDescriptor? = null
    private var tunDup: ParcelFileDescriptor? = null
    private var fdServer: LocalServerSocket? = null
    private var fdOwner: LocalSocket? = null

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
        closeTunnel()
        super.onDestroy()
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

        val icon = resources
            .getIdentifier("ic_launcher", "mipmap", packageName)
            .takeIf { it != 0 } ?: android.R.drawable.ic_dialog_info
        val lang = AppLang.of(runCatching { AppLangStore.read() }.getOrNull())
            ?: AppLang.EN

        val notification = Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(icon)
            .setContentTitle(Str.APP_NAME.of(lang))
            .setContentText(Str.NOTIFY_VPN_ON.of(lang))
            .setOngoing(true)
            .build()

        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
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

    private companion object {
        const val CHANNEL_ID = "zapp-vpn"
        const val NOTIFICATION_ID = 43
    }
}
