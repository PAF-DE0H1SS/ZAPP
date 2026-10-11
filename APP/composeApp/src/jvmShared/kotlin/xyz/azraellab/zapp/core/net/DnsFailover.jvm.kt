package xyz.azraellab.zapp.core.net

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetSocketAddress
import java.net.SocketTimeoutException

/**
 * Проба DNS-сервера: настоящий A-запрос по UDP и проверка ответа.
 *
 * Отличие от UDP-пробы [probeLink] не в тонкостях, а в сути: та шлёт мусор
 * и по тишине заключает «жив», потому что ей важен сам факт доставки пакета
 * (серверы hy2/tuic молчат на любое). Здесь молчание -- это отказ: резолвер,
 * не ответивший на свой же вопрос, для туннеля мёртв, даже если порт открыт.
 */
actual suspend fun probeDns(server: String, name: String, timeoutMs: Int): Boolean =
    withContext(Dispatchers.IO) {
        if (server.isBlank()) return@withContext false
        val id = (System.nanoTime() and 0xFFFF).toInt()
        val query = DnsFailover.queryBytes(name, id)
        try {
            DatagramSocket().use { socket ->
                socket.soTimeout = timeoutMs.coerceAtLeast(1)
                socket.connect(InetSocketAddress(server, 53))
                socket.send(DatagramPacket(query, query.size))
                val buffer = ByteArray(512)
                val response = DatagramPacket(buffer, buffer.size)
                socket.receive(response)
                DnsFailover.responseOk(buffer.copyOf(response.length), id)
            }
        } catch (e: SocketTimeoutException) {
            false
        } catch (e: Exception) {
            false
        }
    }
