package xyz.azraellab.zapp.core.probe

import java.net.ConnectException
import java.net.Inet4Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.security.cert.X509Certificate
import java.util.Random
import javax.net.ssl.SNIHostName
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLException
import javax.net.ssl.SSLSocket
import javax.net.ssl.TrustManager
import javax.net.ssl.X509TrustManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext

/**
 * Сокетные пробы продуктовых целей: прямые и через пробный SOCKS-релей.
 *
 * Успех -- это завершившийся TLS handshake до реального хоста: сертификат
 * не проверяется (для обхода важна дохожд ClientHello, а не подпись), но
 * версия и SNI -- настоящие, иначе проба измеряла бы саму себя. QUIC --
 * отдельная проверка: тот же «Version Negotiation», что и в NetworkProbe.
 *
 * Таймауты взяты короткими: перебор -- это серия проб, и медленный
 * кандидат не должен вставать в очередь к следующему.
 */
internal object ProbeSockets {

    private const val CONNECT_TIMEOUT_MS = 2500
    private const val TLS_TIMEOUT_MS = 3500
    private const val UDP_TIMEOUT_MS = 1500

    /** Продуктовый хост для QUIC-пробы: медиа youtube ходит по QUIC. */
    private const val UDP_PROBE_HOST = "redirector.googlevideo.com"

    /**
     * Прогон всех целей напрямую из приложения.
     *
     * Используется, когда трафик приложения уже обрабатывается пробным
     * инстансом (root: NFQUEUE) -- прокси для пробы не нужен.
     */
    suspend fun runDirect(): ProbeRound = coroutineScope {
        val outcomes = STRATEGY_TARGETS.map { target ->
            async(Dispatchers.IO) { target to directLatency(target.host) }
        }.awaitAll().map { (target, latency) -> TargetOutcome(target, latency) }
        ProbeRound(outcomes, udpOk = quicOk(resolve(UDP_PROBE_HOST)))
    }

    /**
     * Прогон всех целей через SOCKS5-сервер пробного релея (noroot-путь).
     *
     * Релей и есть обход: его разрез ClientHello происходит на пути к
     * серверу, поэтому успех соединения через него == успеху обхода.
     */
    suspend fun runViaSocks(port: Int): ProbeRound = coroutineScope {
        val outcomes = STRATEGY_TARGETS.map { target ->
            async(Dispatchers.IO) { target to socksLatency(port, target.host) }
        }.awaitAll().map { (target, latency) -> TargetOutcome(target, latency) }
        ProbeRound(outcomes, udpOk = quicOk(resolve(UDP_PROBE_HOST)))
    }

    /** Один полный проход: исходы по целям и UDP-флаг. */
    data class ProbeRound(
        val outcomes: List<TargetOutcome>,
        val udpOk: Boolean
    ) {
        fun score(): CandidateScore = CandidateScore(outcomes, udpOk)
    }

    fun resolve(host: String): String? = try {
        val all = InetAddress.getAllByName(host)
        (all.firstOrNull { it is Inet4Address } ?: all.firstOrNull())?.hostAddress
    } catch (e: UnknownHostException) {
        null
    }

    /** Latency прямого handshake или null, если не прошло. */
    private fun directLatency(host: String): Long? {
        val ip = resolve(host) ?: return null
        return tlsLatency(Socket().apply {
            tcpNoDelay = true
            connect(InetSocketAddress(ip, 443), CONNECT_TIMEOUT_MS)
            soTimeout = TLS_TIMEOUT_MS
        }, host)
    }

    /** Latency handshake через SOCKS5 или null. */
    private fun socksLatency(port: Int, host: String): Long? {
        val socket = Socket()
        return try {
            socket.tcpNoDelay = true
            socket.connect(InetSocketAddress("127.0.0.1", port), CONNECT_TIMEOUT_MS)
            socket.soTimeout = TLS_TIMEOUT_MS
            if (!socks5Connect(socket, host)) return null
            tlsLatency(socket, host)
        } catch (e: Exception) {
            null
        } finally {
            runCatching { socket.close() }
        }
    }

    /**
     * TLS handshake поверх уже открытого сокета.
     *
     * Возвращает время handshake в миллисекундах либо null при любом
     * отказе: сброс, таймаут и «сертификат чужой» -- это все «не дошло».
     */
    private fun tlsLatency(raw: Socket, host: String): Long? {
        val sni = try {
            SNIHostName(host)
        } catch (e: IllegalArgumentException) {
            return null
        }
        return try {
            val context = SSLContext.getInstance("TLS").apply {
                init(null, TRUST_ALL, null)
            }
            val started = System.nanoTime()
            raw.use { socket ->
                val ssl = context.socketFactory.createSocket(socket, host, 443, true) as SSLSocket
                ssl.useClientMode = true
                ssl.sslParameters = ssl.sslParameters.apply { serverNames = listOf(sni) }
                ssl.startHandshake()
                ssl.session?.invalidate()
            }
            (System.nanoTime() - started) / 1_000_000L
        } catch (e: SSLException) {
            null
        } catch (e: SocketTimeoutException) {
            null
        } catch (e: ConnectException) {
            null
        } catch (e: Exception) {
            null
        }
    }

    /**
     * SOCKS5 CONNECT по доменному имени: greeting, запрос, разбор ответа.
     *
     * Прокси наш и дружелюбный (метод no-auth), поэтому достаточно
     * байтовой проверки реплики: версия 5 и статус 0.
     */
    private fun socks5Connect(socket: Socket, host: String): Boolean {
        val input = socket.getInputStream()
        val output = socket.getOutputStream()

        output.write(byteArrayOf(0x05, 0x01, 0x00))
        output.flush()
        val greeting = readFully(input, 2) ?: return false
        if (greeting[0] != 0x05.toByte() || greeting[1] != 0x00.toByte()) return false

        val hostBytes = host.toByteArray(Charsets.US_ASCII)
        val request = ByteArray(7 + hostBytes.size)
        request[0] = 0x05
        request[1] = 0x01
        request[2] = 0x00
        request[3] = 0x03
        request[4] = hostBytes.size.toByte()
        hostBytes.copyInto(request, destinationOffset = 5)
        request[5 + hostBytes.size] = 0x01
        request[6 + hostBytes.size] = 0xBB.toByte()
        output.write(request)
        output.flush()

        val response = readFully(input, 4) ?: return false
        if (response[0] != 0x05.toByte() || response[1] != 0x00.toByte()) return false
        return consumeSocksAddress(input, response[3])
    }

    /** Съедает адрес из ответа прокси: IPv4/домен/IPv6. */
    private fun consumeSocksAddress(input: java.io.InputStream, atyp: Byte): Boolean = try {
        when (atyp.toInt() and 0xff) {
            1 -> readFully(input, 4) != null
            3 -> {
                val len = readFully(input, 1)?.get(0)?.toInt() ?: return false
                readFully(input, len) != null
            }
            4 -> readFully(input, 16) != null
            else -> false
        }.also {
            if (it) readFully(input, 2)
        } ?: false
    } catch (e: Exception) {
        false
    }

    private fun readFully(input: java.io.InputStream, size: Int): ByteArray? {
        val buf = ByteArray(size)
        var read = 0
        while (read < size) {
            val n = try {
                input.read(buf, read, size - read)
            } catch (e: Exception) {
                return null
            }
            if (n < 0) return null
            read += n
        }
        return buf
    }

    /**
     * QUIC Version Negotiation: пакет с неизвестной версией на 443/udp.
     *
     * Ответ сервера означает, что UDP до цели доходит; тишина -- что путь
     * закрыт (или сеть без UDP вовсе -- это выдаст прямая проба тут же).
     */
    private fun quicOk(ip: String?): Boolean {
        if (ip == null) return false
        return try {
            java.net.DatagramSocket().use { socket ->
                socket.soTimeout = UDP_TIMEOUT_MS
                val random = Random()
                val dcid = ByteArray(8).also { random.nextBytes(it) }
                val scid = ByteArray(8).also { random.nextBytes(it) }
                val packet = byteArrayOf(
                    0xC0.toByte(),
                    0x0A, 0x0A, 0x0A, 0x0A,
                    8
                ) + dcid + byteArrayOf(8.toByte()) + scid
                val address = InetAddress.getByName(ip)
                socket.send(java.net.DatagramPacket(packet, packet.size, address, 443))
                val buffer = ByteArray(64)
                socket.receive(java.net.DatagramPacket(buffer, buffer.size))
                true
            }
        } catch (e: SocketTimeoutException) {
            false
        } catch (e: Exception) {
            false
        }
    }

    /** Принимаем любые сертификаты: см. комментарий к [tlsLatency]. */
    private val TRUST_ALL: Array<TrustManager> = arrayOf(object : X509TrustManager {
        override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) = Unit
        override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) = Unit
        override fun getAcceptedIssuers(): Array<X509Certificate> = arrayOf()
    })
}
