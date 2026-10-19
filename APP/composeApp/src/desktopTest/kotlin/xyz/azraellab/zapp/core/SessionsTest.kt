package xyz.azraellab.zapp.core

import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.job
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Поколения сессий и рестарт-барьер против гонок при быстрых вкл/выкл.
 *
 * Ставится там, где асинхронный хвост прошлого старта мог бы дописать
 * уже в новый (GPS-геолокация, fd-отправитель туннеля) и там, где два
 * демона сидели бы на одном ресурсе (два box на одном tun, двойные
 * NFQUEUE-цепочки).
 */
class SessionsTest {

    // --- Поколение ---

    @Test
    fun `живо пока поколение не сдвинули`() {
        val gen = SessionGeneration()
        val token = gen.bump()
        assertTrue(gen.isCurrent(token))
        gen.bump()
        assertFalse(gen.isCurrent(token))
    }

    @Test
    fun `снимок текущего совпадает с последним bump`() {
        val gen = SessionGeneration()
        gen.bump()
        val first = gen.current()
        gen.bump()
        val second = gen.current()
        assertTrue(second > first)
        assertTrue(gen.isCurrent(second))
        assertFalse(gen.isCurrent(first))
    }

    @Test
    fun `старт и стоп двигают поколение вперёд`() {
        val gen = SessionGeneration()
        val start = gen.bump()   // старт сессии
        gen.bump()               // стоп её обрывает
        assertFalse(gen.isCurrent(start))
    }

    // --- Барьер ---

    @Test
    fun `вторая сессия ждёт конца первой`() = runBlocking {
        val barrier = SessionBarrier()
        val order = mutableListOf<String>()
        val a = launch {
            barrier.enter(coroutineContext.job)
            order += "a-start"
            delay(50)
            order += "a-end"
        }
        val b = launch {
            barrier.enter(coroutineContext.job)
            order += "b-start"
            order += "b-end"
        }
        joinAll(a, b)
        assertEquals(listOf("a-start", "a-end", "b-start", "b-end"), order)
    }

    @Test
    fun `цепочка сессий выстраивается в очередь`() = runBlocking {
        val barrier = SessionBarrier()
        val order = mutableListOf<String>()
        coroutineScope {
            repeat(3) { i ->
                launch {
                    barrier.enter(coroutineContext.job)
                    order += "s$i"
                    delay(10)
                }
            }
        }
        // Порядок входа фиксируется до join, поэтому очередь стабильна.
        assertEquals(listOf("s0", "s1", "s2"), order)
    }

    @Test
    fun `завершённый предшественник не задерживает вход`() {
        runBlocking {
            val barrier = SessionBarrier()
            val job: Job = launch {
                barrier.enter(coroutineContext.job)
            }
            job.join()
            barrier.enter(coroutineContext.job)
            assertNotNull(barrier.current())
        }
    }

    @Test
    fun `до первой сессии барьер пуст`() {
        runBlocking {
            val barrier = SessionBarrier()
            assertNull(barrier.current())
            barrier.enter(coroutineContext.job)
            assertNotNull(barrier.current())
        }
    }
}
