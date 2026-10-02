package xyz.azraellab.zapp.core

import android.util.Log

/**
 * Мост к нативному счётчику.
 *
 * Нативная библиотека может не загрузиться: устройство на чужой архитектуре
 * или `.so`, не попавший в APK. Обе причины не должны ронять приложение,
 * поэтому вызовы обёрнуты, а [available] сообщает вызывающему коду, какой
 * путь использовать.
 *
 * Контракт нативной стороны: CSV `имя,rx,tx,dropped` по строке на интерфейс.
 */
internal object NativeTraffic {
    private const val TAG = "ZappTraffic"

    val available: Boolean = runCatching {
        System.loadLibrary("zapp-traffic")
        true
    }.getOrElse { error ->
        Log.w(TAG, "native traffic source unavailable: ${error.message}")
        false
    }

    /** Разбирает CSV нативной стороны в список счётчиков. */
    fun read(): List<InterfaceCounters> {
        if (!available) return emptyList()
        val csv = runCatching { nativeReadCsv() }.getOrNull() ?: return emptyList()
        return parseCsv(csv)
    }

    fun nowMs(): Long = if (available) runCatching { nativeNowMs() }.getOrDefault(0L) else 0L

    fun invalidate() {
        if (available) runCatching { nativeInvalidate() }
    }

    /** Счётчики служебных интерфейсов полезны для проверки, но не для графика. */
    private fun isTunnelledName(name: String): Boolean =
        name == "lo" || name.startsWith("tun") || name.startsWith("wg") ||
            name.startsWith("ip6tnl") || name.startsWith("ppp")

    private external fun nativeReadCsv(): String?
    private external fun nativeNowMs(): Long
    private external fun nativeInvalidate()
}

/**
 * Разбор CSV счётчиков.
 *
 * Вынесен отдельной функцией без привязки к JNI, чтобы его можно было
 * проверить обычным тестом на десктопе: формат нативной стороны меняется
 * чаще, чем хотелось бы, а ловить ошибку формата на устройстве дорого.
 */
internal fun parseCsv(csv: String): List<InterfaceCounters> =
    csv.lineSequence()
        .mapNotNull { line ->
            val parts = line.split(',')
            if (parts.size < 4) return@mapNotNull null
            val name = parts[0].trim()
            if (name.isEmpty()) return@mapNotNull null
            val rx = parts[1].trim().toLongOrNull() ?: return@mapNotNull null
            val tx = parts[2].trim().toLongOrNull() ?: return@mapNotNull null
            val dropped = parts[3].trim().toLongOrNull() ?: 0L
            InterfaceCounters(
                name = name,
                rxBytes = rx,
                txBytes = tx,
                droppedBytes = dropped,
                up = rx > 0L || tx > 0L,
                tunnelled = name == "lo" || name.startsWith("tun") || name.startsWith("wg") ||
                    name.startsWith("ip6tnl") || name.startsWith("ppp")
            )
        }
        .toList()
