package xyz.azraellab.zapp.core.dev

import xyz.azraellab.zapp.core.AppTraffic
import xyz.azraellab.zapp.core.InterfaceCounters
import xyz.azraellab.zapp.core.InterfaceTraffic
import xyz.azraellab.zapp.core.LinkSpeed
import xyz.azraellab.zapp.core.TrafficBaseUnit
import xyz.azraellab.zapp.core.TrafficConfig
import xyz.azraellab.zapp.core.TrafficFormat
import xyz.azraellab.zapp.core.TrafficMeter
import xyz.azraellab.zapp.core.TrafficSample
import xyz.azraellab.zapp.core.TrafficUnit
import xyz.azraellab.zapp.core.formatSpeed
import xyz.azraellab.zapp.core.isTunnelSpeedInterface
import xyz.azraellab.zapp.core.linkSpeedBetween
import xyz.azraellab.zapp.core.statusLine
import xyz.azraellab.zapp.core.toDisplayString

/**
 * Мониторинг трафика: форматирование, счётчик скорости, плашка.
 *
 * Две опасные зоны, ради которых и написаны тесты: человеческое
 * форматирование (1023 байта не должны превращаться в «1.0 KiB») и
 * разница счётчиков (перезапущенный интерфейс не должен выдавать
 * отрицательную скорость или ложный ноль там, где трафик есть).
 */
internal fun trafficTests(out: MutableList<DevTest>) {

    // --- Форматирование: байты ---

    val binary = TrafficConfig(unitSystem = TrafficUnit.BINARY.code)
    val decimal = TrafficConfig(unitSystem = TrafficUnit.DECIMAL.code)

    val byteCases = listOf(
        Triple(0L, binary, "0 B"),
        Triple(1L, binary, "1 B"),
        Triple(512L, binary, "512 B"),
        Triple(1023L, binary, "1023 B"),
        Triple(1024L, binary, "1 KiB"),
        Triple(1536L, binary, "1.5 KiB"),
        Triple(10240L, binary, "10 KiB"),
        Triple(1048576L, binary, "1 MiB"),
        Triple(1572864L, binary, "1.5 MiB"),
        Triple(1073741824L, binary, "1 GiB"),
        Triple(999L, decimal, "999 B"),
        Triple(1000L, decimal, "1 kB"),
        Triple(1500L, decimal, "1.5 kB"),
        Triple(1000000L, decimal, "1 MB")
    )
    byteCases.forEach { (value, config, expected) ->
        out += DevTest(
            "traffic/bytes-$expected-hash".replace('/', '-').replace(' ', '_'),
            DevCategory.TRAFFIC,
            "${value}B -> $expected"
        ) {
            checkEq(expected, TrafficFormat.bytes(value, config), "bytes")
        }
    }

    out += DevTest("traffic/bytes-negative", DevCategory.TRAFFIC, "Отрицательный счётчик не падает") {
        checkEq("-5 B", TrafficFormat.bytes(-5L, binary), "формат остаётся")
    }

    // --- Форматирование: скорость ---

    out += DevTest("traffic/speed-bits-binary", DevCategory.TRAFFIC, "Скорость в битах, бинарная") {
        checkEq("8 B/s", TrafficFormat.speed(8.0, binary), "8 бит")
        checkEq("800 B/s", TrafficFormat.speed(800.0, binary), "800 бит")
        checkEq("1 KiB/s", TrafficFormat.speed(1024.0, binary), "Кибибайт")
        checkEq("1.5 KiB/s", TrafficFormat.speed(1536.0, binary), "полтора")
    }

    out += DevTest("traffic/speed-bytes-base", DevCategory.TRAFFIC, "База «байты» убирает i и делит на 8") {
        val bytesBase = TrafficConfig(baseUnit = TrafficBaseUnit.BYTES.code)
        checkEq("1 KB/s", TrafficFormat.speed(8192.0, bytesBase), "без i")
        checkEq("128 B/s", TrafficFormat.speed(1024.0, bytesBase), "байты")
    }

    out += DevTest("traffic/speed-zero", DevCategory.TRAFFIC, "Ноль -- 0 B/s") {
        checkEq("0 B/s", TrafficFormat.speed(0.0, binary), "ноль")
    }

    // --- Форматирование: время ---

    val durationCases = listOf(
        0L to "00:00",
        1000L to "00:01",
        59000L to "00:59",
        60000L to "01:00",
        3599000L to "59:59",
        3600000L to "1:00:00",
        3661000L to "1:01:01",
        86400000L to "24:00:00",
        (-5L) to "00:00"
    )
    durationCases.forEach { (millis, expected) ->
        out += DevTest(
            "traffic/duration-${millis}",
            DevCategory.TRAFFIC,
            "${millis}ms -> $expected"
        ) {
            checkEq(expected, TrafficFormat.duration(millis), "duration")
        }
    }

    // --- Единицы ---

    out += DevTest("traffic/unit-of", DevCategory.TRAFFIC, "TrafficUnit.of") {
        checkEq(TrafficUnit.BINARY, TrafficUnit.of("binary"), "binary")
        checkEq(TrafficUnit.DECIMAL, TrafficUnit.of("DECIMAL"), "decimal")
        checkEq(TrafficUnit.BINARY, TrafficUnit.of("что-то"), "неизвестный")
        checkEq(TrafficUnit.BINARY, TrafficUnit.of(null), "null")
    }

    out += DevTest("traffic/base-unit-of", DevCategory.TRAFFIC, "TrafficBaseUnit.of") {
        checkEq(TrafficBaseUnit.BITS, TrafficBaseUnit.of("bits"), "bits")
        checkEq(TrafficBaseUnit.BYTES, TrafficBaseUnit.of(" bytes "), "bytes")
        checkEq(TrafficBaseUnit.BITS, TrafficBaseUnit.of("что-то"), "неизвестный")
    }

    // --- Счётчик ---

    fun sample(t: Long, vararg ifaces: InterfaceCounters) =
        TrafficSample(timestampMs = t, interfaces = ifaces.toList())

    out += DevTest("traffic/meter-first-sample-zero", DevCategory.TRAFFIC, "Первый срез -- база, скорость ноль") {
        val meter = TrafficMeter()
        val point = meter.update(sample(0, InterfaceCounters("wlan0", 1000, 500)))
        checkEq(0.0, point.totalBitsPerSecond, "скорость")
        checkEq(1500L, point.totalBytes, "байты уже учтены")
        checkEq(1, meter.history().size, "история")
    }

    out += DevTest("traffic/meter-second-sample-speed", DevCategory.TRAFFIC, "Второй срез: разница за интервал") {
        val meter = TrafficMeter()
        meter.update(sample(0, InterfaceCounters("wlan0", 1000, 500)))
        val point = meter.update(sample(1000, InterfaceCounters("wlan0", 3000, 1500)))
        // rx +2000, tx +1000 за 1 с: (2000+1000)*8 = 24000 бит/с
        checkEq(16000.0, point.rxBitsPerSecond, "rx")
        checkEq(8000.0, point.txBitsPerSecond, "tx")
        checkEq(24000.0, point.totalBitsPerSecond, "total")
        checkTrue(point.active, "точка активна")
    }

    out += DevTest("traffic/meter-totals-accumulate", DevCategory.TRAFFIC, "Накопленные итоги складываются") {
        val meter = TrafficMeter()
        meter.update(sample(0, InterfaceCounters("wlan0", 1000, 500)))
        meter.update(sample(1000, InterfaceCounters("wlan0", 3000, 1500)))
        val summary = meter.summary()
        checkEq(3000L, summary.rxBytes, "rx")
        checkEq(1500L, summary.txBytes, "tx")
        checkEq(4500L, summary.totalBytes, "total")
        checkEq(24000.0, summary.currentBitsPerSecond, "текущая")
        checkEq(24000.0, summary.peakBitsPerSecond, "пик")
        checkEq(24000.0, summary.averageBitsPerSecond, "средняя")
        checkEq(1L, summary.activeSeconds, "активная секунда")
        checkEq(0L, summary.sessionStartedAtMs, "старт сессии")
        checkFalse(summary.isIdle(), "не простой")
    }

    out += DevTest("traffic/meter-counter-reset", DevCategory.TRAFFIC, "Уменьшение счётчика -- ноль, не минус") {
        val meter = TrafficMeter()
        meter.update(sample(0, InterfaceCounters("wlan0", 5000, 0)))
        val point = meter.update(sample(1000, InterfaceCounters("wlan0", 100, 0)))
        checkEq(0.0, point.rxBitsPerSecond, "rx после сброса")
        val summary = meter.summary()
        checkEq(5000L, summary.rxBytes, "итоги не откатились")
    }

    out += DevTest("traffic/meter-down-interfaces-ignored", DevCategory.TRAFFIC, "Опущенные интерфейсы не дают скорость") {
        val meter = TrafficMeter(TrafficConfig(activeInterfacesOnly = true))
        meter.update(sample(0, InterfaceCounters("wlan0", 0, 0, up = true)))
        val point = meter.update(sample(1000, InterfaceCounters("wlan0", 9000, 0, up = false)))
        checkEq(0.0, point.rxBitsPerSecond, "interфейс down не считается")
    }

    out += DevTest("traffic/meter-history-trim", DevCategory.TRAFFIC, "История обрезается до points") {
        val config = TrafficConfig(historyPoints = 10)
        val meter = TrafficMeter(config)
        repeat(25) { i ->
            meter.update(sample(i * 1000L, InterfaceCounters("wlan0", i.toLong(), 0)))
        }
        checkEq(10, meter.history().size, "размер истории")
        checkEq(24000L, meter.history().last().timestampMs, "последняя -- самая новая")
    }

    out += DevTest("traffic/meter-reset", DevCategory.TRAFFIC, "Сброс очищает всё") {
        val meter = TrafficMeter()
        meter.update(sample(0, InterfaceCounters("wlan0", 100, 100)))
        meter.update(sample(1000, InterfaceCounters("wlan0", 200, 200)))
        meter.reset()
        checkTrue(meter.history().isEmpty(), "история пуста")
        val summary = meter.summary()
        checkEq(0L, summary.totalBytes, "итоги нулевые")
        checkEq(0.0, summary.peakBitsPerSecond, "пик нулевой")
        val point = meter.update(sample(5000, InterfaceCounters("wlan0", 500, 500)))
        checkEq(0.0, point.totalBitsPerSecond, "после сброса первый срез -- база")
        checkEq(1000L, point.totalBytes, "новая база")
    }

    out += DevTest("traffic/meter-multi-interface", DevCategory.TRAFFIC, "Скорость суммируется по интерфейсам") {
        val meter = TrafficMeter()
        meter.update(sample(0, InterfaceCounters("wlan0", 0, 0), InterfaceCounters("rmnet0", 0, 0)))
        val point = meter.update(
            sample(1000, InterfaceCounters("wlan0", 1000, 0), InterfaceCounters("rmnet0", 3000, 0))
        )
        checkEq(32000.0, point.rxBitsPerSecond, "4000 байт * 8")
    }

    out += DevTest("traffic/meter-per-interface-summary", DevCategory.TRAFFIC, "Разбивка по интерфейсам") {
        val meter = TrafficMeter()
        meter.update(sample(0, InterfaceCounters("wlan0", 100, 10), InterfaceCounters("rmnet0", 50, 5)))
        val summary = meter.summary()
        checkEq(2, summary.byInterface.size, "два интерфейса")
        checkEq(listOf("wlan0", "rmnet0"), summary.byInterface.map { it.name }, "имена")
        val top = summary.topInterfaces(1)
        checkEq("wlan0", top.first().name, "wlan0 больше")
        checkEq(110L, top.first().totalBytes, "итог")
    }

    out += DevTest("traffic/meter-top-apps", DevCategory.TRAFFIC, "Топ приложений по убыванию") {
        val summary = xyz.azraellab.zapp.core.TrafficSummary(
            byApp = listOf(
                AppTraffic("small", 10, 10),
                AppTraffic("big", 1000, 0),
                AppTraffic("mid", 100, 0)
            )
        )
        checkEq(listOf("big", "mid", "small"), summary.topApps(3).map { it.packageName }, "порядок")
        checkEq(110L, AppTraffic("x", 100, 10).totalBytes, "totalBytes")
        checkEq(20L, summary.topApps(3).last().totalBytes, "последний")
    }

    out += DevTest("traffic/sample-helpers", DevCategory.TRAFFIC, "Суммы среза и поиск по имени") {
        val s = TrafficSample(1, listOf(InterfaceCounters("a", 10, 20), InterfaceCounters("b", 30, 40)))
        checkEq(40L, s.rxBytes(), "rx")
        checkEq(60L, s.txBytes(), "tx")
        checkEq(100L, s.totalBytes(), "total")
        checkEq("b", s.interfaceByName("b")?.name, "по имени")
        checkEq(null, s.interfaceByName("zzz"), "нет такого")
        checkEq(30L, InterfaceCounters("c", 10, 20).totalBytes, "iface total")
    }

    // --- Плашка скорости ---

    out += DevTest("traffic/link-speed-tunnels-only", DevCategory.TRAFFIC, "Считаются только туннели") {
        val prev = TrafficSample(0, listOf(InterfaceCounters("wlan0", 0, 0)))
        val current = TrafficSample(1000, listOf(InterfaceCounters("wlan0", 10000, 0)))
        checkEq(null, linkSpeedBetween(prev, current), "обычный интерфейс -- не туннель")
    }

    out += DevTest("traffic/link-speed-computes", DevCategory.TRAFFIC, "Туннель: rx -- вниз, tx -- вверх") {
        val prev = TrafficSample(
            0,
            listOf(InterfaceCounters("tun0", 1000, 500, tunnelled = true))
        )
        val current = TrafficSample(
            1000,
            listOf(InterfaceCounters("tun0", 3000, 1500, tunnelled = true))
        )
        val speed = requireNotNull(linkSpeedBetween(prev, current))
        checkEq(2000L, speed.downBps, "вниз = rx")
        checkEq(1000L, speed.upBps, "вверх = tx")
    }

    out += DevTest("traffic/link-speed-no-baseline-zero", DevCategory.TRAFFIC, "Новый туннель без базы -- ноль, не null") {
        val current = TrafficSample(1000, listOf(InterfaceCounters("tun0", 5000, 0, tunnelled = true)))
        checkEq(LinkSpeed(0, 0), linkSpeedBetween(TrafficSample(0), current), "первый срез")
    }

    out += DevTest("traffic/link-speed-z-prefix", DevCategory.TRAFFIC, "Kernel-WG интерфейсы z* тоже туннель") {
        val prev = TrafficSample(0, listOf(InterfaceCounters("zapp0", 0, 0)))
        val current = TrafficSample(1000, listOf(InterfaceCounters("zapp0", 1024, 256)))
        val speed = requireNotNull(linkSpeedBetween(prev, current))
        checkEq(1024L, speed.downBps, "вниз")
        checkEq(256L, speed.upBps, "вверх")
    }

    out += DevTest("traffic/link-speed-lo-excluded", DevCategory.TRAFFIC, "Loopback -- не туннель") {
        checkFalse(InterfaceCounters("lo", tunnelled = true).isTunnelSpeedInterface(), "lo")
        checkTrue(InterfaceCounters("tun0", tunnelled = true).isTunnelSpeedInterface(), "tun")
        checkTrue(InterfaceCounters("zapp0").isTunnelSpeedInterface(), "z*")
        checkFalse(InterfaceCounters("wlan0").isTunnelSpeedInterface(), "wlan")
        checkFalse(InterfaceCounters("z").isTunnelSpeedInterface(), "z в одиночку")
    }

    out += DevTest("traffic/link-speed-counter-drop", DevCategory.TRAFFIC, "Упавший счётчик туннеля -- ноль, не минус") {
        val prev = TrafficSample(0, listOf(InterfaceCounters("tun0", 5000, 5000, tunnelled = true)))
        val current = TrafficSample(1000, listOf(InterfaceCounters("tun0", 10, 10, tunnelled = true)))
        checkEq(LinkSpeed(0, 0), linkSpeedBetween(prev, current), "ноль")
    }

    out += DevTest("traffic/link-speed-display", DevCategory.TRAFFIC, "Строка плашки") {
        checkEq(
            "↓ 1.5 KiB/s  ↑ 0 B/s",
            LinkSpeed(1536, 0).toDisplayString(),
            "display"
        )
    }

    out += DevTest("traffic/status-line", DevCategory.TRAFFIC, "Плашка: скорость, пинг, режимы") {
        val speed = LinkSpeed(1536, 0)
        checkEq("↓ 1.5 KiB/s  ↑ 0 B/s  (T|Z)", statusLine(true, speed, null, "T|Z"), "без пинга")
        checkEq("↓ 1.5 KiB/s  ↑ 0 B/s · 45 ms  (T|Z)", statusLine(true, speed, 45, "T|Z"), "с пингом")
        checkEq("↓ 1.5 KiB/s  ↑ 0 B/s · 45 ms", statusLine(true, speed, 45, ""), "режимов нет")
        checkEq("↓ …  ↑ …", statusLine(true, null, null, ""), "ещё не посчитали")
        checkEq("↓ …  ↑ … · 45 ms", statusLine(true, null, 45, ""), "пинг без скорости")
        checkEq("(Z|GDPI)", statusLine(false, speed, 45, "Z|GDPI"), "не подключены -- только живые режимы")
        checkEq("", statusLine(false, speed, 45, ""), "не подключены и режимов нет")
    }

    out += DevTest("traffic/format-speed", DevCategory.TRAFFIC, "formatSpeed: целые и дробные") {
        checkEq("0 B/s", formatSpeed(0), "ноль")
        checkEq("0 B/s", formatSpeed(-100), "отрицательное -- ноль")
        checkEq("512 B/s", formatSpeed(512), "байты")
        checkEq("1023 B/s", formatSpeed(1023), "без перехода")
        checkEq("1 KiB/s", formatSpeed(1024), "кило")
        checkEq("1.5 KiB/s", formatSpeed(1536), "полтора")
        checkEq("10 KiB/s", formatSpeed(10240), "десять -- без дроби")
        checkEq("1 MiB/s", formatSpeed(1048576), "мега")
        checkEq("1 GiB/s", formatSpeed(1073741824L), "гига")
    }
}
