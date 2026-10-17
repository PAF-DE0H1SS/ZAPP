package xyz.azraellab.zapp.core.netcheck

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import xyz.azraellab.zapp.core.net.LinkProbe
import xyz.azraellab.zapp.core.net.ProbeKind
import xyz.azraellab.zapp.core.net.probeLink

/** Узел для проверки: домен/IP, порт и метка для списка. */
data class NetCheckNode(
    val host: String,
    val port: Int = 443,
    val label: String = host
)

/** Итог пробы одного узла. */
data class NetCheckResult(
    val node: NetCheckNode,
    val alive: Boolean,
    /** Задержка мс; null -- жив, но измерить не удалось (UDP молчит). */
    val latencyMs: Int?,
    val error: String,
    val atMs: Long
)

/**
 * Сканер задержки: параллельные пробы списка узлов и история замеров.
 *
 * Проба инжектируется ([probe]), поэтому оркестровка -- параллелизм, сбор
 * итогов, история и выбор лучшего узла -- тестируется без сети. Реальный
 * замер (TCP/UDP через [probeLink]) подключается фабрикой [NetCheckService.real].
 */
class NetCheckService(
    private val probe: suspend (NetCheckNode) -> LinkProbe
) {

    private val _history = MutableStateFlow<List<NetCheckResult>>(emptyList())

    /** Последние замеры, новые сверху; ограничены [HISTORY]. */
    val history: StateFlow<List<NetCheckResult>> = _history.asStateFlow()

    /** Пробяет все узлы параллельно, кладёт итоги в историю, возвращает их. */
    suspend fun check(nodes: List<NetCheckNode>): List<NetCheckResult> = coroutineScope {
        val startedAt = System.currentTimeMillis()
        val results = nodes
            .filter { it.host.isNotBlank() }
            .map { node ->
                async(Dispatchers.Default) {
                    val p = runCatching { probe(node) }
                        .getOrDefault(LinkProbe(alive = false, error = "probe"))
                    NetCheckResult(
                        node = node,
                        alive = p.alive,
                        latencyMs = p.latencyMs.takeIf { it > 0 },
                        error = p.error,
                        atMs = startedAt
                    )
                }
            }
            .awaitAll()
        _history.value = (results + _history.value).take(HISTORY)
        results
    }

    /** Лучший живой узел по задержке; null -- живых нет. */
    fun best(results: List<NetCheckResult>): NetCheckResult? =
        results.filter { it.alive && it.latencyMs != null }.minByOrNull { it.latencyMs!! }

    companion object {
        private const val HISTORY = 60

        /** Реальная проба TCP-соединением ([probeLink]). */
        fun real(): NetCheckService = NetCheckService { node ->
            probeLink(node.host, node.port, ProbeKind.TCP)
        }

        /** Публичные DNS-резолверы как узлы по умолчанию. */
        fun defaultNodes(): List<NetCheckNode> = listOf(
            NetCheckNode("1.1.1.1", 443, "Cloudflare"),
            NetCheckNode("8.8.8.8", 443, "Google"),
            NetCheckNode("9.9.9.9", 443, "Quad9")
        )
    }
}
