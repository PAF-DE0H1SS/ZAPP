package xyz.azraellab.zapp.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.material3.MaterialTheme
import kotlin.math.PI
import kotlin.math.hypot
import kotlin.math.min
import kotlin.math.sin
import kotlin.random.Random

/**
 * Ночное небо: мерцающие звёзды и белые кометы со шлейфами - перенос /e2 сайта
 * (app/_/e2-bg.tsx) на канвас Compose. Комета, проходящая рядом со звездой,
 * «вспыхивает» её: звезда белеет и разгорается вдвое, затем гаснет и переезжает
 * в новое место - как на сайте.
 *
 * Координаты звёзд хранятся в долях экрана (0..1), поэтому фон сам подстраивается
 * под поворот и resize; координаты комет и радиусы - в пикселях, как в JS.
 */
/**
 * Палитра неба для одной темы.
 *
 * Фон - чистая декорация, поэтому WCAG 1.4.11 к нему не применяется: 3:1 для
 * звёзд требовать нельзя, это не элемент интерфейса. Но на светлой теме белые
 * звёзды на `#FAFAFA` просто исчезают - фон перестаёт существовать, и это
 * настоящий дефект, а не вопрос соответствия. Поэтому на светлой схеме набор
 * инвертируется в тёмный, и контраст звёзд к странице становится ~11:1.
 */
data class StarfieldPalette(
    val stars: List<Pair<Color, Float>>,
    val flashColor: Color,
    val trailFrom: Color,
    val trailTo: Color
)

private class Star {
    var x = 0f          // доля ширины
    var y = 0f          // доля высоты
    var r = 1f          // радиус в «пикселях сайта» (масштабируется плотностью)
    var phase = 0f
    var speed = 1f
    var minO = 0.1f
    var maxO = 0.9f
    var color = Color.White
    var hitTimer = 0
}

private class Comet {
    var x1 = 0f; var y1 = 0f
    var x2 = 0f; var y2 = 0f
    var progress = 0f
    var speed = 0.006f
    var size = 2f
    var life = 1f
    val trail = ArrayDeque<Offset>()

    fun headX(): Float = x1 + (x2 - x1) * min(progress, 1f)
    fun headY(): Float = y1 + (y2 - y1) * min(progress, 1f)
}

/** Состояние симуляции неба: живёт между кадрами, двигается по времени кадра. */
private class StarfieldState {
    val stars = ArrayList<Star>()
    val comets = ArrayList<Comet>()
    private val rnd = Random(System.nanoTime())
    private var nextSpawnMs = 0L
    private var sinceMs = 0L
    var palette: StarfieldPalette = DARK_PALETTE
        private set

    init {
        repeat(STAR_COUNT) { stars.add(newStar()) }
    }

    /**
     * Цвета звёзд по реальному распределению спектральных классов (A-F белые чаще).
     * Палитра приходит снаружи: на светлой теме белые звёзды не видны, поэтому
     * там набор инвертируется в тёмный (см. [starfieldPalette]).
     */
    private fun starColor(palette: List<Pair<Color, Float>>): Color {
        val roll = rnd.nextFloat() * 100f
        var cum = 0f
        for ((c, w) in palette) {
            cum += w
            if (roll < cum) return c
        }
        return palette.last().first
    }

    fun setPalette(palette: StarfieldPalette) {
        this.palette = palette
        for (s in stars) s.color = starColor(palette.stars)
    }

    private fun newStar(): Star = Star().also { s ->
        s.x = rnd.nextFloat()
        s.y = rnd.nextFloat()
        s.r = if (rnd.nextFloat() < 0.15f) 1.8f + rnd.nextFloat() * 1.5f else 0.8f + rnd.nextFloat() * 1.2f
        s.phase = rnd.nextFloat() * 2f * PI.toFloat()
        s.speed = 0.4f + rnd.nextFloat() * 1.6f
        s.minO = 0.08f + rnd.nextFloat() * 0.12f
        s.maxO = 0.85f + rnd.nextFloat() * 0.15f
        s.color = starColor(palette.stars)
        s.hitTimer = 0
    }

    private fun spawnComet(w: Float, h: Float) {
        val startEdge = rnd.nextInt(4)
        val endEdge = (startEdge + 1 + rnd.nextInt(2)) % 4
        fun onEdge(edge: Int): Pair<Float, Float> = when (edge) {
            0 -> -10f to rnd.nextFloat() * h
            1 -> w + 10f to rnd.nextFloat() * h
            2 -> rnd.nextFloat() * w to -10f
            else -> rnd.nextFloat() * w to h + 10f
        }
        val (ax, ay) = onEdge(startEdge)
        val (bx, by) = onEdge(endEdge)
        comets.add(Comet().also {
            it.x1 = ax; it.y1 = ay; it.x2 = bx; it.y2 = by
            it.speed = 0.004f + rnd.nextFloat() * 0.008f
            it.size = 1.0f + rnd.nextFloat() * 2.5f
            it.trail.addLast(Offset(ax, ay))
        })
    }

    /**
     * Продвинуть симуляцию. [dtSec] - время с прошлого кадра: скорости комет на
     * сайте заданы «на кадр» (60 fps), поэтому приводим их к реальному времени.
     */
    fun advance(dtSec: Float, w: Float, h: Float, d: Float) {
        val frameScale = (dtSec * 60f).coerceIn(0f, 3f)
        sinceMs += (dtSec * 1000f).toInt()

        for (s in stars) {
            if (s.hitTimer > 0) {
                s.hitTimer--
                if (s.hitTimer == 0) {
                    val oldR = s.r
                    val oldMin = s.minO
                    val oldMax = s.maxO
                    s.x = rnd.nextFloat()
                    s.y = rnd.nextFloat()
                    s.r = oldR * (0.8f + rnd.nextFloat() * 0.4f)
                    val t = rnd.nextFloat()
                    val k = if (t < 0.3f) 0.4f else if (t < 0.7f) 0.9f else 1.3f
                    s.minO = (oldMin * k).coerceIn(0.02f, 0.2f)
                    s.maxO = (oldMax * k).coerceIn(0.85f, 1f)
                }
            }
        }

        // Комет держим между MIN_COMETS и MAX_COMETS, спавн - по своему интервалу.
        if (comets.size < MIN_COMETS || (comets.size < MAX_COMETS && sinceMs >= nextSpawnMs)) {
            spawnComet(w, h)
            sinceMs = 0
            nextSpawnMs = (COMET_SPAWN_MIN_MS + rnd.nextInt(COMET_SPAWN_MAX_MS - COMET_SPAWN_MIN_MS + 1)).toLong()
        }

        for (c in ArrayList(comets)) {
            c.progress += c.speed * frameScale
            if (c.progress < 1f) {
                val hx = c.headX(); val hy = c.headY()
                for (s in stars) {
                    if (s.hitTimer > 0) continue
                    val sx = s.x * w; val sy = s.y * h
                    val hit = (s.r + 5f) * d
                    if ((hx - sx) * (hx - sx) + (hy - sy) * (hy - sy) < hit * hit) {
                        s.hitTimer = HIT_FRAMES
                        c.x2 = hx; c.y2 = hy; c.progress = 1f
                        break
                    }
                }
            }
            if (c.progress >= 1f) {
                c.life -= 0.04f * frameScale
                if (c.life <= 0f) { comets.remove(c); continue }
            }
            c.trail.addLast(Offset(c.headX(), c.headY()))
            while (c.trail.size > 100) c.trail.removeFirst()
        }
    }

    private companion object {
        const val STAR_COUNT = 110
        const val HIT_FRAMES = 90
        const val MIN_COMETS = 1
        const val MAX_COMETS = 5
        const val COMET_SPAWN_MIN_MS = 2600
        const val COMET_SPAWN_MAX_MS = 7200
    }
}

/**
 * Распределение спектральных классов одинаковое в обеих палитрах - меняется только
 * светлота, чтобы на тёмном небе звёзды остались тёплыми белыми, а на светлом -
 * тёмными синеватыми. Раньше был один набор на оба фона, и на `#FAFAFA` фон
 * выглядел пустым.
 */
private val DARK_PALETTE = StarfieldPalette(
    stars = listOf(
        Color(0xFFFFF5F0) to 55f,
        Color(0xFFFFF0C8) to 18f,
        Color(0xFFFFC878) to 12f,
        Color(0xFFFF9696) to 8f,
        Color(0xFFB4C8FF) to 5f,
        Color(0xFFDEE4FF) to 2f
    ),
    flashColor = Color.White,
    trailFrom = Color(0xFFC8B4FF),
    trailTo = Color.White
)

private val LIGHT_PALETTE = StarfieldPalette(
    stars = listOf(
        Color(0xFF1F2937) to 55f,
        Color(0xFF3F3A2E) to 18f,
        Color(0xFF5C4A22) to 12f,
        Color(0xFF6B3131) to 8f,
        Color(0xFF27407A) to 5f,
        Color(0xFF2E3560) to 2f
    ),
    flashColor = Color(0xFF0A0A0A),
    trailFrom = Color(0xFF7C6BD6),
    trailTo = Color(0xFF1A1A2E)
)

/** Палитра неба под текущую тему. Чистая функция - её можно проверить тестом. */
internal fun starfieldPalette(dark: Boolean): StarfieldPalette =
    if (dark) DARK_PALETTE else LIGHT_PALETTE

private const val TRAIL_LEN_PX = 120f

/** Обрезать шлейф до [TRAIL_LEN_PX] от головы - зеркало trimTrail на сайте. */
private fun trimTrail(trail: List<Offset>): List<Offset> {
    if (trail.size < 2) return trail
    var dist = 0f
    var cut = 0
    for (i in trail.indices.reversed()) {
        if (i == 0) break
        dist += hypot(trail[i].x - trail[i - 1].x, trail[i].y - trail[i - 1].y)
        if (dist > TRAIL_LEN_PX) { cut = i; break }
    }
    return if (cut == 0) trail else trail.subList(cut, trail.size)
}

@Composable
fun StarfieldBackground(modifier: Modifier = Modifier) {
    val state = remember { StarfieldState() }
    val density = LocalDensity.current.density
    // Тема берётся из самой палитры, а не из isSystemInDarkTheme(): фон рисуется под
    // ту же MaterialTheme, что и карточки поверх него, иначе при ручном переключении
    // темы звёзды остались бы белыми на светлой.
    val page = MaterialTheme.colorScheme.background
    val palette = remember(page) { starfieldPalette(page.luminance() < 0.5f) }
    // Перекрашиваем уже созданные звёзды, иначе смена темы оставила бы старые цвета
    // до пересоздания состояния (remember без ключа переживает смену темы).
    LaunchedEffect(palette) { state.setPalette(palette) }
    var tick by remember { mutableIntStateOf(0) }

    BoxWithConstraints(modifier) {
        val wPx = with(LocalDensity.current) { maxWidth.toPx() }
        val hPx = with(LocalDensity.current) { maxHeight.toPx() }

        LaunchedEffect(wPx, hPx) {
            var last = 0L
            while (true) {
                withFrameNanos { t ->
                    if (last != 0L) {
                        state.advance(((t - last) / 1_000_000_000f).coerceAtMost(0.05f), wPx, hPx, density)
                    }
                    last = t
                    tick++
                }
            }
        }

        Canvas(Modifier.fillMaxSize()) {
            // Время для мерцания: монотонный «кадровый» счётчик, как performance.now() на сайте.
            drawStars(state, size.width, size.height, tick * 16.7f, density, palette)
            state.comets.forEach { drawComet(it, density, palette) }
        }
    }
}

private fun DrawScope.drawStars(
    state: StarfieldState,
    w: Float,
    h: Float,
    timeMs: Float,
    d: Float,
    palette: StarfieldPalette
) {
    for (s in state.stars) {
        val r: Float
        val color: Color
        val alpha: Float
        if (s.hitTimer > 60) {
            // Фаза 1: разгорание - радиус +50%, цвет к «вспышке» темы, прозрачность 1.
            val t = (s.hitTimer - 60) / 30f
            r = s.r * (1f + (1f - t) * 0.5f)
            color = lerpTo(s.color, palette.flashColor, 1f - t)
            alpha = 1f
        } else if (s.hitTimer > 0) {
            // Фаза 2: затухание - радиус ×1.5, вспышка, прозрачность падает.
            r = s.r * 1.5f
            color = palette.flashColor
            alpha = s.hitTimer / 60f
        } else {
            val tw = 0.5f + 0.5f * sin(timeMs * 0.001f * s.speed + s.phase)
            r = s.r
            color = s.color
            alpha = s.minO + (s.maxO - s.minO) * tw
        }
        drawCircle(
            color = color.copy(alpha = alpha.coerceIn(0f, 1f)),
            radius = r * d,
            center = Offset(s.x * w, s.y * h)
        )
    }
}

private fun lerpTo(c: Color, target: Color, t: Float): Color = Color(
    red = c.red + (target.red - c.red) * t,
    green = c.green + (target.green - c.green) * t,
    blue = c.blue + (target.blue - c.blue) * t,
    alpha = 1f
)

/**
 * Комета: шлейф пурпурный→вспышка темы, голова - круг цвета вспышки.
 * Зеркало drawNormal; на светлой теме «вспышка» тёмная, иначе шлейф был бы
 * почти белым на почти белом фоне.
 */
private fun DrawScope.drawComet(c: Comet, d: Float, palette: StarfieldPalette) {
    val opacity = if (c.progress >= 1f) c.life else 1f
    if (opacity <= 0f) return
    val trail = trimTrail(c.trail)
    if (trail.size >= 2) {
        val path = Path().apply {
            moveTo(trail[0].x, trail[0].y)
            for (i in 1 until trail.size - 1) {
                quadraticTo(
                    trail[i].x, trail[i].y,
                    (trail[i].x + trail[i + 1].x) / 2f, (trail[i].y + trail[i + 1].y) / 2f
                )
            }
            lineTo(trail.last().x, trail.last().y)
        }
        drawPath(
            path = path,
            brush = Brush.linearGradient(
                listOf(
                    palette.trailFrom.copy(alpha = 0.10f * opacity),
                    palette.trailTo.copy(alpha = 0.85f * opacity)
                ),
                start = Offset(trail.first().x, trail.first().y),
                end = Offset(trail.last().x, trail.last().y)
            ),
            style = Stroke(width = c.size * d, cap = StrokeCap.Round, join = StrokeJoin.Round)
        )
    }
    drawCircle(
        color = palette.trailTo.copy(alpha = 0.9f * opacity),
        radius = c.size * 0.9f * d,
        center = Offset(c.headX(), c.headY())
    )
}
