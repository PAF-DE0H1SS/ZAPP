package xyz.azraellab.zapp.core

/**
 * Подбор стратегии обхода.
 *
 * Смысл в том, чтобы пользователю не приходилось перебирать стратегии руками.
 * Наблюдения сводятся к трём вещам, которые реально различают сети:
 *
 * - режет ли DPI TLS ClientHello целиком (тогда не поможет подмена SNI,
 *   нужен desync);
 * - блокируется ли сам TLS 1.3 (тогда нужен fake-patch с обходом ALPN);
 * - есть ли QUIC/UDP-фильтрация (тогда резать TCP бессмысленно, надо резать
 *   сам ClientHello до установки).
 *
 * Каждая проверка возвращает не текст для показа, а [ProbeResult], который
 * можно сложить в решение. Пользователь при этом не видит внутренних
 * названий -- он видит, что выбрана стратегия, и может открыть настройки.
 */
object AutoStrategy {

    /** Что удалось выяснить о сети. */
    data class Probe(
        val tlsBlocked: Boolean = false,
        val tls13Blocked: Boolean = false,
        val quicBlocked: Boolean = false,
        val dpiPresent: Boolean = true,
        /** Есть ли подозрение на «умный» DPI, который режет по SNI. */
        val sniInspection: Boolean = false
    )

    /** Результат проверки одной гипотезы. */
    sealed interface ProbeResult {
        data object Pass : ProbeResult
        data object Blocked : ProbeResult
        data object Unknown : ProbeResult
    }

    /** Решение: какая стратегия и почему. */
    data class Decision(
        val strategy: String,
        val reason: String
    )

    /**
     * Выбирает стратегию Zapret по результатам проверок сети.
     *
     * Порядок веток важен: сначала самые частые случаи, чтобы не тратить
     * проверки на то, что и так не сработает.
     */
    fun chooseZapretStrategy(probe: Probe, config: ZapretConfig): Decision {
        // Полностью выключенная фильтрация DPI: ломать нечего, только мешает.
        if (!probe.dpiPresent) {
            return Decision(ZapretStrategy.DISABLED.code, "dpi_absent")
        }

        // TLS 1.3 режут: подмена SNI не поможет, нужен разрез пакета.
        if (probe.tls13Blocked) {
            return Decision(
                ZapretStrategy.MULTIPLY_ALPN.code,
                "tls13_blocked"
            )
        }

        // DPI смотрит SNI: самое частое, лечится подменой SNI или fake.
        if (probe.sniInspection) {
            return Decision(
                ZapretStrategy.FAKE_SNI_PATCH_TLS_CLIENT_HELLO.code,
                "sni_inspected"
            )
        }

        // DPI режет ClientHello целиком, SNI не помогает: нужен desync по TTL.
        if (probe.tlsBlocked) {
            return Decision(
                ZapretStrategy.DESYNC_FAKE_TTL_TCP_ACK.code,
                "client_hello_dropped"
            )
        }

        // Просто фильтрация TLS-расширений: обходим через multiply.
        if (probe.quicBlocked) {
            return Decision(
                ZapretStrategy.MULTIPLY_ALPN.code,
                "tls_extension_filtered"
            )
        }

        // Ничего не нашли: берём щадящую стратегию из настроек или дефолт.
        val fallback = config.strategy.takeIf { ZapretStrategy.of(it) != ZapretStrategy.DISABLED }
            ?: ZapretStrategy.MULTIPLY_ALPN.code
        return Decision(fallback, "nothing_detected")
    }

    /**
     * Выбирает режим GoodbyeDPI.
     *
     * Здесь набор режимов меньше, и отличается он не от сети, а от того, что
     * именно режет DPI: сам ClientHello или только заголовок.
     */
    fun chooseGoodbyeDpiMode(probe: Probe): Decision {
        if (!probe.dpiPresent) return Decision(GoodbyeDpiMode.DISABLED.code, "dpi_absent")
        if (probe.sniInspection) return Decision(GoodbyeDpiMode.FAKE_SNI.code, "sni_inspected")
        if (probe.tlsBlocked || probe.tls13Blocked) {
            return Decision(GoodbyeDpiMode.SPLIT2.code, "client_hello_dropped")
        }
        return Decision(GoodbyeDpiMode.AUTO.code, "nothing_detected")
    }

    /**
     * Применяет решение к настройкам.
     *
     * Пишется и явная стратегия, и её собственные значения: иначе пользователь
     * увидит «стратегия выбрана», а работать будет старый набор флагов.
     * Значения, которые стратегия не задаёт, остаются нетронутыми -- поэтому
     * `null` в [ZapretDefaults] означает «не менять».
     */
    fun applyZapret(config: ZapretConfig, decision: Decision): ZapretConfig {
        val base = ZapretStrategy.of(decision.strategy)
        val d = base.defaults

        return config.copy(
            strategy = base.code,
            // Ручные ручки, которые противоречат автоподбору, сбрасываем.
            autoStrategy = false,

            newSyntax = d.newSyntax ?: config.newSyntax,
            hostCase = d.hostCase ?: config.hostCase,
            hostSpell = d.hostSpell ?: config.hostSpell,
            hostNoSpace = d.hostNoSpace ?: config.hostNoSpace,
            methodSpace = d.methodSpace ?: config.methodSpace,
            multipath = d.multipath ?: config.multipath,
            ipdiag = d.ipdiag ?: config.ipdiag,

            desyncMethods = if (d.desync.isNotEmpty()) d.desync else config.desyncMethods,
            dpiSplit = d.split ?: config.dpiSplit,
            dpiSplitPos = d.splitPos ?: config.dpiSplitPos,
            dpiSplitSeqovl = d.splitSeqovl ?: config.dpiSplitSeqovl,

            multiply = d.multiply ?: config.multiply,
            ttl = d.ttl ?: config.ttl,
            wsize = d.wsize ?: config.wsize,
            mss = d.mss ?: config.mss,

            fakeTlsMode = d.fakeTlsMode ?: config.fakeTlsMode,
            fakePacket = d.fakePacket ?: config.fakePacket,
            fakeTcp = d.fakeTcp ?: config.fakeTcp,
            fakeCutTls = d.fakeCutTls ?: config.fakeCutTls,
            fakeSeq = d.fakeSeq ?: config.fakeSeq,
            fakeCsum = d.fakeCsum ?: config.fakeCsum,
            fakeRepeats = d.fakeRepeats ?: config.fakeRepeats,
            foolingTs = d.foolingTs ?: config.foolingTs
        )
    }

    /**
     * Строит описание проверок для UI.
     *
     * Список проверок показывается до запуска и после, чтобы пользователь видел
     * не только «найдено X», но и что именно проверялось. Формулировки
     * короткие, потому что это технические термины, а не объяснения.
     */
    fun stepsForZapret(): List<ZapretProbeStep> = listOf(
        ZapretProbeStep(1, "tls_client_hello", "TLS ClientHello"),
        ZapretProbeStep(2, "tls13", "TLS 1.3"),
        ZapretProbeStep(3, "sni", "SNI"),
        ZapretProbeStep(4, "quic", "QUIC/UDP"),
        ZapretProbeStep(5, "http2_alpn", "ALPN/HTTP2")
    )

    fun stepsForGoodbyeDpi(): List<ZapretProbeStep> = listOf(
        ZapretProbeStep(1, "tls_client_hello", "TLS ClientHello"),
        ZapretProbeStep(2, "sni", "SNI"),
        ZapretProbeStep(3, "tls13", "TLS 1.3")
    )
}

/** Один шаг проверки для отображения. */
data class ZapretProbeStep(
    val index: Int,
    val id: String,
    val title: String
)
