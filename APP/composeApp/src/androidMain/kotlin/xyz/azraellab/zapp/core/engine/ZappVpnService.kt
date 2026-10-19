package xyz.azraellab.zapp.core.engine

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.ConnectivityManager
import android.net.LocalServerSocket
import android.net.LocalSocket
import android.net.LocalSocketAddress
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.VpnService
import android.os.Build
import android.os.IBinder
import android.os.ParcelFileDescriptor
import android.app.ForegroundServiceStartNotAllowedException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import xyz.azraellab.zapp.core.AppLang
import xyz.azraellab.zapp.core.AppLangStore
import xyz.azraellab.zapp.core.AppState
import xyz.azraellab.zapp.core.LinkSpeedBus
import xyz.azraellab.zapp.core.PingBus
import xyz.azraellab.zapp.core.Str
import xyz.azraellab.zapp.core.daemon.daemonLogcat
import xyz.azraellab.zapp.core.log.AppLog
import xyz.azraellab.zapp.core.toDisplayString
import java.io.File
import java.net.Inet6Address
import java.net.InetAddress
import java.util.concurrent.atomic.AtomicBoolean

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

    /**
     * Колбэк физической сети: перечитывает таблицу обхода uid0.
     *
     * Обход держится на имени таблицы текущей дефолтной сети, а оно
     * меняется при Wi-Fi <-> симка. Без этого правила демоны root после
     * переключения снова уходят в собственный туннель.
     *
     * Подписка именно на **физическую** сеть, а не на дефолтную: пока
     * VPN поднят, дефолтной считается он сам, и смена WiFi на симку
     * колбэк дефолтной сети не вызывает -- правило оставалось на таблице
     * выключенного wlan0, обход переставал работать, и uid0 возвращался
     * в туннель с петлёй.
     */
    private var defaultNetCb: ConnectivityManager.NetworkCallback? = null

    /**
     * Сеть, несущая туннель прямо сейчас.
     *
     * Держится для [VpnService.setUnderlyingNetworks] и чтобы отличать
     * «ушла наша сеть» от «ушла чужая»: колбэки приходят на каждую
     * физическую сеть устройства, и трогать подложку при смене второй
     * симки было бы неверно.
     */
    @Volatile
    private var underlying: Network? = null

    /** Флаги дренажа: колбэки сыплются пачками, обход нужен один за последний. */
    private val bypassRefreshPending = AtomicBoolean(false)
    private val bypassRefreshRunning = AtomicBoolean(false)

    override fun onCreate() {
        super.onCreate()
        instance = this
        registerDefaultNetworkCb()
        // Контракт startForegroundService закрывается как можно раньше:
        // если закрытие туннеля обгоняет доставку старт-намерения, onCreate
        // остаётся единственным местом, где startForeground гарантированно
        // вызывается до того, как система успеет пожаловаться.
        startAsForeground()
    }

    override fun onBind(intent: Intent?): IBinder? = super.onBind(intent)

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startAsForeground()

        // Кнопка СТОП в уведомлении: тап приходит сюда отдельным
        // намерением и обязан обработаться ДО проверки параметров --
        // остановка ничего не поднимает, а значит и устанавливать туннель
        // в этом вызове нельзя.
        if (intent?.action == ACTION_STOP) {
            runCatching { AppState.obtain().disconnectVpn() }
                .onFailure { VpnTunnel.fail("stop from notification failed: ${it.message ?: it::class.simpleName}") }
            return START_NOT_STICKY
        }

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
        unregisterDefaultNetworkCb()
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
     * Следит за дефолтной сетью ради таблицы обхода uid0 и ради самой
     * системы.
     *
     * Android переписывает свои маршруты при смене сети сам, а наше
     * правило построено на имени таблицы, которое тогда устаревает --
     * и демоны root возвращаются в туннель с петлёй. Колбэк зовёт
     * [UidZeroBypass.refresh], который без поднятого туннеля -- no-op.
     *
     * Плюс [VpnService.setUnderlyingNetworks]: без него система считает,
     * что туннель висит на сети, которой уже нет, и приложение смотрит
     * на чужую метрику -- «нет интернета» при работающем туннеле.
     */
    private fun registerDefaultNetworkCb() {
        val cm = getSystemService(CONNECTIVITY_SERVICE) as? ConnectivityManager ?: run {
            daemonLogcat(TAG, "default network callback: no ConnectivityManager")
            return
        }
        val cb = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                underlying = network
                tellUnderlying(arrayOf(network))
                refreshBypassLater()
            }

            override fun onLost(network: Network) {
                // Пропала не та сеть, что под туннелем -- трогать нечего.
                if (network != underlying) return
                underlying = null
                tellUnderlying(null)
                refreshBypassLater()
            }
        }
        // NOT_VPN -- реальная сеть под туннелем; у самого VPN этого
        // флага нет, иначе подписка ловила бы только его самого.
        val req = NetworkRequest.Builder()
            .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .addCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN)
            .build()
        runCatching { cm.registerNetworkCallback(req, cb) }
            .onSuccess {
                defaultNetCb = cb
                daemonLogcat(TAG, "underlying network callback: registered")
            }
            .onFailure { daemonLogcat(TAG, "underlying network callback failed: ${it.message}") }
    }

    /**
     * Сообщает системе, какая сеть несёт туннель.
     *
     * null -- «подложки нет»: система перестаёт считать туннель работающим
     * по умолчанию и не шлёт уведомления от имени мёртвой сети. Ошибка
     * здесь не фатальна -- маршруты перепишутся и сами, зовётся поэтому
     * через runCatching и только логируется.
     */
    private fun tellUnderlying(networks: Array<Network>?) {
        runCatching { setUnderlyingNetworks(networks) }
            .onFailure { daemonLogcat(TAG, "setUnderlyingNetworks failed: ${it.message}") }
    }

    /**
     * Перечитывает таблицу обхода uid0 вне потока сети.
     *
     * `su` + `ip` блокируют вызывающего -- в ConnectivityThread это
     * задержало бы все остальные уведомления сети. Пачка колбэков
     * сводится к одному обходу по последнему состоянию: пока обход
     * выполняется, новые запросы ставят только флаг, а поток дорабатывает
     * его в цикле после завершения.
     */
    private fun refreshBypassLater() {
        if (!UidZeroBypass.isActive()) return
        bypassRefreshPending.set(true)
        if (bypassRefreshRunning.getAndSet(true)) return
        Thread {
            try {
                while (bypassRefreshPending.getAndSet(false)) {
                    UidZeroBypass.refresh { line -> daemonLogcat(TAG, line) }
                }
            } finally {
                bypassRefreshRunning.set(false)
            }
        }.start()
    }

    private fun unregisterDefaultNetworkCb() {
        val cb = defaultNetCb ?: return
        defaultNetCb = null
        val cm = getSystemService(CONNECTIVITY_SERVICE) as? ConnectivityManager ?: return
        runCatching { cm.unregisterNetworkCallback(cb) }
    }

    /**
     * Плашка в уведомлении: пока подключены, в тексте живут скорость
     * туннеля (↓/↑, опрос раз в секунду) и пинг (раз в пять секунд).
     *
     * Подписка, а не таймер: каналы сами шлют новые значения, а при
     * отключении шлют null -- уведомление возвращается к базовому
     * тексту и сервис гаснет вместе с туннелем. `combine` нужен, чтобы
     * оба поля рисовались из одного снимка: два отдельных `map` давали
     * бы перерисовку на каждое обновление пинга с устаревшей скоростью.
     */
    private fun startSpeedTicker() {
        if (speedJob?.isActive == true) return
        speedJob = speedScope.launch {
            combine(LinkSpeedBus.current, PingBus.current) { speed, ping ->
                val lang = AppLang.of(runCatching { AppLangStore.read() }.getOrNull()) ?: AppLang.EN
                when {
                    speed == null -> Str.NOTIFY_VPN_ON.of(lang)
                    ping != null -> speed.toDisplayString() + " · $ping ms"
                    else -> speed.toDisplayString()
                }
            }
                // Уведомление перерисовывается только при смене текста:
                // публикация новой скорости каждую секунду -- не повод
                // дёргать системный UI, когда цифры не изменились.
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
        // Отдельный маршрут к каждому v4 DNS-серверу: часть прошивок
        // (Samsung и другие) шлёт системный резолвер прямо к адресу
        // сервера, минуя общий маршрут, и запрос уходит мимо туннеля.
        // /32 не меняет прочую маршрутизацию; если семья адреса не
        // заявлена в tun -- это не наш случай, молча пропускаем.
        for (dns in params.dns) {
            runCatching {
                val addr = InetAddress.getByName(dns)
                if (addr !is Inet6Address) builder.addRoute(addr, 32)
            }
        }

        // Свой трафик мимо туннеля обязателен: сокеты box ходят напрямую,
        // иначе их пакеты входят в tun и уходят сами в себя -- петля.
        //
        // Allowed-режим (per-app) и disallowed-режим у Builder
        // взаимоисключающие: после addAllowedApplication вызов
        // addDisallowedApplication бросает IllegalArgumentException.
        // В allow-режиме всё, чего нет в списке, идёт мимо туннеля, --
        // значит, свой пакет просто не попадает в список, а bypassApps
        // в этом режиме не применяются.
        if (params.allowedApps.isNotEmpty()) {
            for (pkg in params.allowedApps) {
                if (pkg == packageName) continue
                runCatching { builder.addAllowedApplication(pkg) }
            }
        } else {
            builder.addDisallowedApplication(packageName)
            for (pkg in params.bypassApps) {
                runCatching { builder.addDisallowedApplication(pkg) }
            }
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

        // Как в ZappGpsService: отказ foreground-старту (Android 12+)
        // виден в журнале, а не тонет в молчаливом runCatching.
        try {
            if (Build.VERSION.SDK_INT >= 34) {
                startForeground(
                    NOTIFICATION_ID,
                    buildNotification(Str.NOTIFY_VPN_ON.of(lang)),
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
                )
            } else {
                startForeground(NOTIFICATION_ID, buildNotification(Str.NOTIFY_VPN_ON.of(lang)))
            }
        } catch (e: ForegroundServiceStartNotAllowedException) {
            AppLog.log("vpn", "foreground refused: ${e::class.simpleName}")
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
        // Кнопка СТОП: единственный способ снять туннель, не открывая
        // приложение и не вытаскивая шторку до уведомления.
        val stopPending = PendingIntent.getService(
            this,
            REQUEST_STOP,
            Intent(this, ZappVpnService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE
        )
        return Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(icon)
            .setContentTitle(Str.APP_NAME.of(lang))
            .setContentText(contentText)
            .setOngoing(true)
            // Плашка со скоростью и пингом не должна читаться с экрана
            // блокировки: содержимое скрывается целиком, а не по строкам.
            .setVisibility(Notification.VISIBILITY_SECRET)
            .addAction(
                // Иконка 0: кнопка действия рисуется системной.
                android.app.Notification.Action.Builder(
                    0, Str.COMMON_STOP.of(lang), stopPending
                ).build()
            )
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
        private const val TAG = "ZAPP-VPN"

        /** Действие «СТОП» в уведомлении (отдельный старт-интент). */
        private const val ACTION_STOP = "xyz.azraellab.zapp.action.STOP_VPN"

        /** Номер PendingIntent'а кнопки СТОП. */
        private const val REQUEST_STOP = 1

        /** Живой сервис для teardown() из движка. */
        @Volatile
        internal var instance: ZappVpnService? = null
    }
}
