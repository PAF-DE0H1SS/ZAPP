package xyz.azraellab.zapp.core

import xyz.azraellab.zapp.core.log.AppLog
import xyz.azraellab.zapp.core.root.RootShell

/**
 * Счётчики трафика на Android.
 *
 * Путь выбора -- по убыванию дешевизны:
 *
 *  1. Нативный читатель `/proc/net/dev` -- на устройствах, где файл открыт.
 *  2. sysfs `/sys/class/net/<имя>/statistics/rx_bytes` -- где proc закрыт, а
 *     sysfs нет.
 *  3. Сам proc из Kotlin -- то же, только дороже.
 *  4. `su -c cat /proc/net/dev` --Samsung (S24) закрывает uid'у и proc, и
 *     sysfs, и netlink; приложение и так работает с root, поэтому последний
 *     рубеж -- прочитать файл от имени root. Результат кешируется на
 *     полсекунды, чтобы окно трафика (опрос 200 мс) не порождало лишние
 *     процессы su.
 *
 * Имена интерфейсов отдельно не нужны: любой успешный путь отдаёт и имена,
 * и числа в одном срезе.
 */
actual fun createTrafficSource(): TrafficSource = AndroidTrafficSource()

internal class AndroidTrafficSource : TrafficSource {
    private val start = android.os.SystemClock.elapsedRealtime()
    private val proc = ProcReader()
    private val perApp: TrafficSource? = perAppSource()

    private val rootLock = Any()
    private var rootCacheAtMs = 0L
    private var rootCache: List<InterfaceCounters> = emptyList()
    private var rootLogged = false

    override fun nowMs(): Long = android.os.SystemClock.elapsedRealtime() - start

    override fun read(nowMs: Long): TrafficSample {
        val native = NativeTraffic.read()
        if (native.isNotEmpty()) return TrafficSample(timestampMs = nowMs, interfaces = native)
        val sysfs = sysfsInterfaces()
        if (sysfs.isNotEmpty()) return TrafficSample(timestampMs = nowMs, interfaces = sysfs)
        val proc = proc.readInterfaces()
        if (proc.isNotEmpty()) return TrafficSample(timestampMs = nowMs, interfaces = proc)
        val (at, root) = rootProc(nowMs)
        return TrafficSample(timestampMs = at, interfaces = root)
    }

    override fun batteryDrainPercentPerMinute(): Double? = null

    /**
     * Срез через `su`; пара -- метка времени САМОГО ЧТЕНИЯ, а не запроса.
     *
     * Кеш отдаёт старую метку намеренно: дельты считаются по timestamp
     * среза, и подмена его на «сейчас» ускорила бы скорость в пустом месте.
     */
    private fun rootProc(nowMs: Long): Pair<Long, List<InterfaceCounters>> {
        if (!RootShell.available()) return nowMs to emptyList()
        synchronized(rootLock) {
            val age = nowMs - rootCacheAtMs
            if (age in 0 until ROOT_CACHE_MS && rootCache.isNotEmpty()) {
                return rootCacheAtMs to rootCache
            }
            val result = RootShell.run("cat /proc/net/dev", ROOT_TIMEOUT_MS)
            if (!result.ok || result.output.isBlank()) return nowMs to emptyList()
            val list = proc.parseText(result.output)
            rootCache = list
            rootCacheAtMs = nowMs
            if (!rootLogged && list.isNotEmpty()) {
                rootLogged = true
                AppLog.log("traffic", "counters via su: /proc and sysfs denied for uid")
            }
            return nowMs to list
        }
    }

    /** Срез по приложениям; пустой список, если файла с UID-статистикой нет. */
    fun readPerApp(nowMs: Long): List<AppTraffic> {
        val source = perApp ?: return emptyList()
        val sample = source.read(nowMs)
        return sample.interfaces.map {
            AppTraffic(packageName = it.name, rxBytes = it.rxBytes, txBytes = it.txBytes)
        }
    }

    fun perAppAvailable(): Boolean = perApp != null

    private companion object {
        const val ROOT_CACHE_MS = 500L
        const val ROOT_TIMEOUT_MS = 1500L
    }
}

/**
 * Срез всех интерфейсов из sysfs.
 *
 * Имена -- из `NetworkInterface`, числа -- из `statistics/`: каталог
 * `/sys/class/net` приложение не может прочитать целиком (нет права на
 * листинг), но traversal по известному имени разрешён. Интерфейс, счётчик
 * которого не читается, просто выпадает из среза.
 */
internal fun sysfsInterfaces(): List<InterfaceCounters> {
    val names = runCatching {
        java.net.NetworkInterface.getNetworkInterfaces()?.toList()?.map { it.name }
    }.getOrNull().orEmpty()
    if (names.isEmpty()) return emptyList()
    return names.mapNotNull { name -> sysfsCounters(name) }
}

/** Одна строка sysfs-счётчика; null, если интерфейс исчез или файл не читается. */
internal fun sysfsCounters(name: String): InterfaceCounters? {
    val base = "/sys/class/net/$name/statistics/"
    val rx = readCounter(base + "rx_bytes") ?: return null
    val tx = readCounter(base + "tx_bytes") ?: return null
    return InterfaceCounters(
        name = name,
        rxBytes = rx,
        txBytes = tx,
        droppedBytes = readCounter(base + "rx_dropped") ?: 0L,
        up = rx > 0L || tx > 0L,
        tunnelled = isTunnelled(name)
    )
}

private fun readCounter(path: String): Long? = runCatching {
    java.io.File(path).readText().trim().toLong()
}.getOrNull()

/** Служебные интерфейсы: не показываются в разбивке и не участвуют в плашке. */
internal fun isTunnelled(name: String): Boolean =
    name == "lo" || name.startsWith("tun") || name.startsWith("wg") ||
        name.startsWith("ip6tnl") || name.startsWith("ppp")

/** Разбор `/proc/net/dev`. */
internal class ProcReader {
    fun readInterfaces(): List<InterfaceCounters> {
        val text = readFile("/proc/net/dev") ?: return emptyList()
        return parseText(text)
    }

    /** Разбор уже прочитанного текста: общий для прямого чтения и `su`. */
    fun parseText(text: String): List<InterfaceCounters> = text.lineSequence()
        .drop(2)
        .mapNotNull { parseLine(it) }
        .toList()

    private fun parseLine(line: String): InterfaceCounters? {
        val colon = line.indexOf(':')
        if (colon < 0) return null
        val name = line.substring(0, colon).trim()
        if (name.isEmpty()) return null
        val columns = line.substring(colon + 1).trim().split(WHITESPACE)
        if (columns.size < 16) return null
        val rx = columns[0].toLongOrNull() ?: return null
        val tx = columns[8].toLongOrNull() ?: return null
        return InterfaceCounters(
            name = name,
            rxBytes = rx,
            txBytes = tx,
            droppedBytes = columns[3].toLongOrNull() ?: 0L,
            up = rx > 0L || tx > 0L,
            tunnelled = isTunnelled(name)
        )
    }

    private fun isTunnelled(name: String): Boolean =
        name == "lo" || name.startsWith("tun") || name.startsWith("wg") ||
            name.startsWith("ip6tnl") || name.startsWith("ppp")

    private fun readFile(path: String): String? = runCatching {
        java.io.FileInputStream(path).bufferedReader().use { it.readText() }
    }.getOrNull()

    private companion object {
        val WHITESPACE = Regex("\\s+")
    }
}

/** Разбор `xt_qtaguid`; недоступен на Android 10 и новее. */
internal fun perAppSource(): TrafficSource? = runCatching {
    val text = java.io.FileInputStream("/proc/net/xt_qtaguid/stats")
        .bufferedReader().use { it.readText() }
    if (text.isBlank()) null else UidStatsSource(text)
}.getOrNull()

/**
 * Счётчики по UID, а не по имени пакета.
 *
 * Имя пакета доступно только для собственных приложений, поэтому наружу отдаём
 * UID: сопоставление с именами -- задача уровня выше, где есть доступ к списку
 * установленных пакетов.
 */
internal class UidStatsSource(private val text: String) : TrafficSource {
    private var previous: Map<Int, LongArray> = parse(text)
    private var previousMs: Long = 0L

    override fun nowMs(): Long = android.os.SystemClock.elapsedRealtime()

    override fun read(nowMs: Long): TrafficSample {
        val current = previous
        val elapsed = ((nowMs - previousMs).coerceAtLeast(1L)) / 1000.0
        val counters = current.mapValues { (uid, values) ->
            val old = previous[uid] ?: longArrayOf(0L, 0L)
            longArrayOf(
                (values[0] - old[0]).coerceAtLeast(0L),
                (values[1] - old[1]).coerceAtLeast(0L)
            )
        }
        previousMs = nowMs
        return TrafficSample(
            timestampMs = nowMs,
            interfaces = counters.map { (uid, values) ->
                InterfaceCounters(
                    name = "uid:$uid",
                    rxBytes = values[0],
                    txBytes = values[1],
                    up = values[0] > 0L || values[1] > 0L
                )
            }
        )
    }

    override fun batteryDrainPercentPerMinute(): Double? = null

    private fun parse(raw: String): Map<Int, LongArray> {
        val result = mutableMapOf<Int, LongArray>()
        raw.lineSequence().forEach { line ->
            val parts = line.trim().split(WHITESPACE)
            if (parts.size < 4) return@forEach
            val idx = parts[0].indexOf('_')
            val uid = parts[0].substring(0, idx).toIntOrNull() ?: return@forEach
            val set = parts[1]
            if (set != "0" && set != "2") return@forEach
            val rx = parts[3].toLongOrNull() ?: return@forEach
            val tx = parts[4].takeIf { parts.size > 4 }?.toLongOrNull() ?: 0L
            val slot = result.getOrPut(uid) { longArrayOf(0L, 0L) }
            slot[0] += rx
            slot[1] += tx
        }
        return result
    }

    private companion object {
        val WHITESPACE = Regex("\\s+")
    }
}
