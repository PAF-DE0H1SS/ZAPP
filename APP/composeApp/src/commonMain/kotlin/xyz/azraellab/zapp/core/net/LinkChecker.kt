package xyz.azraellab.zapp.core.net

/** Итог одной проверки коннекта. */
data class LinkProbe(
    val alive: Boolean,
    val latencyMs: Int = -1,
    val error: String = ""
)

/** Как проверять конкретный коннект. */
enum class ProbeKind {
    /** TCP: замер времени установления соединения. */
    TCP,

    /** UDP: пакет и ждём ICMP-unreachable; тишина -- жив (серверы молчат). */
    UDP
}

/**
 * Проверка живости одного коннекта.
 *
 * Реальный замер, а не флаг из конфига: TCP даёт задержку до SYN-ACK,
 * UDP честно работает с протоколами без слушателя на TCP и ловит отказ
 * по ICMP. Сетевой вызов блокирующий и идёт на IO-потоке; вызывается
 * только из фоновых корутин.
 */
expect suspend fun probeLink(
    host: String,
    port: Int,
    kind: ProbeKind,
    timeoutMs: Int = 4000
): LinkProbe
