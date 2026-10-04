package xyz.azraellab.zapp.core.engine

import android.content.Intent
import android.net.VpnService
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import xyz.azraellab.zapp.core.AndroidCtx
import xyz.azraellab.zapp.core.permissions.AndroidPermissionBridge
import kotlin.coroutines.resume

/**
 * Android: туннель поднимает [ZappVpnService], а ядро получает fd через
 * unix-сокет [fdSocketPath] (SCM_RIGHTS): ProcessBuilder вырезает
 * унаследованные fd при спавне дочернего процесса, номер в окружении
 * ребенку не достаётся.
 *
 * Сервис нужен по двум причинам: система выдаёт fd только через
 * VpnService, а живое соединение в фоне убивают без foreground-службы.
 * Вызов ведёт себя как обычная suspend-функция: либо fd, либо честное
 * исключение, которое движок запишет в журнал.
 */
actual object VpnTunnel {

    @Volatile
    internal var params: VpnTunnelParams? = null

    @Volatile
    private var pending: CompletableDeferred<Int>? = null

    actual val fdSocketPath: String?
        get() = AndroidCtx.current
            ?.filesDir
            ?.let { "${it.absolutePath}/$SOCK_NAME" }

    actual suspend fun establish(params: VpnTunnelParams): Int? {
        val context = AndroidCtx.current
            ?: error("application context is not attached")
        // Нет разрешения -- спрашиваем пользователя, а не падаем молча.
        if (VpnService.prepare(context) != null) {
            val granted = awaitVpnPermission()
            if (!granted) error("VPN permission was denied by the user")
        }

        this.params = params
        val deferred = CompletableDeferred<Int>()
        pending = deferred

        val started = runCatching {
            context.startForegroundService(Intent(context, ZappVpnService::class.java))
        }
        started.exceptionOrNull()?.let { cause ->
            pending = null
            error("cannot start VPN service: ${cause.message ?: cause::class.simpleName}")
        }

        val fd = withTimeoutOrNull(START_TIMEOUT_MS) { deferred.await() }
        if (fd == null) {
            pending = null
            error("VPN service did not open the tunnel in ${START_TIMEOUT_MS / 1000}s")
        }
        return fd
    }

    actual fun close() {
        pending = null
        params = null
        // Система держит сервис биндингом, пока жив туннель: один только
        // stopService сервис не уничтожает, fd /dev/tun остаётся открытым,
        // и мёртвый tun0 перехватывает весь трафик устройства (чёрная
        // дыра). Сначала рвём fd сами, затем снимаем сервис.
        runCatching { ZappVpnService.instance?.teardown() }
        val context = AndroidCtx.current ?: return
        runCatching { context.stopService(Intent(context, ZappVpnService::class.java)) }
    }

    /** Сuspend-обёртка над системным диалогом VPN-консента. */
    private suspend fun awaitVpnPermission(): Boolean = suspendCancellableCoroutine { cont ->
        AndroidPermissionBridge.requestVpn { granted -> cont.resume(granted) }
    }

    /** Зовётся сервисом, когда туннель поднят и fd готов. */
    internal fun deliver(fd: Int) {
        val waiter = pending
        pending = null
        waiter?.complete(fd)
    }

    /** Зовётся сервисом, когда поднять туннель не удалось. */
    internal fun fail(reason: String) {
        val waiter = pending
        pending = null
        waiter?.completeExceptionally(IllegalStateException(reason))
    }

    private const val START_TIMEOUT_MS = 8_000L
    internal const val SOCK_NAME = "zapp-tun.sock"
}
