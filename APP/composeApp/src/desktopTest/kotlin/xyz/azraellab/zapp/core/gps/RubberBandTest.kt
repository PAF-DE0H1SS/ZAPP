package xyz.azraellab.zapp.core.gps

import kotlin.math.abs
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Анти rubber-banding: джиттер скорости, профиль разгона/торможения, дрейф.
 *
 * Вся математика чистая, поэтому здесь же и живут тесты: то, что на
 * телефоне крутится в фоне раз в секунду, проверяется здесь покадрово.
 */
class RubberBandTest {

    @Test
    fun `джиттер держит заявленные границы`() {
        val random = Random(42)
        repeat(200) {
            val value = RubberBand.jitterSpeed(10.0, 20, random)
            assertTrue(value in 8.0..12.0, "выпало $value -- за пределами ±20 %")
        }
    }

    @Test
    fun `джиттер с процентом больше сотни ограничен сотней`() {
        val random = Random(1)
        repeat(100) {
            val value = RubberBand.jitterSpeed(1.0, 500, random)
            assertTrue(value >= 0.0, "не уходит в минус: $value")
            assertTrue(value <= 2.0, "процент ограничен сотней: $value")
        }
    }

    @Test
    fun `нулевой разброс и нулевая скорость -- точные значения`() {
        val random = Random(0)
        assertEquals(7.5, RubberBand.jitterSpeed(7.5, 0, random), 1e-9)
        assertEquals(7.5, RubberBand.jitterSpeed(7.5, -3, random), 1e-9)
        assertEquals(0.0, RubberBand.jitterSpeed(0.0, 90, random), 1e-9)
    }

    @Test
    fun `профиль монотонен -- разгон растёт, торможение падает`() {
        var previous = RubberBand.speedProfile(0.0, 200.0, 50.0, 50.0)
        for (passed in 1..50) {
            val current = RubberBand.speedProfile(passed.toDouble(), 200.0, 50.0, 50.0)
            assertTrue(current >= previous, "разгон: $passed м -- не монотонно ($current < $previous)")
            // До 5 м рост сжирает пол: после -- строгое увеличение.
            if (passed > 5) assertTrue(current > previous, "разгон: $passed м -- не строго растёт")
            previous = current
        }
        assertEquals(1.0, previous, 1e-9, "разгон дошёл до полной скорости")
        previous = 1.0
        for (passed in 151..200) {
            val current = RubberBand.speedProfile(passed.toDouble(), 200.0, 50.0, 50.0)
            assertTrue(current <= previous, "торможение: $passed м -- не монотонно")
            // Последние 5 м упираются в пол: до этого -- строгое убывание.
            if (passed < 195) assertTrue(current < previous, "торможение: $passed м -- не строго падает")
            previous = current
        }
        assertTrue(previous >= RubberBand.MIN_FACTOR, "прибытие не обнуляется")
    }

    @Test
    fun `профиль на кольце по модулю длины`() {
        // RouteProgress в кольце передаёт счёт по модулю: профиль должен
        // отрабатывать и «второй круг», начиная разгон заново.
        val lap = RubberBand.speedProfile(250.0, 250.0, 25.0, 25.0)
        assertEquals(RubberBand.MIN_FACTOR, lap, 1e-9, "смыкание кольца тормозит")
        val second = RubberBand.speedProfile(125.0, 250.0, 25.0, 25.0)
        assertEquals(1.0, second, 1e-9, "середина второго круга -- полная")
    }

    @Test
    fun `дрейф копится и сбрасывается`() {
        val drift = DriftAccumulator()
        assertEquals(0.0, drift.meters, 1e-9)
        repeat(3) { drift.tick(0.3, 1.0) }
        assertEquals(0.9, drift.meters, 1e-6, "три такта по 0.3 м/с")
        drift.tick(10.0, -1.0) // отрицательный шаг -- игнор
        assertEquals(0.9, drift.meters, 1e-6)
        drift.reset()
        assertEquals(0.0, drift.meters, 1e-9)
    }

    @Test
    fun `профиль маршрута не меняет старое поведение без параметров`() {
        // accel=decel=0 -- профиль отключён: RouteProgress со старыми
        // аргументами обязан ехать ровно, как раньше.
        val points = listOf(
            xyz.azraellab.zapp.core.RoutePoint(0.0, 0.0),
            xyz.azraellab.zapp.core.RoutePoint(0.001, 0.0)
        )
        val progress = RouteProgress(points, loop = false, routeDwellSeconds = 0)
        val first = requireNotNull(progress.step(1.0, 10.0)) { "шаг" }
        // 10 м из ~111 м: полный шаг без профиля.
        assertTrue(abs(first.latitude - 10.0 / 111194.9) < 1e-6, "полный шаг: ${first.latitude}")
    }
}
