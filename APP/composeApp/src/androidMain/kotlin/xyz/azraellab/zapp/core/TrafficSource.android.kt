package xyz.azraellab.zapp.core

/**
 * Счётчики трафика на Android.
 *
 * Основной источник -- нативный код на C++, он читает `/proc/net/dev` с
 * кешированием по времени модификации файла. Если нативная библиотека не
 * загрузилась, читаем тот же файл из Kotlin: функциональность при этом
 * немного ниже, но мониторинг продолжает работать, а не превращается в
 * пустой экран.
 *
 * Для разбивки по приложениям используется `xt_qtaguid`. Файл был удалён из
 * ядра начиная с Android 10, поэтому на новых версиях [perAppSource]
 * вернёт пустой список -- это ожидаемо, а не ошибка.
 */
actual fun createTrafficSource(): TrafficSource = AndroidTrafficSource()

internal class AndroidTrafficSource : TrafficSource {
    private val start = android.os.SystemClock.elapsedRealtime()
    private val proc = ProcReader()
    private val perApp: TrafficSource? = perAppSource()

    override fun nowMs(): Long = android.os.SystemClock.elapsedRealtime() - start

    override fun read(nowMs: Long): TrafficSample {
        val native = NativeTraffic.read()
        val interfaces = if (native.isNotEmpty()) native else proc.readInterfaces()
        return TrafficSample(timestampMs = nowMs, interfaces = interfaces)
    }

    override fun batteryDrainPercentPerMinute(): Double? = null

    /** Срез по приложениям; пустой список, если файла с UID-статистикой нет. */
    fun readPerApp(nowMs: Long): List<AppTraffic> {
        val source = perApp ?: return emptyList()
        val sample = source.read(nowMs)
        return sample.interfaces.map {
            AppTraffic(packageName = it.name, rxBytes = it.rxBytes, txBytes = it.txBytes)
        }
    }

    fun perAppAvailable(): Boolean = perApp != null
}

/** Разбор `/proc/net/dev`. */
internal class ProcReader {
    fun readInterfaces(): List<InterfaceCounters> {
        val text = readFile("/proc/net/dev") ?: return emptyList()
        return text.lineSequence()
            .drop(2)
            .mapNotNull { parseLine(it) }
            .toList()
    }

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
