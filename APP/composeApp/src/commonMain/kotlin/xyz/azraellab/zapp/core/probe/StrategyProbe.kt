package xyz.azraellab.zapp.core.probe

import kotlin.time.TimeMark
import kotlin.time.TimeSource
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import xyz.azraellab.zapp.core.AppLang
import xyz.azraellab.zapp.core.GoodbyeDpiConfig
import xyz.azraellab.zapp.core.GoodbyeDpiMode
import xyz.azraellab.zapp.core.ZapretConfig
import xyz.azraellab.zapp.core.ZapretStrategy

/**
 * Кандидатный подбор стратегии обхода.
 *
 * Модель перенесена по мотивам zapret-control-center: одна «гипотезная»
 * проверка сети не выбирает стратегию -- она лишь описывает сеть. Чтобы
 * реально подобрать обход, каждый кандидат прогоняется сам: изолированный
 * пробный инстанс обрабатывает трафик приложения, пробы идут к реальным
 * продуктовым хостам, а результат складывается в скоринг. Победитель
 * применяется к конфигу и запускается уже как основной демон.
 *
 * Всё здесь -- общий код: цели, скоринг, список кандидатов и координатор
 * проб не зависят от платформы, изолированные инстансы -- отдельный
 * jvm-слой (NFQUEUE нужен root, релей -- нет).
 */

/** Цель пробы: хост и продукт, который он представляет. */
data class ProbeTarget(val host: String, val product: String)

/**
 * Продуктовые хосты.
 *
 * Список устроен как у эталона: ядро продукта обязательно для успеха
 * (без Discord и YouTube нет смысла выбирать обход), медиа и шлюзы --
 * приоритетные (голос и видео рвутся чаще всего), телеграм -- заодно.
 */
val STRATEGY_TARGETS: List<ProbeTarget> = listOf(
    ProbeTarget("discord.com", "discord-core"),
    ProbeTarget("gateway.discord.gg", "discord-gateway"),
    ProbeTarget("discord.media", "discord-media"),
    ProbeTarget("www.youtube.com", "youtube-core"),
    ProbeTarget("redirector.googlevideo.com", "youtube-media"),
    ProbeTarget("youtubei.googleapis.com", "youtube-api"),
    ProbeTarget("telegram.org", "telegram")
)

/** Продукты без которых кандидат не годится вообще. */
val REQUIRED_PRODUCTS: Set<String> = setOf("discord-core", "youtube-core")

/** Продукты, которые дают преимущество в скоринге. */
val PRIORITY_PRODUCTS: Set<String> = setOf(
    "discord-gateway", "discord-media", "youtube-media"
)

/** Исход пробы одного хоста: latency -- время handshake, null -- не прошло. */
data class TargetOutcome(
    val target: ProbeTarget,
    val latencyMs: Long?
)

/**
 * Скоринг кандидата.
 *
 * Порядок сравнения -- из эталона, он отражает реальные приоритеты
 * пользователя: сначала обязательные продукты, потом приоритетные
 * (голос/видео), потом UDP-медиа (QUIC -- единственный путь к youtube),
 * потом широта покрытия и только потом скорость.
 */
data class CandidateScore(
    val outcomes: List<TargetOutcome>,
    val udpOk: Boolean
) {
    /** Прошли успешно продукты. */
    val products: Set<String> = outcomes
        .filter { it.latencyMs != null }
        .map { it.target.product }
        .toSet()

    val successes: Int get() = products.size

    /** Суммарная задержка успешных handshake'ей; конкурирует последней. */
    val latencyMs: Long get() = outcomes.mapNotNull { it.latencyMs }.sum()

    /** Все обязательные продукты на месте. */
    val eligible: Boolean get() = products.containsAll(REQUIRED_PRODUCTS)

    /** Число приоритетных продуктов, которых удалось достучаться. */
    fun priorityScore(): Int = products.count { it in PRIORITY_PRODUCTS }

    fun isBetterThan(other: CandidateScore?): Boolean {
        if (other == null) return true
        if (eligible != other.eligible) return eligible
        if (priorityScore() != other.priorityScore()) return priorityScore() > other.priorityScore()
        if (udpOk != other.udpOk) return udpOk
        if (successes != other.successes) return successes > other.successes
        if (latencyMs != other.latencyMs) return latencyMs < other.latencyMs
        return false
    }

    /** Короткая строка для журнала/исхода: «5/7, udp» и подобное. */
    fun summary(): String =
        "$successes/${outcomes.size}${if (udpOk) ", udp" else ""}"
}

/** Один кандидат: код в конфиге и подписи для прогресса. */
data class ProbeCandidate(
    val id: String,
    val ru: String,
    val en: String
) {
    fun label(lang: AppLang): String = if (lang == AppLang.RU) ru else en
}

/**
 * Кандидаты zapret: текущая стратегия первой (если вообще выбрана),
 * потом пресеты по убыванию «помощи»: сначала те, что шире всего режут
 * поток, потом точечные подмены. DISABLED в перебор не идёт: обход
 * выключен -- нечего проверять.
 */
fun zapretCandidates(current: String): List<ProbeCandidate> {
    val ordered = listOf(
        ZapretStrategy.MULTISPLIT_SEQOVL,
        ZapretStrategy.FAKE_FAKEDSPLIT_TS,
        ZapretStrategy.MULTIPLY_ALPN,
        ZapretStrategy.DESYNC_FAKEDSPLIT,
        ZapretStrategy.DESYNC_FAKE_TTL_TCP_ACK,
        ZapretStrategy.DPI_DESYNC,
        ZapretStrategy.FAKE_SNI_PATCH_TLS_CLIENT_HELLO,
        ZapretStrategy.MULTIPATH
    ).filter { it != ZapretStrategy.DISABLED && it != ZapretStrategy.AUTO }

    val selected = ZapretStrategy.of(current)
    val head = if (selected != ZapretStrategy.AUTO && selected != ZapretStrategy.DISABLED) {
        listOf(selected)
    } else {
        emptyList()
    }
    return (head + ordered).distinct().map { it.toCandidate() }
}

/**
 * Кандидаты goodbyedpi: режимы, у которых хватает собственных флагов,
 * чтобы запуститься без ручных настроек. FAKE_SNI здесь нет: без
 * непустого SNI такой режим вообще не стартует, а подставлять чужой
 * домен ради пробы бессмысленно.
 */
fun goodbyeDpiCandidates(current: String): List<ProbeCandidate> {
    val ordered = listOf(
        GoodbyeDpiMode.AUTO,
        GoodbyeDpiMode.SPLIT2,
        GoodbyeDpiMode.SPLIT_POS,
        GoodbyeDpiMode.FAKE_PATCH,
        GoodbyeDpiMode.FAKE_SSL
    )
    val selected = GoodbyeDpiMode.of(current)
    val head = if (selected in ordered) listOf(selected) else emptyList()
    return (head + ordered).distinct().map {
        ProbeCandidate(it.code, it.ru, it.en)
    }
}

/**
 * Кандидаты релея (noroot): параметры разрыва ClientHello.
 *
 * Здесь нет «стратегий zapret» по одной простой причине: без root нет
 * NFQUEUE, и весь обход -- это уровень данных в локальном SOCKS-релее.
 * Перебираются поэтому его собственные ручки: без разрыва (контроль),
 * один разрез с короткой и длинной паузой, двойной разрез с той же
 * гаммой пауз.
 */
fun relayCandidates(current: RelayTuning): List<ProbeCandidate> {
    val ordered = listOf(
        RelayTuning(delayMs = 10, cuts = 1),
        RelayTuning(delayMs = 50, cuts = 1),
        RelayTuning(delayMs = 10, cuts = 2),
        RelayTuning(delayMs = 50, cuts = 2),
        RelayTuning(delayMs = 10, cuts = 0)
    )
    // Текущий тюнинг первым (даже если он вне списка): он уже используется,
    // и его результат -- базовое сравнение для остальных; дубль в ordered
    // схлопнётся distinct'ом.
    return (listOf(current) + ordered).distinct().map {
        ProbeCandidate(
            id = "relay:${it.id}",
            ru = "Разрез ${it.cuts}×${it.delayMs} мс",
            en = "Split ${it.cuts}×${it.delayMs} ms"
        )
    }
}

private fun ZapretStrategy.toCandidate() = ProbeCandidate(code, ru, en)

/** Прогресс перебора: какой кандидат сейчас и сколько их всего. */
data class ProbeProgress(
    val index: Int,
    val total: Int,
    val candidate: ProbeCandidate
)

/**
 * Координатор проб: одна проба в процессе, её прогресс виден UI.
 *
 * Лок здесь же, а не в UI: повторное нажатие кнопки и параллельный
 * запуск из другого места должны упираться в один объект, а не в
 * «кнопка уже выключена».
 */
object StrategyProbeCoordinator {

    private val _progress = MutableStateFlow<ProbeProgress?>(null)
    val progress: StateFlow<ProbeProgress?> = _progress.asStateFlow()

    private var locked = false

    // Отсчёт времени живёт здесь, а не в UI: карточка может быть закрыта
    // и открыта заново посреди перебора, и тогда показанное время обязано
    // продолжаться, а не начинаться заново.
    private var startedAt: TimeMark? = null

    /** Берёт лок пробы: false -- проба уже идёт, второй запуск не нужен. */
    @Synchronized
    fun begin(total: Int): Boolean {
        if (locked) return false
        locked = true
        startedAt = TimeSource.Monotonic.markNow()
        _progress.value = ProbeProgress(0, total, ProbeCandidate("", "", ""))
        return true
    }

    @Synchronized
    fun tick(index: Int, candidate: ProbeCandidate) {
        val total = _progress.value?.total ?: 0
        _progress.value = ProbeProgress(index + 1, total, candidate)
    }

    @Synchronized
    fun finish() {
        locked = false
        startedAt = null
        _progress.value = null
    }

    /** Сколько секунд идёт перебор; 0 -- пробы не было. */
    fun elapsedSec(): Long = startedAt?.elapsedNow()?.inWholeSeconds ?: 0L

    /**
     * Примерное оставшееся время: среднее по завершённым кандидатам,
     * умноженное на оставшиеся (включая текущий).
     *
     * null -- завершённых ещё нет, оценивать не по чему: первая секунда
     * показывает просто прогресс без обещаний.
     */
    fun etaSec(elapsedSec: Long, index: Int, total: Int): Long? {
        val done = index - 1
        if (done < 1 || done >= total) return null
        val remaining = total - done
        return ((elapsedSec.toDouble() / done) * remaining).toLong().coerceAtLeast(1L)
    }
}

/** Итог пробы одного target'а: что применять и насколько хорошо вышло. */
data class StrategyProbeOutcome(
    /** Что проверяли: "zapret" или "gdp". */
    val target: String,
    /** Победитель. */
    val candidate: ProbeCandidate,
    /** Скоринг победителя; null, если пробы не дали данных. */
    val score: CandidateScore?,
    /** Сеть недоступна целиком -- применять нечего. */
    val noData: Boolean,
    /** Код стратегии/режима для применения (root-путь). */
    val strategyCode: String?,
    /** Тюнинг релея для применения (noroot-путь), иначе null. */
    val relay: RelayTuning?,
    val notes: List<String>
)

/**
 * Хуки пробы в основной демон (root-путь goodbyedpi).
 *
 * У goodbyedpi нет своего номера очереди -- он всегда слушает 0, поэтому
 * пробный инстанс нельзя поднять параллельно боевому: на время перебора
 * основной демон останавливается, а после пробы возвращается как был.
 * Хуки отделяют пробу от конкретного способа управлять демоном: движок
 * об этом не знает, а AppState -- знает.
 */
interface ProbeHooks {
    /** Работает ли сейчас основной демон обхода. */
    fun dpiRunning(): Boolean

    /** Останавливает основной демон (вместе с его firewall-правилами). */
    suspend fun stopDpi()

    /** Возвращает демон к жизни с текущим конфигом. */
    suspend fun startDpi()

    companion object {
        /** Пустые хуки: проба не трогает боевых демонов. */
        val NONE: ProbeHooks = object : ProbeHooks {
            override fun dpiRunning(): Boolean = false
            override suspend fun stopDpi() = Unit
            override suspend fun startDpi() = Unit
        }
    }
}

/**
 * Запуск перебора: expect/actual, потому что изолированные инстансы
 * (NFQUEUE под root, свой SOCKS-релей без него) и сокетные пробы --
 * чистая JVM.
 */
expect object StrategyProbe {

    /**
     * Перебирает кандидатов и возвращает итог.
     *
     * null -- лок не взят (проба уже идёт) или инстанс поднять не
     * удалось. Проба никогда не бросает: недоступная сеть -- это
     * валидный итог с noData, а не исключение в UI.
     *
     * [hooks] -- управление боевым демоном на время пробы; используются
     * только root-пробой goodbyedpi, остальные пути обходятся без них.
     */
    suspend fun run(
        target: String,
        zapret: ZapretConfig,
        goodbyeDpi: GoodbyeDpiConfig,
        relay: RelayTuning,
        hooks: ProbeHooks = ProbeHooks.NONE
    ): StrategyProbeOutcome?
}
