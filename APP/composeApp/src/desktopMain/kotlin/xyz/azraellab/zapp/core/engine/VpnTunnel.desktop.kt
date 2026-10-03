package xyz.azraellab.zapp.core.engine

/**
 * Desktop: туннель ядро открывает само (`/dev/net/tun`), fd платформе
 * передавать не зачем. Всегда null -- и вызывающий код не различает
 * платформы: env `ZAPP_TUN_FD` просто не заполняется.
 */
actual object VpnTunnel {

    actual val fdSocketPath: String? = null

    actual suspend fun establish(params: VpnTunnelParams): Int? = null

    actual fun close() = Unit
}
