package xyz.azraellab.zapp.ui.theme

import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.Easing
import androidx.compose.animation.core.FiniteAnimationSpec
import androidx.compose.animation.core.tween
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds

/**
 * Единый язык движения интерфейса.
 *
 * Правила, которые держат весь UI согласованным:
 *
 * - Смена состояния (цвет, геометрия) -- не дольше [state] и с замедлением
 *   [easing]: быстрый старт, мягкая остановка. Дольше выглядит как задержка,
 *   а не как осмысленная анимация.
 * - [fast] -- мелкая реакция (шеврон, чекбокс), [base] -- раскрытия и
 *   переходы внутри страницы.
 * - Подключённое состояние неподвижно: пока сеть работает, экран не должен
 *   ни дышать, ни мигать -- любое движение в этот момент читается как
 *   «что-то происходит».
 * - Системный отключатель анимации уважает Compose сам (анимации идут через
 *   общий clock), здесь дополнительной возни не требуется.
 */
object AzraelMotion {
    /** Замедление по умолчанию: резкий старт, длинный хвост. */
    val easing: Easing = CubicBezierEasing(0.16f, 1f, 0.3f, 1f)

    /** Мгновенная реакция: шеврон, нажатие, мелкие состояния. */
    val fast: Duration = 160.milliseconds

    /** Раскрытия блоков, переходы внутри страницы. */
    val base: Duration = 220.milliseconds

    /** Смена крупного состояния: статус, цвет, геометрия. */
    val state: Duration = 420.milliseconds

    /**
     * Спек анимации по правилам выше: тип вывода (Float, Int, Color...)
     * выводится из места вызова, поэтому одна функция закрывает и шеврон,
     * и fade, и смену цвета.
     */
    fun <T> spec(duration: Duration = base): FiniteAnimationSpec<T> =
        tween(duration.inWholeMilliseconds.toInt(), easing = easing)
}
