package xyz.azraellab.zapp.core.netcheck

import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import xyz.azraellab.zapp.core.net.LinkProbe
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** NetCheck: оркестровка параллельных проб, выбор лучшего, история. */
class NetCheckTest {

    @Test
    fun checksAllNodesInParallel() = runBlocking {
        val service = NetCheckService { node ->
            delay(50)
            LinkProbe(alive = true, latencyMs = node.host.length)
        }
        val nodes = listOf(
            NetCheckNode("aa.example", 443, "A"),
            NetCheckNode("bbb.example", 443, "B"),
            NetCheckNode("cccc.example", 443, "C")
        )
        val started = System.currentTimeMillis()
        val results = service.check(nodes)
        val elapsed = System.currentTimeMillis() - started
        assertEquals(3, results.size)
        // параллельно: три пробы по 50 мс укладываются в один интервал
        assertTrue(elapsed < 150, "пробы должны идти параллельно, заняло $elapsed мс")
    }

    @Test
    fun bestPicksLowestLatencyAlive() {
        val service = NetCheckService { LinkProbe(alive = true, latencyMs = 10) }
        val results = listOf(
            mk("a", alive = true, ms = 40),
            mk("b", alive = true, ms = 12),
            mk("c", alive = false, ms = -1)
        )
        assertEquals("b", service.best(results)?.node?.host)
    }

    @Test
    fun bestIgnoresAliveWithoutLatency() {
        // жив, но UDP молчит (latencyMs = -1 -> null): не может быть «лучшим»
        val service = NetCheckService { LinkProbe(alive = true, latencyMs = -1) }
        val results = listOf(mk("a", alive = true, ms = -1))
        assertNull(service.best(results))
    }

    @Test
    fun bestNullWhenNothingAlive() {
        val service = NetCheckService { LinkProbe(alive = false, error = "timeout") }
        val results = listOf(mk("a", alive = false, ms = -1), mk("b", alive = false, ms = -1))
        assertNull(service.best(results))
    }

    @Test
    fun blankHostsAreSkipped() = runBlocking {
        val service = NetCheckService { LinkProbe(alive = true, latencyMs = 5) }
        val results = service.check(listOf(NetCheckNode(""), NetCheckNode("ok.example")))
        assertEquals(1, results.size)
        assertEquals("ok.example", results[0].node.host)
    }

    @Test
    fun probeFailureBecomesDeadResult() = runBlocking {
        val service = NetCheckService { error("boom") }
        val results = service.check(listOf(NetCheckNode("x.example")))
        assertEquals(1, results.size)
        assertTrue(!results[0].alive)
        assertEquals("probe", results[0].error)
    }

    @Test
    fun historyKeepsNewestFirstAndCaps() = runBlocking {
        val service = NetCheckService { LinkProbe(alive = true, latencyMs = 1) }
        repeat(70) { service.check(listOf(NetCheckNode("n$it"))) }
        val history = service.history.value
        assertTrue(history.size <= 60, "история должна ограничиваться, размер ${history.size}")
        assertEquals("n69", history.first().node.host, "сверху -- последний замер")
    }

    private fun mk(host: String, alive: Boolean, ms: Int) = NetCheckResult(
        node = NetCheckNode(host, 443, host),
        alive = alive,
        latencyMs = ms.takeIf { it > 0 },
        error = "",
        atMs = 0L
    )
}
