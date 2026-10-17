package xyz.azraellab.zapp.core.connections

import java.io.IOException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class FallbackConnectionsSourceTest {

    private class Stub(private val result: () -> ConnectionsSample) : ConnectionsSource {
        var calls = 0

        override fun read(): ConnectionsSample {
            calls++
            return result()
        }
    }

    private val row = ConnRow("tcp", 443, "1.2.3.4:9", "app")

    @Test
    fun fallbackStepsInWhenPrimaryFails() {
        val primary = Stub { throw IOException("zappmon: not running") }
        val fallback = Stub { ConnectionsSample(listOf(row)) }
        val source = FallbackConnectionsSource(primary = primary, fallback = fallback)
        val sample = source.read()
        assertEquals(listOf(row), sample.rows, "таблица живая даже без демона")
        assertEquals(1, fallback.calls)
    }

    @Test
    fun healthyPrimaryShadowsFallbackCompletely() {
        val primary = Stub { ConnectionsSample.EMPTY }
        val fallback = Stub { error("опрос не должен трогаться, пока монитор жив") }
        val source = FallbackConnectionsSource(primary = primary, fallback = fallback)
        assertEquals(ConnectionsSample.EMPTY, source.read())
        assertEquals(1, primary.calls)
        assertEquals(0, fallback.calls)
    }

    @Test
    fun emptyPrimarySampleIsNotAFailure() {
        // Пустая таблица -- валидный ответ живого демона (соединений нет),
        // а не повод откатываться на опрос.
        val primary = Stub { ConnectionsSample.EMPTY }
        val fallback = Stub { ConnectionsSample(listOf(row)) }
        val source = FallbackConnectionsSource(primary = primary, fallback = fallback)
        assertEquals(ConnectionsSample.EMPTY, source.read())
        assertEquals(0, fallback.calls)
    }

    @Test
    fun recoverySwitchesBackToPrimary() {
        var broken = true
        val primary = Stub {
            if (broken) throw IOException("zappmon: timeout") else ConnectionsSample.EMPTY
        }
        val fallback = Stub { ConnectionsSample.EMPTY }
        val source = FallbackConnectionsSource(primary = primary, fallback = fallback)
        source.read()
        assertEquals(1, fallback.calls, "сломан -- откат")
        broken = false
        source.read()
        assertEquals(1, fallback.calls, "после восстановления опрос не нужен")
        assertEquals(2, primary.calls)
    }
}
