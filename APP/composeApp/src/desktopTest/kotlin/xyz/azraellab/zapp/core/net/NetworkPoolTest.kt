package xyz.azraellab.zapp.core.net

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Пул сетей: монотонное поколение и выбор следующего аплинка.
 *
 * Смена Wi-Fi -> мобайл не должна ждать ручного рестарта: колбэк двигает
 * поколение, всё устаревшее узнаёт об этом по счётчику, а при потере
 * текущей сети пул сразу отдаёт кандидата вместо ожидания системы.
 */
class NetworkPoolTest {

    @Test
    fun `новая сеть двигает поколение`() {
        val pool = NetworkPool()
        val start = pool.generation
        pool.onAvailable("wifi")
        assertTrue(pool.generation > start)
        val afterWifi = pool.generation
        pool.onAvailable("wifi")   // повтор -- без изменений
        assertEquals(afterWifi, pool.generation)
    }

    @Test
    fun `подложка становится текущей и растит поколение`() {
        val pool = NetworkPool()
        val gen = pool.generation
        assertEquals("wifi", pool.onCurrent("wifi"))
        assertEquals("wifi", pool.current)
        assertTrue(pool.generation > gen)
    }

    @Test
    fun `потеря текущей выбирает следующего из пула`() {
        val pool = NetworkPool()
        pool.onCurrent("wifi")
        pool.onAvailable("cell")
        val gen = pool.generation
        val next = pool.onLost("wifi")
        assertEquals("cell", next)
        assertEquals("cell", pool.current)
        assertTrue(pool.generation > gen)
    }

    @Test
    fun `потеря не-текущей сети пул не перекраивает`() {
        val pool = NetworkPool()
        pool.onCurrent("wifi")
        pool.onAvailable("cell")
        val gen = pool.generation
        val stillWifi = pool.onLost("cell")
        assertEquals("wifi", stillWifi)
        assertEquals("wifi", pool.current)
        assertTrue(pool.generation > gen)   // кандидат выпал -- поколение растёт
    }

    @Test
    fun `потеря всех отдаёт null`() {
        val pool = NetworkPool()
        pool.onCurrent("wifi")
        assertNull(pool.onLost("wifi"))
        assertNull(pool.current)
    }

    @Test
    fun `поколение монотонно на любых операциях`() {
        val pool = NetworkPool()
        var previous = pool.generation
        pool.onAvailable("a")
        pool.onCurrent("a")
        pool.onAvailable("b")
        pool.onCurrent("b")
        pool.onLost("a")
        pool.onLost("b")
        val sequence = pool.generation
        assertTrue(sequence > previous, "поколение должно расти, а не прыгать назад")
        previous = sequence
        pool.onAvailable("c")
        assertTrue(pool.generation > previous)
    }

    @Test
    fun `повторное объявление текущей -- без лишних поколений`() {
        val pool = NetworkPool()
        pool.onCurrent("wifi")
        val gen = pool.generation
        pool.onCurrent("wifi")
        assertEquals(gen, pool.generation)
    }
}
