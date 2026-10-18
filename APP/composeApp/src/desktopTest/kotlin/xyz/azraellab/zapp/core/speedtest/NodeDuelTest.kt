package xyz.azraellab.zapp.core.speedtest

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Дуэль узлов: агрегаты проб, вердикт с порогом ничьей, чередование
 * сторон оркестратором. Всё чистое -- бокс и сокеты не нужны.
 */
class NodeDuelTest {

    @Test
    fun sideAggregatesOkProbes() {
        val side = duelSide("A", listOf(100, 80, 90))
        assertEquals(3, side.probes)
        assertEquals(3, side.ok)
        assertEquals(80, side.minMs)
        assertEquals(90, side.avgMs)
        assertEquals(20, side.spreadMs)
        assertTrue(side.alive)
    }

    @Test
    fun sideCountsFailuresAsProbes() {
        // Сбой -- это тоже замер (время ушло на таймаут): счётчик проб
        // честный, а средняя считается только по прошедшим.
        val side = duelSide("A", listOf(100, -1, -1))
        assertEquals(3, side.probes)
        assertEquals(1, side.ok)
        assertEquals(100, side.avgMs)
        assertEquals(0, side.spreadMs)
        assertTrue(side.alive)
    }

    @Test
    fun sideWithoutOkProbesIsDead() {
        val side = duelSide("B", listOf(-1, -1))
        assertFalse(side.alive)
        assertEquals(2, side.probes)
        assertEquals(0, side.ok)
        assertEquals(-1, side.minMs)
        assertEquals(-1, side.avgMs)
        assertEquals(-1, side.spreadMs)
    }

    @Test
    fun winnerNeedsRealMargin() {
        val a = duelSide("A", listOf(100))
        // Разница в пределах порога -- ничья: джиттер сети съедает 19 мс.
        assertNull(duelResult(a, duelSide("B", listOf(119))).winner)
        // Ровно порог -- уже победа: граница включительная.
        assertEquals(DuelWinner.A, duelResult(a, duelSide("B", listOf(120))).winner)
        assertEquals(DuelWinner.B, duelResult(duelSide("A", listOf(100)), duelSide("B", listOf(50))).winner)
    }

    @Test
    fun deadLosesEvenWithoutMargin() {
        // Недоступный узел не может быть «примерно так же хорош».
        val result = duelResult(duelSide("A", listOf(-1)), duelSide("B", listOf(900)))
        assertEquals(DuelWinner.B, result.winner)
    }

    @Test
    fun bothDeadIsTie() {
        val result = duelResult(duelSide("A", listOf(-1)), duelSide("B", listOf(-1, -1)))
        assertNull(result.winner)
        assertFalse(result.a.alive)
        assertFalse(result.b.alive)
    }

    @Test
    fun runnerInterleavesSidesAndReportsProgress() {
        val order = mutableListOf<String>()
        val latencies = mapOf("A" to listOf(50, 60, 55), "B" to listOf(200, 210, 205))
        val used = mutableMapOf<String, Int>()
        val runner = NodeDuelRunner { tag ->
            order += tag
            val index = used.getOrDefault(tag, 0)
            used[tag] = index + 1
            latencies[tag]!![index]
        }
        val progress = mutableListOf<Pair<Int, Int>>()
        val result = kotlinx.coroutines.runBlocking {
            runner.run("A", "B") { done, total -> progress += done to total }
        }
        // Пробы вперемешку: временной сдвиг сети не достаётся одной стороне.
        assertEquals(listOf("A", "B", "A", "B", "A", "B"), order)
        assertEquals((1..6).map { it to 6 }, progress)
        assertEquals(DuelWinner.A, result.winner)
        assertEquals(50, result.a.minMs)
        assertEquals(55, result.a.avgMs)
        assertEquals(205, result.b.avgMs)
    }

    @Test
    fun runnerCountsFailedProbesAsDeadSide() {
        val runner = NodeDuelRunner { tag -> if (tag == "A") 70 else null }
        val result = kotlinx.coroutines.runBlocking { runner.run("A", "B") }
        assertEquals(3, result.a.ok)
        assertEquals(0, result.b.ok)
        assertFalse(result.b.alive)
        assertEquals(DuelWinner.A, result.winner)
    }
}
