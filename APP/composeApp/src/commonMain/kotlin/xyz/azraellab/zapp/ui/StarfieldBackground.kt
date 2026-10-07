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
import kotlin.math.absoluteValue
import kotlin.math.hypot
import kotlin.random.Random
import xyz.azraellab.zapp.core.PerfConfig
import xyz.azraellab.zapp.core.sky.CometFlight
import xyz.azraellab.zapp.core.sky.SkyPoint
import xyz.azraellab.zapp.core.sky.SkyStar
import xyz.azraellab.zapp.core.sky.StarKind
import xyz.azraellab.zapp.core.sky.cometFlight
import xyz.azraellab.zapp.core.sky.cometHead
import xyz.azraellab.zapp.core.sky.cometOpacity
import xyz.azraellab.zapp.core.sky.generateStars
import xyz.azraellab.zapp.core.sky.skyParams
import xyz.azraellab.zapp.core.sky.starBrightness

/**
 * Ночное небо: мигающие звёзды реальных спектральных цветов и белые кометы,
 * проходящие экран от края до края.
 *
 * Вся математика -- чистые функции в [core.sky.SkyMath]; здесь только
 * состояние (seed на запуск, список звёзд и комет) и отрисовка. Seed
 * рождается при создании состояния -- алгоритмы спавна меняются с каждым
 * входом в программу.
 */

/** Палитра неба под текущую тему. На светлой теме набор инвертирован в тёмный. */
data class StarfieldPalette(
    val starColors: Map<StarKind, Color>,
    val flashColor: Color,
    val trailFrom: Color,
    val trailTo: Color
)

private val DARK_STAR_COLORS: Map<StarKind, Color> = mapOf(
    StarKind.WHITE to Color(0xFFFFF4ED),
    StarKind.YELLOW to Color(0xFFFFEB9A),
    StarKind.ORANGE to Color(0xFFFFB35C),
    StarKind.RED to Color(0xFFFF7A6B),
    StarKind.BLUE to Color(0xFF9FB8FF)
)

private val LIGHT_STAR_COLORS: Map<StarKind, Color> = mapOf(
    StarKind.WHITE to Color(0xFF3D3A4E),
    StarKind.YELLOW to Color(0xFF6A5A12),
    StarKind.ORANGE to Color(0xFF7A4A12),
    StarKind.RED to Color(0xFF7A2E28),
    StarKind.BLUE to Color(0xFF2E4A7A)
)

private val DARK_PALETTE = StarfieldPalette(
    starColors = DARK_STAR_COLORS,
    flashColor = Color.White,
    trailFrom = Color.White.copy(alpha = 0f),
    trailTo = Color.White
)

private val LIGHT_PALETTE = StarfieldPalette(
    starColors = LIGHT_STAR_COLORS,
    flashColor = Color(0xFF0A0A0A),
    trailFrom = Color(0xFFC8C8D8).copy(alpha = 0f),
    trailTo = Color(0xFF6E6E82)
)

/** Палитра неба под текущую тему. Чистая функция - её можно проверить тестом. */
internal fun starfieldPalette(dark: Boolean): StarfieldPalette =
    if (dark) DARK_PALETTE else LIGHT_PALETTE

/** Комета в полёте: трек и момент рождения. */
private class CometRuntime(val flight: CometFlight) {
    var bornMs = 0f
}

/**
 * Состояние симуляции: звёзды и кометы. Seed рождается при создании --
 * алгоритмы спавна свои для каждого запуска.
 */
private class StarfieldState(w: Float, h: Float) {
    private val seed = Random(System.nanoTime()).nextLong()
    private val params = skyParams(seed)
    private val rnd = Random(seed xor params.starCount.toLong() * 2654435761L)
    val stars: List<SkyStar> = generateStars(params, rnd)
    val comets = ArrayList<CometRuntime>()
    private var nextCometAtMs = 0f

    init {
        repeat(params.cometMin.coerceAtLeast(1)) { comets.add(spawnComet(w, h)) }
        nextCometAtMs = 0f
    }

    private fun spawnComet(w: Float, h: Float): CometRuntime {
        val speed = params.cometSpeedMinPx + rnd.nextFloat() * (params.cometSpeedMaxPx - params.cometSpeedMinPx)
        return CometRuntime(cometFlight(w, h, rnd, speed, params.cometDriftMax))
    }

    /** Продвинуть на [dtSec]; [nowMs] -- общее время анимации. */
    fun advance(dtSec: Float, w: Float, h: Float, nowMs: Float) {
        nextCometAtMs -= dtSec * 1000f
        if (comets.size < params.cometMin ||
            (comets.size < params.cometMax && nextCometAtMs <= 0f)
        ) {
            nextCometAtMs = params.cometSpawnMinMs + rnd.nextFloat() *
                (params.cometSpawnMaxMs - params.cometSpawnMinMs)
            comets.add(spawnComet(w, h))
            comets.last().bornMs = nowMs
        }

        var ci = comets.size - 1
        while (ci >= 0) {
            val c = comets[ci]
            if (nowMs - c.bornMs >= c.flight.lifeMs) comets.removeAt(ci)
            ci--
        }
    }
}

@Composable
fun StarfieldBackground(modifier: Modifier = Modifier, perf: PerfConfig = PerfConfig()) {
    val density = LocalDensity.current.density
    val page = MaterialTheme.colorScheme.background
    val palette = remember(page) { starfieldPalette(page.luminance() < 0.5f) }
    var tick by remember { mutableIntStateOf(0) }
    val animated = perf.animatedBackground
    val fps = perf.backgroundFps.coerceIn(15, 60)

    BoxWithConstraints(modifier) {
        val wPx = with(LocalDensity.current) { maxWidth.toPx() }
        val hPx = with(LocalDensity.current) { maxHeight.toPx() }
        val state = remember(wPx, hPx) { StarfieldState(wPx, hPx) }

        if (animated) {
            LaunchedEffect(wPx, hPx, fps) {
                val frameMs = 1_000_000_000L / fps
                var last = 0L
                var lastDraw = 0L
                var nowMs = 0f
                while (true) {
                    withFrameNanos { t ->
                        if (t - lastDraw >= frameMs) {
                            if (last != 0L) {
                                val dt = ((t - last) / 1_000_000_000f).coerceAtMost(0.05f)
                                nowMs += dt * 1000f
                                state.advance(dt, wPx, hPx, nowMs)
                            }
                            last = t
                            lastDraw = t
                            tick++
                        }
                    }
                }
            }
        } else {
            LaunchedEffect(wPx, hPx) {
                tick++
            }
        }

        Canvas(Modifier.fillMaxSize()) {
            drawStars(state, size.width, size.height, density, palette)
            state.comets.forEach { drawComet(it, density, palette) }
        }
    }
}

private fun DrawScope.drawStars(
    state: StarfieldState,
    w: Float,
    h: Float,
    d: Float,
    palette: StarfieldPalette
) {
    val tMs = System.nanoTime() / 1_000_000f
    for (s in state.stars) {
        val b = starBrightness(tMs, s.phase, s.blinkLifeMs, s.blinkGapMs)
        val color = palette.starColors[s.kind] ?: palette.trailTo
        val alpha = (0.15f + 0.85f * b).coerceIn(0f, 1f)
        val baseR = s.radiusPx * d
        if (s.glowPx > 0f && b > 0.15f) {
            drawCircle(
                color = color.copy(alpha = alpha * 0.22f),
                radius = baseR * (1f + s.glowPx * 0.9f),
                center = Offset(s.fx * w, s.fy * h)
            )
        }
        drawCircle(
            color = color.copy(alpha = alpha),
            radius = baseR,
            center = Offset(s.fx * w, s.fy * h)
        )
    }
}

/**
 * Комета: белый шлейф, затухающий к хвосту, яркая голова.
 * Возраст считается от [CometRuntime.bornMs]; прозрачность растёт при входе
 * и падает ближе к гибели за противоположной гранью.
 */
private fun DrawScope.drawComet(c: CometRuntime, d: Float, palette: StarfieldPalette) {
    val age = System.nanoTime() / 1_000_000f - c.bornMs
    val op = cometOpacity(c.flight, age)
    if (op <= 0.01f) return
    val head = cometHead(c.flight, age)
    val px = Offset(head.x, head.y)

    // Шлейф: цепочка точек назад от головы по вектору скорости.
    val vx = c.flight.velocity.x
    val vy = c.flight.velocity.y
    val vLen = hypot(vx, vy)
    if (vLen < 1e-4f) return
    val ux = vx / vLen
    val uy = vy / vLen
    val trailLen = (170f * d).coerceIn(24f, 300f)
    val segs = 14
    val points = buildList {
        for (i in 0..segs) {
            val back = trailLen * i / segs
            add(Offset(head.x - ux * back, head.y - uy * back))
        }
    }

    val path = Path().apply {
        moveTo(points[0].x, points[0].y)
        for (i in 1 until points.size - 1) {
            val mid = Offset(
                (points[i].x + points[i + 1].x) / 2f,
                (points[i].y + points[i + 1].y) / 2f
            )
            quadraticTo(points[i].x, points[i].y, mid.x, mid.y)
        }
        lineTo(points.last().x, points.last().y)
    }

    drawPath(
        path = path,
        brush = Brush.linearGradient(
            listOf(
                palette.trailFrom.copy(alpha = 0.05f * op),
                palette.trailTo.copy(alpha = 0.7f * op)
            ),
            start = Offset(points.first().x, points.first().y),
            end = px
        ),
        style = Stroke(
            width = (1.4f + vLen / 500f) * d,
            cap = StrokeCap.Round,
            join = StrokeJoin.Round
        )
    )
    drawCircle(
        color = palette.trailTo.copy(alpha = 0.95f * op),
        radius = 1.2f * d + 0.5f,
        center = px
    )
}