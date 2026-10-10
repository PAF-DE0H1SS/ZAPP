package xyz.azraellab.zapp.core.dpi

import xyz.azraellab.zapp.core.daemon.DpiOverlay
import xyz.azraellab.zapp.core.daemon.daemonLogcat
import xyz.azraellab.zapp.core.probe.RelayTuning
import java.io.InputStream
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.Executors

/**
 * Локальный SOCKS5-релей с разрывом ClientHello -- обход DPI без root.
 *
 * С root обход делает nfqws в NFQUEUE: режет и подменяет пакеты прямо
 * в ядре. Без root NFQUEUE недоступен, но обход можно сделать на уровне
 * данных: box отдаёт целевые TCP-порты сюда, а релей, прежде чем
 * проксировать, рвёт первый TLS-запись на несколько сегментов с паузой --
 * домен из SNI не умещается в первый сегмент, и DPI, не собирающий
 * поток обратно, теряет точку матчинга. Так же устроен сплит у zapret
 * (там ещё и fake-пакеты, но их из userland не подсунуть -- сервер бы
 * принял мусор в поток).
 *
 * Сервер параметризован портом и тюнингом: боевой экземпляр слушает
 * [DpiOverlay.RELAY_PORT] с текущим [DpiOverlay.relay], а автоподбор
 * стратегии поднимает свои инстансы на свободных портах со своими
 * параметрами -- две пробы не мешают друг другу.
 */
internal class SplitRelayServer(
    private val port: Int,
    private val tuning: () -> RelayTuning
) {

    private val CONNECT_TIMEOUT_MS = 10_000

    private val pool = Executors.newCachedThreadPool { r ->
        Thread(r, "zapp-relay-worker").apply { isDaemon = true }
    }

    private var server: ServerSocket? = null

    /** Открывает сокет; null -- успех, текст -- причина отказа. */
    @Synchronized
    fun start(): String? {
        if (server?.isClosed == false) return null
        val opened = try {
            ServerSocket().apply {
                reuseAddress = true
                bind(InetSocketAddress("127.0.0.1", port))
            }
        } catch (e: Exception) {
            val why = e.message ?: e::class.simpleName.orEmpty()
            daemonLogcat("ZAPP-RELAY", "bind failed: $why")
            return "relay port $port unavailable: $why"
        }
        server = opened
        Thread({ acceptLoop(opened) }, "zapp-relay-accept").apply {
            isDaemon = true
            start()
        }
        daemonLogcat("ZAPP-RELAY", "listening on 127.0.0.1:$port")
        return null
    }

    /** Фактический порт (для пробных инстансов на порту 0). */
    @get:Synchronized
    val localPort: Int
        get() = server?.localPort ?: port

    /** Закрывает сокет; открытые сессии доедают сами по закрытию. */
    @Synchronized
    fun stop() {
        runCatching { server?.close() }
        server = null
        daemonLogcat("ZAPP-RELAY", "closed")
    }

    private fun acceptLoop(s: ServerSocket) {
        while (!s.isClosed) {
            val client = try {
                s.accept()
            } catch (e: Exception) {
                if (!s.isClosed) daemonLogcat("ZAPP-RELAY", "accept: ${e.message ?: e::class.simpleName.orEmpty()}")
                break
            }
            pool.execute {
                runCatching { handle(client) }.onFailure {
                    daemonLogcat("ZAPP-RELAY", "session: ${it.message ?: it::class.simpleName.orEmpty()}")
                }
            }
        }
    }

    private fun handle(client: Socket) {
        try {
            client.tcpNoDelay = true
            val input = client.getInputStream()
            val output = client.getOutputStream()

            // Приветствие SOCKS5: версия + число методов.
            val hello = readFully(input, 2) ?: return
            if (hello[0].toInt() != 5) return
            val methods = hello[1].toInt() and 0xff
            readFully(input, methods) ?: return
            output.write(byteArrayOf(0x05, 0x00))
            output.flush()

            // Запрос CONNECT: остальные команды (BIND/UDP) релею не нужны.
            val head = readFully(input, 4) ?: return
            if ((head[1].toInt() and 0xff) != 1) {
                reply(output, 0x07)
                return
            }
            val host = when (val atyp = head[3].toInt() and 0xff) {
                1 -> {
                    val b = readFully(input, 4) ?: return
                    "${b[0].toInt() and 0xff}.${b[1].toInt() and 0xff}.${b[2].toInt() and 0xff}.${b[3].toInt() and 0xff}"
                }
                3 -> {
                    val len = (readFully(input, 1) ?: return)[0].toInt() and 0xff
                    val b = readFully(input, len) ?: return
                    String(b, Charsets.ISO_8859_1)
                }
                4 -> {
                    val b = readFully(input, 16) ?: return
                    java.net.InetAddress.getByAddress(b).hostAddress
                }
                else -> {
                    reply(output, 0x08)
                    return
                }
            }
            val portBytes = readFully(input, 2) ?: return
            val port = ((portBytes[0].toInt() and 0xff) shl 8) or (portBytes[1].toInt() and 0xff)

            val upstream = Socket()
            try {
                upstream.connect(InetSocketAddress(host, port), CONNECT_TIMEOUT_MS)
            } catch (e: Exception) {
                reply(output, 0x05)
                return
            }
            upstream.tcpNoDelay = true
            reply(output, 0x00)

            val upOut = upstream.getOutputStream()
            val upIn = upstream.getInputStream()
            // Первый сегмент от клиента -- обычно ClientHello: он и
            // разрывается; дальше обычное сквозное копирование.
            val toUp = Thread(
                { pumpSplit(input, upOut, upstream, client) },
                "zapp-relay-client-up"
            ).apply { isDaemon = true }
            val toClient = Thread(
                { pump(upIn, output, upstream, client) },
                "zapp-relay-up-client"
            ).apply { isDaemon = true }
            toUp.start()
            toClient.start()
            // Закрытие в finally каждого насоса снимает обе стороны, поэтому
            // join здесь не вечен: один конец умер -- второй разблокирован.
            toUp.join()
            toClient.join()
        } finally {
            runCatching { client.close() }
        }
    }

    /**
     * Копирование клиент -> сеть с разрывом первого TLS-записи.
     *
     * Сплит ищет в ClientHello начало имени хоста SNI и заводит его во
     * второй сегмент: первый уходит целиком и только потом, с паузой,
     * идёт остаток. [RelayTuning.cuts] > 1 рвёт остаток ещё раз по
     * середине -- так делает multisplit и так проверяется, помогает ли
     * двойной разрез. Не-TLS-трафик и не разобранный заголовок проходят
     * как есть; тюнинг читается на каждой новой сессии, чтобы проба
     * меняла параметры без перезапуска сервера.
     */
    private fun pumpSplit(src: InputStream, dst: OutputStream, upstream: Socket, client: Socket) {
        val buf = ByteArray(32 * 1024)
        var first = true
        try {
            while (true) {
                val n = src.read(buf)
                if (n < 0) break
                if (n == 0) continue
                if (first) {
                    first = false
                    val current = tuning().normalized()
                    val splitAt = SplitRelay.splitOffset(buf, n)
                    if (splitAt > 0 && splitAt < n && current.cuts > 0) {
                        // Позиции разрезов: первый -- перед SNI-доменом,
                        // остальные -- равномерно по остатку записи.
                        val positions = ArrayList<Int>(current.cuts)
                        positions.add(splitAt)
                        for (i in 1 until current.cuts) {
                            positions.add(splitAt + ((n - splitAt) * i) / current.cuts)
                        }
                        var from = 0
                        for (pos in positions) {
                            if (pos <= from || pos >= n) continue
                            dst.write(buf, from, pos - from)
                            dst.flush()
                            Thread.sleep(current.delayMs.toLong())
                            from = pos
                        }
                        dst.write(buf, from, n - from)
                        dst.flush()
                        continue
                    }
                }
                dst.write(buf, 0, n)
                dst.flush()
            }
        } catch (e: Exception) {
            daemonLogcat("ZAPP-RELAY", "upstream write: ${e.message ?: e::class.simpleName.orEmpty()}")
        } finally {
            runCatching { upstream.close() }
            runCatching { client.close() }
        }
    }

    /** Обычное копирование в обратную сторону; конец -- закрытие обеих сторон. */
    private fun pump(src: InputStream, dst: OutputStream, upstream: Socket, client: Socket) {
        val buf = ByteArray(32 * 1024)
        try {
            while (true) {
                val n = src.read(buf)
                if (n < 0) break
                if (n == 0) continue
                dst.write(buf, 0, n)
                dst.flush()
            }
        } catch (e: Exception) {
            daemonLogcat("ZAPP-RELAY", "client write: ${e.message ?: e::class.simpleName.orEmpty()}")
        } finally {
            runCatching { upstream.close() }
            runCatching { client.close() }
        }
    }

    private fun reply(out: OutputStream, rep: Int) {
        out.write(byteArrayOf(0x05, rep.toByte(), 0, 0x01, 0, 0, 0, 0, 0, 0))
        out.flush()
    }

    private fun readFully(input: InputStream, n: Int): ByteArray? {
        if (n <= 0) return ByteArray(0)
        val out = ByteArray(n)
        var got = 0
        while (got < n) {
            val r = input.read(out, got, n - got)
            if (r < 0) return null
            got += r
        }
        return out
    }
}

/**
 * Боевой экземпляр релея с жизненным циклом "счётчик заимствований".
 *
 * Zapret и goodbyedpi держат релей вместе, последняя остановка закрывает
 * сокет. Порт один ([DpiOverlay.RELAY_PORT]), двум демонам он и не нужен
 * по отдельности; параметры -- текущие из [DpiOverlay.relay].
 */
internal object SplitRelay {

    private var instance: SplitRelayServer? = null
    private var refs = 0

    /**
     * Держит релей открытым. null -- взяли или уже держали; текст --
     * причина отказа для карточки демона.
     */
    @Synchronized
    fun acquire(): String? {
        val existing = instance
        if (existing != null) {
            refs++
            return null
        }
        val server = SplitRelayServer(DpiOverlay.RELAY_PORT) { DpiOverlay.relay }
        val error = server.start()
        if (error != null) return error
        instance = server
        refs = 1
        return null
    }

    /** Отпускает одну ссылку; на нуле сокет закрывается. */
    @Synchronized
    fun release() {
        if (refs <= 0) return
        refs--
        if (refs > 0) return
        instance?.stop()
        instance = null
    }

    /**
     * Смещение начала имени хоста SNI внутри первого сегмента или -1.
     *
     * Разбор минимальный и строгий: не тот тип рукопожатия, обрезанный
     * буфер или лишние расширения -- -1. Ошибиться вправо нельзя: сегменты
     * склеивает сервер, и неправильное смещение ломает рукопожатие целиком.
     * internal ради теста: структура -- контракт со схемой TLS.
     */
    internal fun splitOffset(buf: ByteArray, n: Int): Int {
        fun b(i: Int) = buf[i].toInt() and 0xff
        if (n < 5 || b(0) != 0x16) return -1 // не TLS-запись -- не трогаем
        var p = 5
        if (n < p + 4 || b(p) != 0x01) return -1 // не ClientHello
        p += 4
        if (n < p + 35) return -1 // legacy_version + random
        p += 2 + 32
        val sidLen = b(p)
        p += 1 + sidLen
        if (n < p + 2) return -1
        p += 2 + ((b(p) shl 8) or b(p + 1)) // cipher_suites
        if (n < p + 1) return -1
        val compLen = b(p)
        p += 1 + compLen
        if (n < p + 2) return -1
        val extLen = ((b(p) shl 8) or b(p + 1))
        p += 2
        val extEnd = minOf(p + extLen, n)
        while (p + 4 <= extEnd) {
            val type = (b(p) shl 8) or b(p + 1)
            val len = (b(p + 2) shl 8) or b(p + 3)
            p += 4
            if (p + len > extEnd) return -1
            if (type == 0x0000 && len >= 5) {
                // server_name: list_len(2), name_type(1), name_len(2), имя.
                val nameLen = (b(p + 3) shl 8) or b(p + 4)
                if (p + 5 + nameLen <= extEnd) return p + 5
                return -1
            }
            p += len
        }
        return -1
    }
}
