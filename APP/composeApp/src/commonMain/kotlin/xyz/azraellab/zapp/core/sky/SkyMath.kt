package xyz.azraellab.zapp.core.sky

import kotlin.math.PI
import kotlin.math.absoluteValue
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin
import kotlin.random.Random

/**
 * Математика ночного неба.
 *
 * Всё чистое и тестируемое: [SkySeed] -> параметры алгоритма на один запуск
 * приложения, далеко от Compose. Каждый заход в программу рождает новый seed,
 * поэтому алгоритмы спавна (число звёзд, времена жизни миганий, скорости и
 * наклон комет) меняются от запуска к запуску.
 *
 * Единицы: все координаты в пикселях, время -- в миллисекундах, углы -- в
 * радианах (положительное направление по часовой стрелке от оси X).
 */

/** Спектральный класс звезды: цвет задаётся в палитре, здесь -- только доля. */
enum class StarKind {
    WHITE,     // A-F: тёплые белые (самые частые)
    YELLOW,    // G-K: жёлтые
    ORANGE,    // K: оранжевые
    RED,       // M: красные редкие
    BLUE       // O-B: голубые редкие
}

/** Точка в плоскости. */
data class SkyPoint(val x: Float, val y: Float)

/** Отрезок: спавн и отрезок полёта. */
data class CometLine(
    val birth: SkyPoint, // точка, откуда комета начала путь (вне экрана)
    val death: SkyPoint  // точка, куда приходит голова к концу жизни (вне экрана)
)

/**
 * Звезда: координаты в долях экрана, радиус в пикселях, цвет и параметры
 * мигания. Время жизни мигания [blinkLifeMs] -- длительность одной яркой
 * фазы; период = [blinkLifeMs] + [blinkGapMs]. Фаза [phase] задаёт случайный
 * порядок вспышек.
 */
data class SkyStar(
    val fx: Float,
    val fy: Float,
    val radiusPx: Float,
    val kind: StarKind,
    val phase: Float,       // 0..1 -- сдвиг цикла
    val blinkLifeMs: Float, // сколько звезда «горит» за одну вспышку
    val blinkGapMs: Float,  // сколько звёзда «спит»
    val glowPx: Float       // радиус мягкого ореола (0 -- звёздочка-точка)
)

/**
 * Параметры одного запуска: как именно рождаются звёзды и кометы.
 * Выводятся из [SkySeed] детерминированно: один seed -- один набор.
 */
data class SkyParams(
    val starCount: Int,
    val blinkLifeMinMs: Float,
    val blinkLifeMaxMs: Float,
    val blinkGapMinMs: Float,
    val blinkGapMaxMs: Float,
    val cometMin: Int,
    val cometMax: Int,
    val cometSpeedMinPx: Float,  // px/s
    val cometSpeedMaxPx: Float,  // px/s
    val cometSpawnMinMs: Float,
    val cometSpawnMaxMs: Float,
    val cometDriftMax: Float     // макс. реальный наклон траектории относительно диагонали
)

/**
 * Детерминированный генератор параметров из seed.
 *
 * Сначала делается фиксированное число прогревающих вызовов RNG, потом из
 * отдельных бросков собираются все диапазоны. Гарантируется: одинаковый
 * [seed] -- одинаковый результат, разные seeds -- (почти наверняка) разные.
 */
fun skyParams(seed: Long): SkyParams {
    val rnd = Random(seed)
    // Прогрев: первые значения не зависят от дней/тиков и проматывают RNG.
    repeat(7) { rnd.nextInt() }

    val starCount = 85 + rnd.nextInt(56)                   // 85..140
    val lifeRatio = 0.5f + rnd.nextFloat() * 2.5f           // длина яркой фазы
    val gapRatio = 0.35f + rnd.nextFloat() * 2.2f           // длина тёмной фазы

    fun scaled(baseMs: Float, ratio: Float): Float = (baseMs * ratio).coerceIn(180f, 9000f)

    val cometMin = 1 + rnd.nextInt(2)
    val cometMax = cometMin + 1 + rnd.nextInt(3)
    return SkyParams(
        starCount = starCount,
        blinkLifeMinMs = scaled(700f, lifeRatio * (0.8f + rnd.nextFloat() * 0.4f)),
        blinkLifeMaxMs = scaled(2600f, lifeRatio * (0.8f + rnd.nextFloat() * 0.4f)),
        blinkGapMinMs = scaled(500f, gapRatio * (0.7f + rnd.nextFloat() * 0.6f)),
        blinkGapMaxMs = scaled(3400f, gapRatio * (0.7f + rnd.nextFloat() * 0.6f)),
        cometMin = cometMin,
        cometMax = cometMax,
        cometSpeedMinPx = 320f + rnd.nextFloat() * 200f,
        cometSpeedMaxPx = 720f + rnd.nextFloat() * 480f,
        cometSpawnMinMs = 1900f + rnd.nextFloat() * 1400f,
        cometSpawnMaxMs = 4200f + rnd.nextFloat() * 4200f,
        cometDriftMax = 0.25f + rnd.nextFloat() * 0.45f
    )
}

/**
 * Генерирует полный набор звёзд для [params].
 *
 * [rnd] уже прогрет: распределение точек uniform, радиусы -- с перекосом к
 * мелким (большинство -- точки), времена жизни и паузы лог-равномерные в
 * своём диапазоне (больше коротких, реже длинные).
 */
fun generateStars(params: SkyParams, rnd: Random): List<SkyStar> =
    List(params.starCount) { _ ->
        val kind = starKind(rnd.nextFloat())
        val fx = rnd.nextFloat() * 0.96f + 0.02f
        val fy = rnd.nextFloat() * 0.96f + 0.02f
        val big = rnd.nextFloat()
        val radius = if (big < 0.78f) 0.8f + rnd.nextFloat() * 0.9f
        else 1.6f + rnd.nextFloat() * 1.4f
        val glow = if (radius > 1.8f) 1.4f + rnd.nextFloat() * 4.0f else 0f
        SkyStar(
            fx = fx,
            fy = fy,
            radiusPx = radius,
            kind = kind,
            phase = rnd.nextFloat(),
            blinkLifeMs = logUniform(params.blinkLifeMinMs, params.blinkLifeMaxMs, rnd),
            blinkGapMs = logUniform(params.blinkGapMinMs, params.blinkGapMaxMs, rnd),
            glowPx = glow
        )
    }

/** Распределение спектральных классов: A-F белые чаще всего, M/B редкие. */
fun starKind(roll: Float): StarKind = when {
    roll < 0.58f -> StarKind.WHITE
    roll < 0.78f -> StarKind.YELLOW
    roll < 0.92f -> StarKind.ORANGE
    roll < 0.97f -> StarKind.RED
    else -> StarKind.BLUE
}

/** Лог-равномерное значение в [min, max]. */
fun logUniform(min: Float, max: Float, rnd: Random): Float {
    val lnMin = kotlin.math.ln(min)
    val lnMax = kotlin.math.ln(max)
    return kotlin.math.exp(lnMin + (lnMax - lnMin) * rnd.nextFloat())
}

/**
 * Яркость звезды в момент времени [tMs].
 *
 * Звезда горит [blinkLifeMs], спит [blinkGapMs], цикл смещён на [phase].
 * Внутри яркой фазы плавное разгорание и затухание (края 20%).
 */
fun starBrightness(
    tMs: Float,
    phase: Float,
    blinkLifeMs: Float,
    blinkGapMs: Float
): Float {
    val cycle = blinkLifeMs + blinkGapMs
    if (cycle <= 0f) return 0f
    val t = (tMs / cycle) + phase
    val p = t - kotlin.math.floor(t)
    if (p * cycle < blinkLifeMs) {
        val x = p * cycle
        if (x >= blinkLifeMs) return 0f
        // Плавные края: первые и последние 15-25% яркой фазы -- рампы.
        val edge = (blinkLifeMs * 0.18f).coerceIn(60f, 380f)
        if (x < edge) return x / edge
        if (x > blinkLifeMs - edge) return (blinkLifeMs - x) / edge
        return 1f
    }
    return 0f
}

/**
 * Траектория кометы, проходящей экран целиком.
 *
 * Спавн -- на отрезке между [edge] и концом маржа за пределами прямоугольника
 * [w]x[h]; голова движется по вектору между двумя точками [from]/[to]
 * (прикинуты так, что путь -- почти диагональ, со случайным наклоном в
 * пределах [drift]). Возвращает от нулевой точки [spawnPoint] и вектора
 * скорости на пиксель экрана, время полёта от рождения до гибели.
 */
data class CometFlight(
    val spawn: SkyPoint,
    val velocity: SkyPoint,     // px/s
    val lifeMs: Float
)

/**
 * Выбор направления по «времени старта»: от случайной грани к случайной
 * грани, не противоположной чужим ним (диагональ выглядит лучше всего, когда
 * комета идёт из угла в угол).
 *
 * Число шагов фиксировано, чтобы тесты не зависели от количества вызовов RNG.
 */
fun cometFlight(
    w: Float,
    h: Float,
    rnd: Random,
    speedPx: Float,
    drift: Float
): CometFlight {
    val margin = 160f

    // Сторона вылета: 0=лево, 1=верх, 2=право, 3=низ. Сторона появления --
    // всегда противоположная (диагональ через весь экран).
    val fromSide = rnd.nextInt(4)
    val toSide = (fromSide + 2) % 4

    // Точка спавна: маржа за гранью, плюс случайное смещение вдоль грани.
    fun sidePoint(side: Int): SkyPoint {
        val along = rnd.nextFloat()
        return when (side) {
            0 -> SkyPoint(-margin, along * h)
            1 -> SkyPoint(along * w, -margin)
            2 -> SkyPoint(w + margin, along * h)
            else -> SkyPoint(along * w, h + margin)
        }
    }

    val from = sidePoint(fromSide)

    // Базовый вектор прямо на противоположную грань, потом лёгкий наклон.
    val base = sidePoint(toSide)
    var dx = base.x - from.x
    var dy = base.y - from.y
    val baseLen = hypot(dx, dy)
    val baseUx = dx / baseLen
    val baseUy = dy / baseLen
    // Наклоняем единичный вектор в пределах drift радиан.
    val ang = atan2(baseUy, baseUx) + (rnd.nextFloat() * 2f - 1f) * drift
    val ux = cos(ang)
    val uy = sin(ang)

    // Точка выхода луча (from + t*u) из прямоугольника, расширенного на маржу,
    // плюс небольшой овершут -- голова гарантированно гаснет ЗА пределами.
    val tl = SkyPoint(-margin, -margin)
    val br = SkyPoint(w + margin, h + margin)
    val tExit = rayExit(from, ux, uy, tl, br) + 60f

    // Голова идёт до выхода плюс ещё кусок шлейфа: жизнь от спавна до tExit.
    val travel = tExit
    val life = (travel / speedPx) * 1000f

    return CometFlight(
        spawn = from,
        velocity = SkyPoint(ux * speedPx, uy * speedPx),
        lifeMs = life
    )
}

/**
 * Параметр t, при котором луч (origin + t*dir, unit) покидает прямоугольник.
 * Гарантирует, что комета исчезает ПОСЛЕ границы, а не внутри экрана.
 */
fun rayExit(origin: SkyPoint, ux: Float, uy: Float, topLeft: SkyPoint, bottomRight: SkyPoint): Float {
    fun slab(p: Float, dir: Float, lo: Float, hi: Float): Pair<Float, Float> {
        if (dir.absoluteValue < 1e-6f) return if (p in lo..hi) (-1e9f to 1e9f) else (1e9f to -1e9f)
        val t1 = (lo - p) / dir
        val t2 = (hi - p) / dir
        return if (t1 < t2) (t1 to t2) else (t2 to t1)
    }

    val (t1x, t2x) = slab(origin.x, ux, topLeft.x, bottomRight.x)
    val (t1y, t2y) = slab(origin.y, uy, topLeft.y, bottomRight.y)
    val enter = maxOf(t1x, t1y)
    val exit = minOf(t2x, t2y)
    // Луч рождается вне прямоугольника: точка входа должна быть >0, иначе
    // комета сразу «внутри». Берём максимум с 0 и небольшой гарантией.
    val start = maxOf(enter, 0f)
    return maxOf(exit, start + 1f).also { require(it >= start) { "ray must exit forward" } }
}

/** Позиция головы кометы в [tMs] после рождения. */
fun cometHead(f: CometFlight, tMs: Float): SkyPoint = SkyPoint(
    f.spawn.x + f.velocity.x * (tMs / 1000f),
    f.spawn.y + f.velocity.y * (tMs / 1000f)
)

/** Прозрачность головы на [tMs]: разгорание на старте, затухание к смерти. */
fun cometOpacity(f: CometFlight, tMs: Float, rampMs: Float = 500f): Float {
    if (tMs <= 0f) return 0f
    val fadeIn = (tMs / rampMs).coerceIn(0f, 1f)
    val fadeOut = ((f.lifeMs - tMs) / rampMs).coerceIn(0f, 1f)
    return (fadeIn * fadeOut).coerceIn(0f, 1f)
}