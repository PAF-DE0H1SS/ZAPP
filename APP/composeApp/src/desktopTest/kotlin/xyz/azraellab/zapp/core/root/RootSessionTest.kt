package xyz.azraellab.zapp.core.root

import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Персистентная root-сессия: рамка команды + сентинел и жизненный цикл канала.
 *
 * Канал фейковый -- без реального `su`: проверяется протокол (команда,
 * сентинел с кодом, граница вывода) и то, что сессия переиспользует канал,
 * а при смерти -- пересоздаёт его.
 */
class RootSessionTest {

    /** Фейковый канал: на команду отвечает настроенным выводом + сентинелом. */
    private class FakeChannel(
        private val code: Int = 0,
        private val output: List<String> = emptyList()
    ) : ShellChannel {
        val closed = AtomicInteger(0)
        private val inbox = LinkedBlockingQueue<String>()

        override fun send(line: String): Boolean {
            val mark = Regex("""ZAPP_R\d+""").find(line)?.value ?: return false
            output.forEach { inbox.offer(it) }
            inbox.offer("$mark $code")
            return true
        }

        override fun readLine(): String? = inbox.poll(200, java.util.concurrent.TimeUnit.MILLISECONDS)

        override fun close() {
            closed.incrementAndGet()
        }
    }

    @Test
    fun capturesOutputAndExitCode() {
        val session = RootSession { FakeChannel(code = 7, output = listOf("alpha", "beta")) }
        val r = session.run("some-cmd")
        assertEquals(7, r.code)
        assertEquals("alpha\nbeta", r.output)
    }

    @Test
    fun successKeepsChannelOpen() {
        val opens = AtomicInteger(0)
        val session = RootSession { opens.incrementAndGet(); FakeChannel(code = 0, output = listOf("ok")) }
        assertEquals(0, session.run("a").code)
        assertEquals(0, session.run("b").code)
        assertEquals(1, opens.get(), "канал должен переиспользоваться, а не открываться заново")
    }

    @Test
    fun nonZeroCodeReturnedAsIs() {
        val session = RootSession { FakeChannel(code = 3, output = listOf("err")) }
        val r = session.run("failing")
        assertEquals(3, r.code)
        assertEquals("err", r.output)
        assertTrue(!r.ok)
    }

    @Test
    fun deadChannelReportsUnavailableAndReopens() {
        val opens = AtomicInteger(0)
        // Первый канал мёртв сразу (send отклоняет) -> UNAVAILABLE; второй жив.
        val session = RootSession {
            opens.incrementAndGet()
            if (opens.get() == 1) object : ShellChannel {
                override fun send(line: String) = false
                override fun readLine(): String? = null
                override fun close() {}
            } else {
                FakeChannel(code = 0, output = listOf("recovered"))
            }
        }
        assertEquals(RootSession.UNAVAILABLE, session.run("a").code)
        val r = session.run("b")
        assertEquals(0, r.code)
        assertEquals("recovered", r.output)
        assertEquals(2, opens.get(), "после сбоя должен открыться новый канал")
    }

    @Test
    fun closeReleasesChannel() {
        val channel = FakeChannel(code = 0)
        val session = RootSession { channel }
        session.run("a")
        session.close()
        assertEquals(1, channel.closed.get())
    }

    @Test
    fun unavailableWhenOpenReturnsNull() {
        val session = RootSession { null }
        assertEquals(RootSession.UNAVAILABLE, session.run("a").code)
    }
}
