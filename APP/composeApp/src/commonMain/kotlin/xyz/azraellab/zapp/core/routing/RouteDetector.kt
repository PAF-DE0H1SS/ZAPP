package xyz.azraellab.zapp.core.routing

import xyz.azraellab.zapp.core.VpnConfig
import xyz.azraellab.zapp.core.daemon.DpiOverlay

/** Действие, которое выбрал трассировщик. */
enum class TraceAction { TUNNEL, DIRECT, BLOCK }

/**
 * Шаг трассировки: одно правило и его вердикт по проверяемому адресу.
 *
 * Правило без совпадения -- это тоже результат («почему не сработало»),
 * а [matched] -- единственный признак того, что трафик пошёл по нему.
 */
data class RouteTraceStep(
    /** Идентификатор правила: константа [RouteDetector] или метка секции. */
    val rule: String,
    /** Куда повёл бы трафик. */
    val action: TraceAction,
    /** Чем совпало (поля sing-box) или почему не совпало. */
    val detail: String,
    /** Правило сработало. */
    val matched: Boolean,
    /** false: вердикт неоднозначен -- не хватает данных (порт/сеть/резолв). */
    val verified: Boolean = true
)

/** Итог прогона адреса через правила маршрутизации. */
data class RouteTrace(
    val action: TraceAction,
    /** Кто решил: метка секции, идентификатор правила или «по умолчанию». */
    val by: String,
    /** Чем совпало; пусто -- сработало правило по умолчанию. */
    val reason: String,
    /** Правила в порядке применения: первое совпавшее и есть ответ. */
    val steps: List<RouteTraceStep>
)

/**
 * Детектор правила маршрутизации: прогоняет адрес через те же правила,
 * в том же порядке, что и сборщик конфига (базовые исключения ->
 * секции по порядку -> QUIC-обход -> туннель по умолчанию), и
 * показывает, какое правило сработает и почему.
 *
 * Границы v1: домен против ip_cidr не резолвится (такие условия
 * помечаются пояснением вместо угадывания), импортированные clash-правила
 * и релей TCP-портов не проверяются -- детектор отвечает за секции
 * и базовые исключения, то есть за то, что пользователь пишет сам.
 */
object RouteDetector {

    /** Идентификаторы встроенных правил -- для перевода в UI. */
    const val RULE_PRIVATE = "private"
    const val RULE_MULTICAST = "multicast"
    const val RULE_V6 = "ipv6"
    const val RULE_QUIC = "quic"
    const val RULE_DEFAULT = "default"

    /** Базовые подсети -- ровно те, что пишет сборщик конфига. */
    private val PRIVATE = listOf(
        "127.0.0.0/8", "10.0.0.0/8", "172.16.0.0/12",
        "192.168.0.0/16", "169.254.0.0/16", "100.64.0.0/10"
    )
    private val MULTICAST = listOf("224.0.0.0/4", "255.255.255.255/32")

    /**
     * Прогон [query] (домен или IP) через правила [vpn].
     *
     * [port] и [network] (`tcp`/`udp`/`any`) -- всё, что знает о
     * соединении: без них шаг с портовым или сетевым условием
     * помечается «данных не хватает», но сам трафик от этого не меняется.
     * [quicBlock] -- текущий флаг обхода QUIC (по умолчанию -- из
     * глобального состояния, в тестах подставляется явно).
     */
    fun trace(
        vpn: VpnConfig,
        query: String,
        port: Int? = null,
        network: String = "any",
        quicBlock: Boolean = DpiOverlay.quicBlock
    ): RouteTrace {
        val address = query.trim().lowercase().trimEnd('.')
        val isIp = isIpLiteral(address)
        val net = network.trim().lowercase().ifBlank { "any" }
        val steps = mutableListOf<RouteTraceStep>()

        // Правила в порядке сборки конфига.
        if (vpn.routing.excludePrivateRanges) {
            val hit = if (isIp) PRIVATE.firstOrNull { cidrContains(it, address) } else null
            steps += RouteTraceStep(
                rule = RULE_PRIVATE,
                action = TraceAction.DIRECT,
                detail = if (!isIp) "ip_cidr: resolve pending" else "ip_cidr: ${hit ?: "miss"}",
                matched = hit != null
            )
        }
        if (vpn.routing.excludeMulticast) {
            val hit = if (isIp) MULTICAST.firstOrNull { cidrContains(it, address) } else null
            steps += RouteTraceStep(
                rule = RULE_MULTICAST,
                action = TraceAction.DIRECT,
                detail = if (!isIp) "ip_cidr: resolve pending" else "ip_cidr: ${hit ?: "miss"}",
                matched = hit != null
            )
        }
        if (!vpn.ipv6) {
            val v6 = isIp && parseIpv6(address) != null
            steps += RouteTraceStep(
                rule = RULE_V6,
                action = TraceAction.BLOCK,
                detail = if (v6) "ip_cidr: ::/0" else "ipv6-only",
                matched = v6
            )
        }

        // Секции пользователя -- в порядке списка, DNS-секции мимо:
        // они задают серверы, а не маршруты.
        vpn.routing.sections
            .filter { it.active() && it.action != SectionAction.DNS }
            .forEach { section ->
                steps += evaluate(section, isIp, address, port, net)
            }

        // QUIC-обход: после секций, как в конфиге.
        if (quicBlock) {
            steps += RouteTraceStep(
                rule = RULE_QUIC,
                action = TraceAction.BLOCK,
                detail = if (net == "any") "protocol: udp?" else "protocol: quic",
                matched = net == "udp",
                verified = net != "any"
            )
        }

        // Первое совпавшее правило -- вердикт, иначе туннель по умолчанию.
        val winner = steps.firstOrNull { it.matched }
        val maybes = steps.filter { !it.matched && !it.verified }
        return if (winner != null) {
            RouteTrace(
                action = winner.action,
                by = winner.rule,
                reason = winner.detail,
                steps = steps
            )
        } else {
            RouteTrace(
                action = TraceAction.TUNNEL,
                by = RULE_DEFAULT,
                reason = maybes.joinToString("; ") { it.rule },
                steps = steps
            )
        }
    }

    /**
     * Одна секция: все условия должны совпасть (AND внутри правила,
     * OR внутри списка значений) -- как sing-box применяет rule-set.
     */
    private fun evaluate(
        section: RouteSection,
        isIp: Boolean,
        address: String,
        port: Int?,
        network: String
    ): RouteTraceStep {
        val domains = parseSectionDomains(section.domains)
        val ips = parseWords(section.ipCidrs)
        val ports = parseSectionPorts(section.ports)
        val wanted = section.network.trim().lowercase().ifBlank { "any" }

        val details = mutableListOf<String>()
        var failed = false
        var incomplete = false

        if (domains.isNotEmpty() && !failed) {
            if (isIp) {
                details += "domain: ip literal"
                failed = true
            } else {
                val hit = matchDomain(domains, address)
                if (hit == null) {
                    details += "domain: miss"
                    failed = true
                } else {
                    details += hit
                }
            }
        }
        if (ips.isNotEmpty() && !failed) {
            when {
                !isIp && section.resolveRealIp -> {
                    details += "ip_cidr: resolve pending"
                    incomplete = true
                }
                !isIp -> {
                    details += "ip_cidr: domain"
                    failed = true
                }
                else -> {
                    val hit = ips.firstOrNull { cidrContains(it, address) }
                    if (hit == null) {
                        details += "ip_cidr: miss"
                        failed = true
                    } else {
                        details += "ip_cidr: $hit"
                    }
                }
            }
        }
        if (!ports.isEmpty() && !failed) {
            when (port) {
                null -> {
                    details += "port: ?"
                    incomplete = true
                }
                else -> if (portIn(ports, port)) {
                    details += "port: $port"
                } else {
                    details += "port: miss"
                    failed = true
                }
            }
        }
        if (wanted != "any" && !failed) {
            when (network) {
                "any" -> {
                    details += "network: ?"
                    incomplete = true
                }
                else -> if (network == wanted) {
                    details += "network: $wanted"
                } else {
                    details += "network: miss"
                    failed = true
                }
            }
        }

        return RouteTraceStep(
            rule = section.label,
            action = when (section.action) {
                SectionAction.DIRECT -> TraceAction.DIRECT
                SectionAction.BLOCK -> TraceAction.BLOCK
                else -> TraceAction.TUNNEL
            },
            detail = details.joinToString(", "),
            matched = !failed && !incomplete,
            verified = !incomplete
        )
    }

    /** Какое доменное условие совпало: точное имя, суффикс, подстрока, шаблон. */
    private fun matchDomain(domains: ParsedDomains, domain: String): String? {
        if (domain in domains.exact) return "domain: $domain"
        domains.suffix.firstOrNull { domain == it || domain.endsWith(".$it") }
            ?.let { return "domain_suffix: $it" }
        domains.keyword.firstOrNull { domain.contains(it) }
            ?.let { return "domain_keyword: $it" }
        domains.regex.firstOrNull { runCatching { Regex(it).containsMatchIn(domain) }.getOrNull() == true }
            ?.let { return "domain_regex: $it" }
        return null
    }

    /** Порт в списке или в диапазоне sing-box `start:end`. */
    private fun portIn(ports: ParsedPorts, port: Int): Boolean =
        port in ports.ports || ports.ranges.any { range ->
            val (start, end) = range.split(':')
            port >= (start.toIntOrNull() ?: return@any false) &&
                port <= (end.toIntOrNull() ?: return@any false)
        }
}
