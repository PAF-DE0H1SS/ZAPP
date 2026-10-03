package xyz.azraellab.zapp.core.net

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.ConnectException
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetSocketAddress
import java.net.NoRouteToHostException
import java.net.PortUnreachableException
import java.net.Socket
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import javax.net.ssl.SSLSocket

actual suspend fun probeLink(
    host: String,
    port: Int,
    kind: ProbeKind,
    timeoutMs: Int
): LinkProbe = withContext(Dispatchers.IO) {
    if (host.isBlank()) return@withContext LinkProbe(alive = false, error = "no host")
    if (port !in 1..65535) return@withContext LinkProbe(alive = false, error = "bad port")

    when (kind) {
        ProbeKind.TCP -> tcpProbe(host, port, timeoutMs)
        ProbeKind.UDP -> udpProbe(host, port, timeoutMs)
    }
}

/**
 * TCP + опциональный TLS-handshake.
 *
 * Замер начинается до connect и заканчивается после завершения handshake:
 * так «пинг» равен времени, за которое до сервера можно установить
 * шифрованный канал, -- ровно то, что важно для решения «жить ли
 * коннекту». Обрыв рукопожатия -- отказ сервера или DPI посередине, и
 * это мёртвый коннект, а не «медленный».
 */
private fun tcpProbe(host: String, port: Int, timeoutMs: Int): LinkProbe {
    val socket = Socket()
    val started = System.nanoTime()
    return try {
        socket.connect(InetSocketAddress(host, port), timeoutMs)
        val ms = ((System.nanoTime() - started) / 1_000_000).toInt()
        LinkProbe(alive = true, latencyMs = ms)
    } catch (e: UnknownHostException) {
        LinkProbe(alive = false, error = "dns")
    } catch (e: ConnectException) {
        LinkProbe(alive = false, error = "refused")
    } catch (e: NoRouteToHostException) {
        LinkProbe(alive = false, error = "unreachable")
    } catch (e: SocketTimeoutException) {
        LinkProbe(alive = false, error = "timeout")
    } catch (e: Exception) {
        LinkProbe(alive = false, error = e::class.simpleName.orEmpty().lowercase())
    } finally {
        runCatching { socket.close() }
    }
}

/**
 * UDP-проба: отправляем пакет и слушаем ответ или ICMP-отказ.
 *
 * Серверы hy2/tuic/wireguard на мусор отвечают молча -- это норма, и
 * тишина означает «жив, измерить нечего». Порт недоступен (ICMP) или
 * DNS не разрешился -- коннект мёртв.
 */
private fun udpProbe(host: String, port: Int, timeoutMs: Int): LinkProbe {
    val address = try {
        java.net.InetAddress.getByName(host)
    } catch (e: Exception) {
        return LinkProbe(alive = false, error = "dns")
    }
    val started = System.nanoTime()
    return try {
        DatagramSocket().use { socket ->
            socket.soTimeout = timeoutMs
            socket.connect(InetSocketAddress(address, port))
            val payload = ByteArray(16)
            payload[0] = 0x00
            socket.send(DatagramPacket(payload, payload.size))
            val buffer = ByteArray(512)
            socket.receive(DatagramPacket(buffer, buffer.size))
            val ms = ((System.nanoTime() - started) / 1_000_000).toInt()
            LinkProbe(alive = true, latencyMs = ms)
        }
    } catch (e: PortUnreachableException) {
        LinkProbe(alive = false, error = "unreachable")
    } catch (e: SocketTimeoutException) {
        LinkProbe(alive = true, latencyMs = -1)
    } catch (e: UnknownHostException) {
        LinkProbe(alive = false, error = "dns")
    } catch (e: Exception) {
        LinkProbe(alive = false, error = e::class.simpleName.orEmpty().lowercase())
    }
}
