package xyz.azraellab.zapp.core.net

import java.net.ConnectException
import java.net.DatagramPacket
import java.net.DatagramSocket
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
import javax.net.ssl.SSLHandshakeException
import javax.net.ssl.SSLSocket
import javax.net.ssl.TrustManager
import javax.net.ssl.X509TrustManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import xyz.azraellab.zapp.core.AutoStrategy

/**
 * Живая проверка сети для автоподбора стратегии.
 *
 * Логика проверки -- гипотезы: сначала контрольный хост (без него любая
 * «блокировка» -- это просто отсутствие сети), потом поиск реально
 * режущегося хоста, потом три вопроса к нему: помогает ли подмена SNI,
 * живёт ли TLS 1.3 на подменённом SNI, отвечает ли сервер на QUIC.
 *
 * Классификация отказов намеренно грубая: сброс, таймаут и handshake-ошибку
 * отделяем от «платформа не умеет TLS 1.3» и от «DNS не разрешился» -- это
 * разные вещи, и путать их нельзя, иначе на старом Android автоподбор
 * выставит стратегию «DPI режет ClientHello» там, где просто нет TLS 1.3.
 */
actual object NetworkProbe {

    /** Контрольный хост: должен работать без всякого обхода. */
    private const val CONTROL = "github.com"

    /**
     * Хосты, режущиеся в России чаще остальных: берём первый, на котором
     * фильтрация реально видна. Ни один не сработал -- фильтрации нет.
     */
    private val BLOCKED_CANDIDATES = listOf("www.youtube.com", "www.instagram.com", "t.me")

    /** SNI для «подмены»: домен, который серверу всё равно. */
    private const val FAKE_SNI = "probe.invalid"

    private const val TLS12 = "TLSv1.2"
    private const val TLS13 = "TLSv1.3"
    private const val TIMEOUT_MS = 6000
    private const val UDP_TIMEOUT_MS = 2500

    actual suspend fun run(): StrategyReport = withContext(Dispatchers.IO) {
        val notes = mutableListOf<String>()
        val steps = LinkedHashMap<String, ProbeStepResult>()

        val controlIp = resolve(CONTROL)
        if (controlIp == null) {
            notes += "control dns failed: $CONTROL"
            return@withContext unknownReport(notes)
        }

        val control12 = tls(controlIp, CONTROL, TLS12, alpn = null)
        if (control12 != AutoStrategy.ProbeResult.Pass) {
            notes += "control unreachable over $TLS12: $CONTROL"
            return@withContext unknownReport(notes)
        }

        // Ищем хост, который реально режется. Все «заблокированные»
        // проходят -- фильтрации на этой сети нет, ломать нечего.
        var target: Pair<String, String>? = null
        for (host in BLOCKED_CANDIDATES) {
            val ip = resolve(host) ?: continue
            if (tls(ip, host, TLS12, alpn = null) == AutoStrategy.ProbeResult.Blocked) {
                target = host to ip
                break
            }
        }

        val control13 = tls(controlIp, CONTROL, TLS13, alpn = null)
        val controlQuic = quicVersionNegotiation(controlIp)

        if (target == null) {
            notes += "no filtering detected"
            steps["tls_client_hello"] = ProbeStepResult("tls_client_hello", AutoStrategy.ProbeResult.Pass, CONTROL)
            steps["tls13"] = ProbeStepResult("tls13", resultOf(control13), CONTROL)
            steps["sni"] = ProbeStepResult("sni", AutoStrategy.ProbeResult.Pass, "not needed")
            steps["quic"] = ProbeStepResult("quic", resultOf(controlQuic), CONTROL)
            steps["http2_alpn"] = ProbeStepResult(
                "http2_alpn",
                resultOf(tls(controlIp, CONTROL, TLS12, alpn = listOf("h2", "http/1.1"))),
                CONTROL
            )
            return@withContext StrategyReport(
                AutoStrategy.Probe(dpiPresent = false),
                steps.values.toList(),
                notes
            )
        }

        val (host, ip) = target
        notes += "target: $host"

        val fake12 = tls(ip, FAKE_SNI, TLS12, alpn = null)
        val fake13 = tls(ip, FAKE_SNI, TLS13, alpn = null)
        notes += "real:$TLS12=blocked fake:$TLS12=$fake12 fake:$TLS13=$fake13"

        // Помогает ли подмена SNI: реально режут, подменённый -- пускают.
        val sniInspection = fake12 == AutoStrategy.ProbeResult.Pass
        // TLS 1.3 режут отдельно: подменённый SNI живёт на 1.2, но не на 1.3.
        val tls13Blocked = sniInspection &&
            fake13 != AutoStrategy.ProbeResult.Pass &&
            control13 == AutoStrategy.ProbeResult.Pass
        // Подмена не помогла вообще: режут ClientHello или адрес целиком.
        val tlsBlocked = !sniInspection && fake12 == AutoStrategy.ProbeResult.Blocked
        // QUIC: контроль отвечает, а цель -- нет.
        val targetQuic = quicVersionNegotiation(ip)
        val quicBlocked = controlQuic == AutoStrategy.ProbeResult.Pass &&
            targetQuic == AutoStrategy.ProbeResult.Blocked

        // ALPN-шаг: на цели с фейковым SNI (который проходит) просим h2.
        val alpnResult = if (sniInspection) {
            val alpnProbe = tls(ip, FAKE_SNI, TLS12, alpn = listOf("h2", "http/1.1"))
            if (alpnProbe == AutoStrategy.ProbeResult.Blocked) AutoStrategy.ProbeResult.Blocked
            else AutoStrategy.ProbeResult.Pass
        } else {
            AutoStrategy.ProbeResult.Unknown
        }

        steps["tls_client_hello"] = ProbeStepResult(
            "tls_client_hello",
            AutoStrategy.ProbeResult.Blocked,
            host
        )
        steps["tls13"] = ProbeStepResult(
            "tls13",
            if (control13 == AutoStrategy.ProbeResult.Pass) resultOf(fake13)
            else AutoStrategy.ProbeResult.Unknown,
            host
        )
        steps["sni"] = ProbeStepResult(
            "sni",
            if (sniInspection) AutoStrategy.ProbeResult.Blocked else AutoStrategy.ProbeResult.Pass,
            if (sniInspection) "fake sni passes" else "swap does not help"
        )
        steps["quic"] = ProbeStepResult(
            "quic",
            when {
                controlQuic != AutoStrategy.ProbeResult.Pass -> AutoStrategy.ProbeResult.Unknown
                else -> targetQuic
            },
            host
        )
        steps["http2_alpn"] = ProbeStepResult("http2_alpn", resultOf(alpnResult), host)

        val probe = AutoStrategy.Probe(
            tlsBlocked = tlsBlocked,
            tls13Blocked = tls13Blocked,
            quicBlocked = quicBlocked,
            dpiPresent = true,
            sniInspection = sniInspection
        )
        StrategyReport(probe, steps.values.toList(), notes)
    }

    /** Сеть недоступна: все шаги неизвестны, решений из этого не строим. */
    private fun unknownReport(notes: MutableList<String>) = StrategyReport(
        probe = AutoStrategy.Probe(),
        steps = listOf("tls_client_hello", "tls13", "sni", "quic", "http2_alpn").map {
            ProbeStepResult(it, AutoStrategy.ProbeResult.Unknown, "control down")
        },
        notes = notes
    )

    /** Alias: результат проб -- это ветка того же sealed, что и в шагах. */
    private fun resultOf(result: AutoStrategy.ProbeResult): AutoStrategy.ProbeResult = result

    private fun resolve(host: String): String? = try {
        val all = InetAddress.getAllByName(host)
        (all.firstOrNull { it is Inet4Address } ?: all.firstOrNull())?.hostAddress
    } catch (e: UnknownHostException) {
        null
    }

    /**
     * TLS-handshake с явным SNI и версией протокола.
     *
     * Сертификат не проверяем намеренно: для фейкового SNI он всегда
     * «чужой», а важно, дошёл ли ClientHello до сервера вообще. Проверка
     * сертификата здесь лжала бы: DPI-отказ и недоверие к сертификату --
     * один и тот же исход для алгоритма, но разные причины.
     */
    private fun tls(ip: String, sni: String, protocol: String, alpn: List<String>?): AutoStrategy.ProbeResult {
        val sniName = try {
            SNIHostName(sni)
        } catch (e: IllegalArgumentException) {
            return AutoStrategy.ProbeResult.Unknown
        }
        return try {
            val context = SSLContext.getInstance("TLS").apply {
                init(null, TRUST_ALL, null)
            }
            Socket().use { raw ->
                raw.tcpNoDelay = true
                raw.connect(InetSocketAddress(ip, 443), TIMEOUT_MS)
                raw.soTimeout = TIMEOUT_MS
                val ssl = context.socketFactory.createSocket(raw, sni, 443, true) as SSLSocket
                ssl.useClientMode = true
                if (protocol !in ssl.supportedProtocols) {
                    // Платформа без TLS 1.3 (старый Android) -- не про сеть.
                    return AutoStrategy.ProbeResult.Unknown
                }
                ssl.enabledProtocols = arrayOf(protocol)
                if (alpn != null) {
                    // setApplicationProtocols -- Java 9 / API 24: на более
                    // старых платформах ALPN просто не выставляется, и
                    // handshake идёт без него. Рефлексия вместо прямого
                    // вызова нужна из-заjdk-release: компилятор видит
                    // старший API-уровень, чем метод.
                    runCatching {
                        val method = SSLSocket::class.java.getMethod(
                            "setApplicationProtocols",
                            Array<String>::class.java
                        )
                        method.invoke(ssl, alpn.toTypedArray())
                    }
                }
                ssl.sslParameters = ssl.sslParameters.apply { serverNames = listOf(sniName) }
                ssl.startHandshake()
                ssl.session?.invalidate()
                AutoStrategy.ProbeResult.Pass
            }
        } catch (e: SSLHandshakeException) {
            if (e.message?.contains("protocol", ignoreCase = true) == true) {
                AutoStrategy.ProbeResult.Unknown
            } else {
                AutoStrategy.ProbeResult.Blocked
            }
        } catch (e: SSLException) {
            AutoStrategy.ProbeResult.Blocked
        } catch (e: SocketTimeoutException) {
            AutoStrategy.ProbeResult.Blocked
        } catch (e: ConnectException) {
            AutoStrategy.ProbeResult.Blocked
        } catch (e: IllegalArgumentException) {
            AutoStrategy.ProbeResult.Unknown
        } catch (e: Exception) {
            AutoStrategy.ProbeResult.Unknown
        }
    }

    /**
     * QUIC Version Negotiation: пакет с неизвестной версией.
     *
     * Сервер по RFC 9000 обязан ответить списком поддерживаемых версий.
     * Тишина при живом контроле -- сервер не отвечает на UDP вообще, то
     * есть путь к цели по QUIC закрыт. Тишина у контрола тоже возможна
     * (сеть без UDP) -- тогда шаг честно уходит в Unknown.
     */
    private fun quicVersionNegotiation(ip: String): AutoStrategy.ProbeResult {
        return try {
            DatagramSocket().use { socket ->
                socket.soTimeout = UDP_TIMEOUT_MS
                val random = Random()
                val dcid = ByteArray(8).also { random.nextBytes(it) }
                val scid = ByteArray(8).also { random.nextBytes(it) }
                val packet = byteArrayOf(
                    0xC0.toByte(),          // long header, Initial
                    0x0A, 0x0A, 0x0A, 0x0A, // неизвестная версия
                    8                       // длина DCID
                ) + dcid + byteArrayOf(8.toByte()) + scid
                val address = InetAddress.getByName(ip)
                socket.send(DatagramPacket(packet, packet.size, address, 443))
                val buffer = ByteArray(64)
                socket.receive(DatagramPacket(buffer, buffer.size))
                AutoStrategy.ProbeResult.Pass
            }
        } catch (e: SocketTimeoutException) {
            AutoStrategy.ProbeResult.Blocked
        } catch (e: Exception) {
            AutoStrategy.ProbeResult.Unknown
        }
    }

    /** Принимаем любые сертификаты: см. комментарий к [tls]. */
    private val TRUST_ALL: Array<TrustManager> = arrayOf(object : X509TrustManager {
        override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) = Unit
        override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) = Unit
        override fun getAcceptedIssuers(): Array<X509Certificate> = arrayOf()
    })
}
