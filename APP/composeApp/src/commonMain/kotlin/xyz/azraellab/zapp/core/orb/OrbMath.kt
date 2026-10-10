package xyz.azraellab.zapp.core.orb

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin
import xyz.azraellab.zapp.core.engine.TunnelState

/**
 * Математика кнопки-орба подключения: фазы анимации и форма blob'а.
 *
 * Всё чистое и без Compose -- рисование (Canvas в [xyz.azraellab.zapp
 * .ui.components.ConnectionOrb]) только исполняет эти формулы, а тесты
 * проверяют их напрямую: замкнутость контура, границы амплитуд, тон по
 * состоянию туннеля.
 */

/** Тон сферы: состояние туннеля -> цветовая семья. */
enum class OrbTone {
    /** Туннель выключен: спокойный нейтральный шар. */
    NEUTRAL,

    /** Подключено: акцент подключения, мягко дышит. */
    LIVE,

    /** Идёт подключение: рабочий тон, крутится спин-кольцо. */
    BUSY,

    /** Ошибка: тон ошибки, глиф-восклицательный. */
    FAIL
}

/** Периоды фаз в миллисекундах -- каждый цикл живёт своей жизнью. */
object OrbPhases {
    /** Морфинг контура blob'а. */
    const val BLOB_MS = 9000f

    /** Дыхание внешнего свечения. */
    const val BREATHE_MS = 4000f

    /** Вращение внутренней дымки. */
    const val SWIRL_MS = 20000f

    /** Пульсация ядра. */
    const val CORE_MS = 7000f

    /** Оборот спин-кольца подключения. */
    const val SPIN_MS = 1100f
}

/** Тон по состоянию туннеля: одна ось для рисования и тестов. */
fun orbTone(state: TunnelState): OrbTone = when (state) {
    TunnelState.DISCONNECTED -> OrbTone.NEUTRAL
    TunnelState.CONNECTING, TunnelState.RECONNECTING -> OrbTone.BUSY
    TunnelState.CONNECTED -> OrbTone.LIVE
    TunnelState.ERROR -> OrbTone.FAIL
}

/** Фаза 0..1 по прошедшему времени: `elapsedMs % period`. */
fun orbPhase(elapsedMs: Float, periodMs: Float): Float {
    require(periodMs > 0f) { "periodMs must be positive" }
    val raw = (elapsedMs % periodMs) / periodMs
    return if (raw < 0f) raw + 1f else raw
}

/**
 * Контур blob'а: окружность плюс три низкочастотные волны (гармоники
 * 2 и 3 с целыми множителями фазы -- поэтому цикл замкнут: фаза 0 и
 * фаза 1 дают одну и ту же кривую). Точки относительно центра (0,0),
 * Canvas сдвигает их в своё место.
 *
 * Параметры согласованы с дыханием ссылочной сферы: амплитуды
 * 4%, 3% и 2.5% радиуса.
 */
fun orbBlobPoints(
    phase: Float,
    radius: Float,
    segments: Int = 120
): List<Pair<Float, Float>> {
    require(radius > 0f) { "radius must be positive" }
    require(segments >= 8) { "segments too small" }
    val p = orbPhase(phase, 1f) * 2f * PI.toFloat()
    return (0..segments).map { i ->
        val angle = (i.toFloat() / segments) * 2f * PI.toFloat()
        val wave = sin(angle * 2f + p) * radius * 0.04f +
            sin(angle * 3f - p) * radius * 0.03f +
            cos(angle * 2f + p * 2f) * radius * 0.025f
        val r = radius + wave
        (r * cos(angle)) to (r * sin(angle))
    }
}

/** Дыхание внешнего свечения: масштаб 1 +/- 8%. */
fun orbBreatheScale(phase: Float): Float =
    1f + 0.08f * sin(orbPhase(phase, 1f) * 2f * PI.toFloat())

/** Прозрачность свечения 0.4..1.0 в такт дыханию (синус ходит и в минус). */
fun orbBreatheAlpha(phase: Float): Float =
    0.7f + 0.3f * sin(orbPhase(phase, 1f) * 2f * PI.toFloat())

/** Масштаб светящегося ядра 0.80..1.04 в такт синуса. */
fun orbCoreScale(phase: Float): Float =
    0.92f + 0.12f * sin(orbPhase(phase, 1f) * 2f * PI.toFloat())

/** Угол спин-кольца по фазе: полный оборот за период. */
fun orbSpinDegrees(phase: Float): Float = orbPhase(phase, 1f) * 360f
