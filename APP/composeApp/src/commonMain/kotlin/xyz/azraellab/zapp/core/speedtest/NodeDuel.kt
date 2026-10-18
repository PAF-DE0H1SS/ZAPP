package xyz.azraellab.zapp.core.speedtest

/**
 * Дуэль двух узлов: парные замеры через clash_api с выбором победителя.
 *
 * Замер идёт **через** узел (`GET /proxies/<тег>/delay`), пока туннель
 * поднят: это честное измерение всего тракта — TLS, рукопожатия,
 * географии — а не прямая доступность сервера, которая живёт отдельно
 * (см. probeLink). Пробы двух сторон идут вперемешку (A, B, A, B...),
 * чтобы временной сдвиг сети не достался только одной стороне.
 */

/** Сколько проб на сторону; берётся лучшая, средняя и разброс. */
const val DUEL_PROBES_PER_SIDE: Int = 3

/**
 * Порог ничьей: разница средних меньше этого -- победителя нет.
 *
 * 20 мс -- эмпирически: промах десятков миллисекунд на фоне джиттера
 * реальной сети -- шум, объявлять по нему победителя значит врать
 * пользователю.
 */
const val DUEL_TIE_MARGIN_MS: Int = 20

/** Итог одной стороны дуэли: агрегат её проб. */
data class DuelSide(
    val tag: String,
    val probes: Int,
    val ok: Int,
    val minMs: Int,
    val avgMs: Int,
    val spreadMs: Int
) {
    /** Ни одна проба не прошла: узел (или api) мёртв. */
    val alive: Boolean get() = ok > 0
}

/** Сторона, показавшая лучшее среднее с отрывом; null -- ничья. */
enum class DuelWinner { A, B }

/** Итог дуэли: обе стороны и вердикт (null -- ничья). */
data class DuelResult(
    val a: DuelSide,
    val b: DuelSide,
    val winner: DuelWinner?
)

/**
 * Агрегат пробы: [samples] -- задержки по порядку, null = сбой пробы.
 *
 * Счётчик проб честный: сбой -- это тоже замер (время ушло на таймаут),
 * и мёртвый узел получает probes=3, ok=0, а не «нуль замеров».
 */
fun duelSide(tag: String, samples: List<Int>): DuelSide {
    val ok = samples.filter { it >= 0 }
    if (ok.isEmpty()) return DuelSide(tag, samples.size, 0, -1, -1, -1)
    return DuelSide(
        tag = tag,
        probes = samples.size,
        ok = ok.size,
        minMs = ok.min(),
        avgMs = ok.average().toInt(),
        spreadMs = ok.max() - ok.min()
    )
}

/**
 * Вердикт: лучшее среднее с отрывом [DUEL_TIE_MARGIN_MS].
 *
 * Мёртвый узел проигрывает живому даже без отрыва: недоступный узел
 * не может быть «примерно так же хорош». Оба мертвы -- ничья, как и
 * равные средние.
 */
fun duelResult(a: DuelSide, b: DuelSide): DuelResult = when {
    a.alive && !b.alive -> DuelResult(a, b, DuelWinner.A)
    !a.alive && b.alive -> DuelResult(a, b, DuelWinner.B)
    !a.alive && !b.alive -> DuelResult(a, b, null)
    a.avgMs + DUEL_TIE_MARGIN_MS <= b.avgMs -> DuelResult(a, b, DuelWinner.A)
    b.avgMs + DUEL_TIE_MARGIN_MS <= a.avgMs -> DuelResult(a, b, DuelWinner.B)
    else -> DuelResult(a, b, null)
}

/**
 * Оркестратор дуэли: проба через узел подставляется извне ([probe]),
 * чтобы вся логика -- чередование сторон, прогресс, итог -- гонялась
 * в тестах без бокса и сокета. null в probe -- сбой пробы.
 */
class NodeDuelRunner(private val probe: suspend (tag: String) -> Int?) {

    /**
     * Дуэль: [DUEL_PROBES_PER_SIDE] проб на сторону вперемешку,
     * [onProgress] получает (сделано, всего) после каждой пробы.
     */
    suspend fun run(
        aTag: String,
        bTag: String,
        onProgress: (done: Int, total: Int) -> Unit = { _, _ -> }
    ): DuelResult {
        val total = DUEL_PROBES_PER_SIDE * 2
        val aSamples = ArrayList<Int>(DUEL_PROBES_PER_SIDE)
        val bSamples = ArrayList<Int>(DUEL_PROBES_PER_SIDE)
        var done = 0
        repeat(DUEL_PROBES_PER_SIDE) {
            aSamples.add(probe(aTag) ?: -1)
            onProgress(++done, total)
            bSamples.add(probe(bTag) ?: -1)
            onProgress(++done, total)
        }
        return duelResult(duelSide(aTag, aSamples), duelSide(bTag, bSamples))
    }
}
