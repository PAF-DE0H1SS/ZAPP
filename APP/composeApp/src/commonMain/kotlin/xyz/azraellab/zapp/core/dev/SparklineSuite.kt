package xyz.azraellab.zapp.core.dev

import xyz.azraellab.zapp.core.traffic.downsample
import xyz.azraellab.zapp.core.traffic.normalize
import xyz.azraellab.zapp.core.traffic.sparklinePair
import xyz.azraellab.zapp.core.traffic.sparklinePoints

/**
 * Спарклайн скорости: прореживание, нормализация, границы координат.
 *
 * Проверяется чистая математика из `core/traffic/Sparkline.kt` -- та самая,
 * что исполняет Canvas спарклайна в статусной карточке туннеля.
 */
internal fun sparklineTests(out: MutableList<DevTest>) {
    val cat = DevCategory.TRAFFIC

    out += DevTest("sparkline/downsample-keeps-tail", cat, "Прореживание сохраняет хвост") {
        val values = (1..100).map { it.toDouble() }
        val thinned = downsample(values, 10)
        checkTrue(thinned.size <= 10, "не больше лимита: ${thinned.size}")
        checkEq(100.0, thinned.last(), "последнее значение -- всегда хвост")
        checkEq(10.0, thinned.first(), "шаг 10: первое -- 10, а не 1")
    }

    out += DevTest("sparkline/downsample-small", cat, "Малая история не трогается") {
        val values = listOf(1.0, 2.0, 3.0)
        checkEq(values, downsample(values, 10), "3 точки при лимите 10 -- как есть")
        checkEq(values, downsample(values, 3), "ровно по лимиту -- как есть")
        checkEq(emptyList<Double>(), downsample(values, 0), "лимит 0 -- пусто")
        checkEq(emptyList<Double>(), downsample(emptyList(), 10), "пусто на входе -- пусто")
    }

    out += DevTest("sparkline/normalize-scale", cat, "Нормализация -- шкала 0..1 по пику") {
        val normalized = normalize(listOf(0.0, 50.0, 100.0))
        checkEq(listOf(0.0, 0.5, 1.0), normalized, "пик в 1.0")
        checkEq(emptyList<Double>(), normalize(listOf(0.0, 0.0)), "все нули -- пусто, не линия на дне")
        checkEq(emptyList<Double>(), normalize(emptyList()), "пусто -- пусто")
        // Отрицательное и nan не должны отравить координату.
        val clamped = normalize(listOf(-5.0, 10.0, Double.NaN))
        checkTrue(clamped.all { it in 0.0..1.0 }, "все значения в границах: $clamped")
    }

    out += DevTest("sparkline/points-bounds", cat, "Точки в границах холста") {
        val history = (1..60).map { it.toDouble() * 1000 }
        val points = sparklinePoints(history, 12, 100f, 28f)
        checkTrue(points.size <= 12, "не больше лимита точек: ${points.size}")
        checkTrue(points.all { it.x in 0f..100f }, "x в границах")
        checkTrue(points.all { it.y in 0f..28f }, "y в границах")
        checkEq(100f, points.last().x, "последняя точка -- правый край")
        checkEq(28f, points.last().y, "последняя -- пик -- верх")
    }

    out += DevTest("sparkline/single-point", cat, "Один отсчёт -- точка по центру") {
        val points = sparklinePoints(listOf(500.0), 12, 100f, 28f)
        checkEq(1, points.size, "одна точка")
        checkEq(50f, points.first().x, "по центру холста")
        checkEq(28f, points.first().y, "единственная точка -- пик шкалы")
    }

    out += DevTest("sparkline/degenerate-size", cat, "Нулевые размеры -- пусто") {
        checkEq(emptyList<xyz.azraellab.zapp.core.traffic.SparkPoint>(), sparklinePoints(listOf(1.0, 2.0), 12, 0f, 28f), "нулевая ширина")
        checkEq(emptyList<xyz.azraellab.zapp.core.traffic.SparkPoint>(), sparklinePoints(listOf(1.0, 2.0), 12, 100f, -1f), "отрицательная высота")
    }

    out += DevTest("sparkline/pair-shared-peak", cat, "Пара линий делит один пик") {
        val rx = listOf(0.0, 100.0, 200.0)
        val tx = listOf(0.0, 50.0, 100.0)
        val (rxPoints, txPoints) = sparklinePair(rx, tx, 12, 100f, 28f)
        checkEq(28f, rxPoints.last().y, "rx -- общий пик -- верх")
        // tx -- половина пика, значит и точка -- половина высоты.
        checkEq(14f, txPoints.last().y, "tx -- половина пика -- половина высоты")
    }

    out += DevTest("sparkline/pair-idle", cat, "Пара в простое -- пусто") {
        val (rxPoints, txPoints) = sparklinePair(
            listOf(0.0, 0.0), listOf(0.0, 0.0), 12, 100f, 28f
        )
        checkTrue(rxPoints.isEmpty() && txPoints.isEmpty(), "ноль трафика -- пустой график")
    }
}
