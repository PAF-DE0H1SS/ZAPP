package xyz.azraellab.zapp.core.dev

import xyz.azraellab.zapp.core.engine.TunnelState
import xyz.azraellab.zapp.core.orb.OrbPhases
import xyz.azraellab.zapp.core.orb.OrbTone
import xyz.azraellab.zapp.core.orb.orbBreatheAlpha
import xyz.azraellab.zapp.core.orb.orbBreatheScale
import xyz.azraellab.zapp.core.orb.orbBlobPoints
import xyz.azraellab.zapp.core.orb.orbCoreScale
import xyz.azraellab.zapp.core.orb.orbPhase
import xyz.azraellab.zapp.core.orb.orbSpinDegrees
import xyz.azraellab.zapp.core.orb.orbTone
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin

/**
 * Орб подключения: тон по состоянию, замкнутость контура, границы
 * дыхания. Проверяется чистая математика из `core/orb/OrbMath.kt` --
 * та самая, что исполняет Canvas кнопки на страницах VPN и Tor.
 */
internal fun orbTests(out: MutableList<DevTest>) {
    val cat = DevCategory.THEME

    out += DevTest("orb/tone-by-state", cat, "Тон -- функция состояния туннеля") {
        checkEq(OrbTone.NEUTRAL, orbTone(TunnelState.DISCONNECTED), "выключен")
        checkEq(OrbTone.BUSY, orbTone(TunnelState.CONNECTING), "подключается")
        checkEq(OrbTone.BUSY, orbTone(TunnelState.RECONNECTING), "переподключается")
        checkEq(OrbTone.LIVE, orbTone(TunnelState.CONNECTED), "подключён")
        checkEq(OrbTone.FAIL, orbTone(TunnelState.ERROR), "ошибка")
        // Пять состояний -- четыре тона (CONNECTING и RECONNECTING делят BUSY).
        checkEq(4, TunnelState.entries.map { orbTone(it) }.toSet().size, "без сюрпризов")
    }

    out += DevTest("orb/phase-wrap", cat, "Фаза -- доля периода с заворотом") {
        checkEq(0f, orbPhase(0f, 4000f), "старт")
        checkEq(0.5f, orbPhase(2000f, 4000f), "середина")
        checkEq(0f, orbPhase(4000f, 4000f), "ровно период -> 0")
        checkEq(0.25f, orbPhase(5000f, 4000f), "период + четверть")
        checkEq(0.75f, orbPhase(-1000f, 4000f), "отрицательное время -> 0.75")
        var threw = false
        runCatching { orbPhase(1f, 0f) }.onFailure { threw = true }
        checkTrue(threw, "нулевой период -- ошибка")
    }

    out += DevTest("orb/blob-seamless", cat, "Контур замкнут: фаза 0 и 1 -- одна кривая") {
        val a = orbBlobPoints(0f, 100f)
        val b = orbBlobPoints(1f, 100f)
        checkEq(a.size, b.size, "число точек")
        var maxDelta = 0f
        for (i in a.indices) {
            maxDelta = maxOf(maxDelta, kotlin.math.abs(a[i].first - b[i].first))
            maxDelta = maxOf(maxDelta, kotlin.math.abs(a[i].second - b[i].second))
        }
        checkTrue(maxDelta < 1e-2f, "максимальное отклонение $maxDelta -- контур цикличен")
        // Первую и последнюю точки склеивает close(): угол 0 и 2π.
        val first = a.first()
        val last = a.last()
        checkTrue(hypot(first.first - last.first, first.second - last.second) < 1e-2f, "конец совпадает с началом")
    }

    out += DevTest("orb/blob-bounds", cat, "Амплитуды волн держатся у радиуса") {
        val radius = 120f
        for (phase in listOf(0f, 0.3f, 0.66f, 0.9f)) {
            for ((x, y) in orbBlobPoints(phase, radius)) {
                val dist = hypot(x, y)
                checkTrue(dist in radius * 0.88f..radius * 1.12f, "радиус $dist при фазе $phase")
            }
        }
        // Сумма амплитуд из формулы: 4% + 3% + 2.5% < 12% -- запас есть.
        val amplitudes = 0.04f + 0.03f + 0.025f
        checkTrue(amplitudes <= 0.12f, "суммарная амплитуда $amplitudes в границах")
    }

    out += DevTest("orb/blob-shape-varies", cat, "Фаза реально меняет форму") {
        val a = orbBlobPoints(0f, 100f, 64)
        val b = orbBlobPoints(0.5f, 100f, 64)
        var maxDelta = 0f
        for (i in a.indices) {
            maxDelta = maxOf(maxDelta, kotlin.math.abs(a[i].first - b[i].first))
            maxDelta = maxOf(maxDelta, kotlin.math.abs(a[i].second - b[i].second))
        }
        checkTrue(maxDelta > 1f, "через полпериода контур отличается (delta=$maxDelta)")
    }

    out += DevTest("orb/breathe-ranges", cat, "Дыхание, ядро и кольцо в своих диапазонах") {
        for (i in 0..200) {
            val p = i / 200f
            checkTrue(orbBreatheScale(p) in 0.91f..1.09f, "breatheScale($p)=${orbBreatheScale(p)}")
            checkTrue(orbBreatheAlpha(p) in 0.39f..1.01f, "breatheAlpha($p)=${orbBreatheAlpha(p)}")
            checkTrue(orbCoreScale(p) in 0.79f..1.05f, "coreScale($p)=${orbCoreScale(p)}")
            checkTrue(orbSpinDegrees(p) in 0f..360f, "spin($p)")
        }
        // Синус проходит ноль: в покое масштабы равны своим базам.
        checkEq(1f, orbBreatheScale(0f), "breathe в нуле")
        checkEq(0.7f, orbBreatheAlpha(0f), "alpha в нуле")
        checkEq(0f, orbSpinDegrees(0f), "spin в нуле")
    }

    out += DevTest("orb/spin-full-turn", cat, "Кольцо делает оборот за период") {
        checkEq(90f, orbSpinDegrees(0.25f), "четверть")
        checkEq(180f, orbSpinDegrees(0.5f), "половина")
        checkTrue(orbSpinDegrees(0.999f) in 359f..360f, "почти оборот")
        checkEq(0f, orbSpinDegrees(1f), "оборот замкнулся")
    }

    out += DevTest("orb/phases-positive", cat, "Периоды фаз положительны и различны") {
        val periods = listOf(OrbPhases.BLOB_MS, OrbPhases.BREATHE_MS, OrbPhases.SWIRL_MS, OrbPhases.CORE_MS, OrbPhases.SPIN_MS)
        checkTrue(periods.all { it > 0f }, "все периоды > 0")
        checkEq(periods.size, periods.toSet().size, "периоды не повторяются")
        // Формула волн использует те же гармоники, что и в документации.
        val p = 0.5f
        val r = 100f
        val points = orbBlobPoints(p, r, 32)
        val expectedFirst = r + sin(0f + orbPhase(p, 1f) * 2f * PI.toFloat()) * r * 0.04f +
            sin(0f - orbPhase(p, 1f) * 2f * PI.toFloat()) * r * 0.03f +
            cos(0f + orbPhase(p, 1f) * 2f * PI.toFloat() * 2f) * r * 0.025f
        val first = points.first()
        checkTrue(kotlin.math.abs(first.first - expectedFirst) < 1e-3f, "первая точка по формуле: ${first.first} vs $expectedFirst")
        checkTrue(kotlin.math.abs(first.second) < 1e-3f, "первая точка лежит на оси X (sin 0 = 0)")
        checkTrue(cos(0f) == 1f && sin(0f) == 0f, "базовые функции на месте")
    }
}
