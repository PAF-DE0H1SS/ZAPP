package xyz.azraellab.zapp.core

import java.io.File

/**
 * Счётчики трафика на десктопе.
 *
 * Источник системный и одинаковый для Linux и macOS: `/proc/net/dev` на Linux,
 * а на macOS тот же формат отдаёт `netstat -ibn`. Файл читается целиком и
 * разбирается вручную, потому что готовых библиотек для этого нет, а тянуть
 * зависимость ради семи строк разбора смысла нет.
 *
 * Формат строки:
 * ```
 * eth0: 1234567 890 12345678 901 ... 9876543 210 98765432 220 ... 0 0
 * ```
 * Первые восемь колонок -- приём (bytes, packets, errs, drop, fifo, frame,
 * compressed, multicast), следующие восемь -- передача в том же порядке.
 * Нас интересуют только колонки 0 и 8.
 */
actual fun createTrafficSource(): TrafficSource = ProcTrafficSource()

internal class ProcTrafficSource : TrafficSource {
    private val start = System.nanoTime()

    override fun nowMs(): Long = (System.nanoTime() - start) / 1_000_000L

    override fun read(nowMs: Long): TrafficSample {
        val path = if (isMac()) "/usr/sbin/netstat" else "/proc/net/dev"
        val raw = if (isMac()) readMac(path) else readProc(path)
        return TrafficSample(timestampMs = nowMs, interfaces = raw)
    }

    override fun batteryDrainPercentPerMinute(): Double? = null

    /** Интерфейсы, которые считаем служебными и не показываем в разбивке. */
    private fun isTunnelled(name: String): Boolean =
        name == "lo" || name.startsWith("tun") || name.startsWith("tap") ||
            name.startsWith("wg") || name.startsWith("utun") || name.startsWith("ppp")

    private fun isMac(): Boolean = System.getProperty("os.name").orEmpty().lowercase().contains("mac")

    private fun readProc(path: String): List<InterfaceCounters> {
        val file = File(path)
        if (!file.isFile) return emptyList()
        val lines = runCatching { file.readLines() }.getOrDefault(emptyList())
        return lines.drop(2).mapNotNull { parseProcLine(it) }
    }

    private fun parseProcLine(line: String): InterfaceCounters? {
        val colon = line.indexOf(':')
        if (colon < 0) return null
        val name = line.substring(0, colon).trim()
        if (name.isEmpty()) return null
        val columns = line.substring(colon + 1).trim().split(Regex("\\s+"))
        if (columns.size < 16) return null
        val rx = columns[0].toLongOrNull() ?: return null
        val tx = columns[8].toLongOrNull() ?: return null
        val dropped = columns[3].toLongOrNull() ?: 0L
        return InterfaceCounters(
            name = name,
            rxBytes = rx,
            txBytes = tx,
            droppedBytes = dropped,
            up = rx > 0L || tx > 0L,
            tunnelled = isTunnelled(name)
        )
    }

    /**
     * macOS отдаёт другой текст, поэтому строки приходится склеивать: в выводе
     * `netstat` имя интерфейса идёт в колонке, а не до двоеточия.
     */
    private fun readMac(path: String): List<InterfaceCounters> {
        val text = runCatching {
            ProcessBuilder(path, "-ibn").redirectErrorStream(true).start()
                .inputStream.bufferedReader().readText()
        }.getOrNull() ?: return emptyList()

        return text.lineSequence()
            .drop(1)
            .mapNotNull { line ->
                val columns = line.trim().split(Regex("\\s+"))
                if (columns.size < 10) return@mapNotNull null
                if (columns[0] == "Name") return@mapNotNull null
                val name = columns[0]
                val rx = columns[6].toLongOrNull() ?: return@mapNotNull null
                val tx = columns[9].toLongOrNull() ?: return@mapNotNull null
                InterfaceCounters(
                    name = name,
                    rxBytes = rx,
                    txBytes = tx,
                    up = rx > 0L || tx > 0L,
                    tunnelled = isTunnelled(name)
                )
            }.toList()
    }
}
