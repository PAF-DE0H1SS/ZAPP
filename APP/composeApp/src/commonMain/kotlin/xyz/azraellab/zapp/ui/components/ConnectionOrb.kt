package xyz.azraellab.zapp.ui.components

import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import xyz.azraellab.zapp.core.engine.TunnelState
import xyz.azraellab.zapp.core.orb.OrbPhases
import xyz.azraellab.zapp.core.orb.OrbTone
import xyz.azraellab.zapp.core.orb.orbBreatheAlpha
import xyz.azraellab.zapp.core.orb.orbBreatheScale
import xyz.azraellab.zapp.core.orb.orbBlobPoints
import xyz.azraellab.zapp.core.orb.orbCoreScale
import xyz.azraellab.zapp.core.orb.orbPhase
import xyz.azraellab.zapp.core.orb.orbSpinDegrees
import xyz.azraellab.zapp.core.orb.orbTone
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/**
 * Кнопка-орб: статус туннеля и управление подключением в одном кружке.
 *
 * Слои рисования (сверху вниз): свечение, деформируемый контур blob'а,
 * внутренние эффекты (дымка, ядро, кромка, блик), спин-кольцо при
 * подключении, глиф. Форма и фазы -- чистая математика из
 * `core/orb/OrbMath.kt`, здесь только Canvas и состояния.
 *
 * Анимация кадровая (withFrameNanos) и выключается вместе с фоновой
 * анимацией приложения: в выключенном состоянии рисуется один статичный
 * кадр, а кольцо подключения замирает -- это осознанный компромисс
 * режима экономии.
 */
@Composable
fun ConnectionOrb(
    state: TunnelState,
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    animate: Boolean = true,
    size: Dp = 132.dp
) {
    val scheme = MaterialTheme.colorScheme
    val tone = orbTone(state)
    val accent = when (tone) {
        OrbTone.NEUTRAL -> scheme.onSurfaceVariant
        OrbTone.LIVE -> scheme.primary
        OrbTone.BUSY -> scheme.tertiary
        OrbTone.FAIL -> scheme.error
    }
    val gradientEnd = lerp(accent, scheme.surface, 0.6f)

    // Кадровый хронометр: фазы считаются от первой отрисовки, чтобы
    // периоды жили своей жизнью (blob 9с, дыхание 4с, кольцо 1.1с).
    var elapsedMs by remember { mutableFloatStateOf(0f) }
    LaunchedEffect(animate) {
        if (!animate) {
            elapsedMs = 0f
            return@LaunchedEffect
        }
        var startNs = -1L
        while (true) {
            withFrameNanos { now ->
                if (startNs < 0) startNs = now
                elapsedMs = (now - startNs) / 1_000_000f
            }
        }
    }

    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val pressScale by animateFloatAsState(
        targetValue = if (pressed) 0.94f else 1f,
        animationSpec = spring(
            dampingRatio = Spring.DampingRatioMediumBouncy,
            stiffness = Spring.StiffnessMedium
        ),
        label = "orbPress"
    )

    val blobPath = remember { Path() }

    Canvas(
        modifier = modifier
            .size(size)
            .clickable(
                interactionSource = interaction,
                indication = null,
                role = Role.Button,
                onClick = onClick
            )
            .semantics {
                contentDescription = label
                this.role = Role.Button
            }
    ) {
        val box = this.size
        val cx = box.width / 2f
        val cy = box.height / 2f
        // Радиус оставляет место свечению (1.6r) внутри холста.
        val r = box.minDimension / 2f * 0.60f
        val center = Offset(cx, cy)

        val breathe = orbBreatheScale(orbPhase(elapsedMs, OrbPhases.BREATHE_MS))
        val breatheAlpha = orbBreatheAlpha(orbPhase(elapsedMs, OrbPhases.BREATHE_MS))

        // --- 1. Внешнее свечение, дышит в такт ---
        val glowRadius = r * 1.6f * breathe
        drawCircle(
            brush = Brush.radialGradient(
                colors = listOf(
                    accent.copy(alpha = 0.36f * breatheAlpha),
                    accent.copy(alpha = 0.16f * breatheAlpha),
                    Color.Transparent
                ),
                center = center,
                radius = glowRadius
            ),
            radius = glowRadius,
            center = center
        )

        // --- 2. Контур blob'а: три волны, форма живёт своим циклом ---
        val points = orbBlobPoints(orbPhase(elapsedMs, OrbPhases.BLOB_MS), r)
        blobPath.reset()
        points.forEachIndexed { i, (x, y) ->
            if (i == 0) blobPath.moveTo(cx + x, cy + y)
            else blobPath.lineTo(cx + x, cy + y)
        }
        blobPath.close()
        val angle = 160f * PI.toFloat() / 180f
        val len = r * 2.2f
        drawPath(
            path = blobPath,
            brush = Brush.linearGradient(
                colors = listOf(accent, gradientEnd),
                start = Offset(cx - cos(angle) * len / 2f, cy - sin(angle) * len / 2f - r * 0.3f),
                end = Offset(cx + cos(angle) * len / 2f, cy + sin(angle) * len / 2f + r * 0.3f)
            )
        )

        // --- 3. Внутренние эффекты строго внутри контура ---
        clipPath(blobPath) {
            // Дымка: мягкие пятна акцента, вращаются медленным кругом.
            rotate(orbSpinDegrees(orbPhase(elapsedMs, OrbPhases.SWIRL_MS)), pivot = center) {
                val spot = Offset(cx - r * 0.22f, cy - r * 0.18f)
                drawCircle(
                    brush = Brush.radialGradient(
                        colors = listOf(
                            accent.copy(alpha = 0.20f),
                            accent.copy(alpha = 0.06f),
                            Color.Transparent
                        ),
                        center = spot,
                        radius = r * 0.55f
                    ),
                    radius = r * 0.55f,
                    center = spot
                )
            }

            // Ядро: пульсирующая подсветка центра.
            val coreR = r * 0.6f * orbCoreScale(orbPhase(elapsedMs, OrbPhases.CORE_MS))
            drawCircle(
                brush = Brush.radialGradient(
                    colors = listOf(
                        Color.White.copy(alpha = 0.28f),
                        accent.copy(alpha = 0.12f),
                        Color.Transparent
                    ),
                    center = center,
                    radius = coreR
                ),
                radius = coreR,
                center = center
            )

            // Кромка: затемнение к краю контура.
            val shadow = lerp(accent, scheme.surface, 0.55f)
            drawRect(
                brush = Brush.radialGradient(
                    0f to Color.Transparent,
                    0.70f to Color.Transparent,
                    0.88f to shadow.copy(alpha = 0.12f),
                    1f to shadow.copy(alpha = 0.30f),
                    center = center,
                    radius = r * 1.1f
                ),
                size = box
            )

            // Блик сверху и отражение снизу: шар становится шаром.
            val highlight = Offset(cx - r * 0.20f, cy - r * 0.35f)
            drawCircle(
                brush = Brush.radialGradient(
                    colors = listOf(
                        Color.White.copy(alpha = 0.24f),
                        Color.White.copy(alpha = 0.10f),
                        Color.Transparent
                    ),
                    center = highlight,
                    radius = r * 0.55f
                ),
                radius = r * 0.55f,
                center = highlight
            )
            drawCircle(
                brush = Brush.radialGradient(
                    colors = listOf(
                        accent.copy(alpha = 0.18f),
                        accent.copy(alpha = 0.08f),
                        Color.Transparent
                    ),
                    center = Offset(cx, cy + r * 0.5f),
                    radius = r * 0.7f
                ),
                radius = r * 0.7f,
                center = Offset(cx, cy + r * 0.5f)
            )

            // Нажатие: тень растёт вместе с ужиманием (pressScale 1 -> 0.94).
            val shade = ((1f - pressScale) * 4f).coerceIn(0f, 1f)
            if (shade > 0.01f) {
                val dim = scheme.background
                drawCircle(
                    brush = Brush.radialGradient(
                        colors = listOf(
                            dim.copy(alpha = 0.25f * shade),
                            dim.copy(alpha = 0.10f * shade),
                            Color.Transparent
                        ),
                        center = Offset(cx, cy + r * 0.1f),
                        radius = r
                    ),
                    radius = r,
                    center = Offset(cx, cy + r * 0.1f)
                )
            }
        }

        // --- 4. Спин-кольцо: только пока туннель поднимается ---
        if (tone == OrbTone.BUSY) {
            val ringR = r * 1.08f
            drawArc(
                color = accent.copy(alpha = 0.75f),
                startAngle = -90f + orbSpinDegrees(orbPhase(elapsedMs, OrbPhases.SPIN_MS)),
                sweepAngle = 280f,
                useCenter = false,
                topLeft = Offset(cx - ringR, cy - ringR),
                size = Size(ringR * 2f, ringR * 2f),
                style = Stroke(width = 2.5.dp.toPx(), cap = StrokeCap.Round)
            )
        }

        // --- 5. Глиф: сила (вкл/выкл) или восклицание (ошибка) ---
        val glyph = Color.White.copy(alpha = 0.95f)
        val stroke = 2.5.dp.toPx()
        when (tone) {
            OrbTone.FAIL -> {
                drawLine(
                    color = glyph,
                    start = Offset(cx, cy - r * 0.24f),
                    end = Offset(cx, cy + r * 0.02f),
                    strokeWidth = stroke,
                    cap = StrokeCap.Round
                )
                drawCircle(
                    color = glyph,
                    radius = stroke * 0.9f,
                    center = Offset(cx, cy + r * 0.24f)
                )
            }
            OrbTone.BUSY -> Unit // роль занятости отыгрывает кольцо
            else -> {
                // Power-глиф: дуга с разрывом сверху + стойка.
                val icon = r * 0.30f
                drawArc(
                    color = glyph,
                    startAngle = -55f,
                    sweepAngle = 290f,
                    useCenter = false,
                    topLeft = Offset(cx - icon, cy - icon * 0.7f),
                    size = Size(icon * 2f, icon * 2f),
                    style = Stroke(width = stroke, cap = StrokeCap.Round)
                )
                drawLine(
                    color = glyph,
                    start = Offset(cx, cy - icon * 1.3f),
                    end = Offset(cx, cy + icon * 0.15f),
                    strokeWidth = stroke,
                    cap = StrokeCap.Round
                )
            }
        }
    }
}
