package xyz.azraellab.zapp.ui.components

import androidx.compose.material3.ColorScheme
import androidx.compose.ui.graphics.Color
import xyz.azraellab.zapp.ui.theme.AzraelSuccess
import xyz.azraellab.zapp.ui.theme.isDark

/**
 * Цвета компонентов: чистые функции, а не `@Composable`.
 *
 * Тесты `ComponentsTest` идут на чистой JVM (`desktopTest`) и не могут ни
 * разложить композицию, ни спросить `MaterialTheme`. Если бы цвета выбирались
 * внутри `@Composable`, проверять их было бы нечем: единственный способ --
 * отрендерить и посмотреть глазами, а это не тест. Поэтому выбор цвета вынесен
 * сюда, компоненты только читают результат, а тест проверяет ту же функцию,
 * которую рисует экран.
 *
 * Тёмная тема -- единственная. Светлой схемы в приложении нет: тёмный фон с
 * полупрозрачными панелями и звёздами не имеет осмысленного светлого варианта,
 * а «светлая» версия выглядела как тёмное приложение с чёрным текстом.
 */
object ComponentColors {

    /** Семантика состояния: то, чем помечены включённое, выключенное и сбой. */
    enum class State { IDLE, ACTIVE, DANGER }

    /**
     * Насыщенный цвет состояния: рамка и заливка акцентных элементов.
     *
     * Как текст на почти чёрном не годится (`#F59E0B` даёт 8.4, но на белом
     * 2.15), поэтому подпись состояния берёт [stateText] -- свои цвета на
     * тёмную схему.
     */
    fun saturated(state: State): Color = when (state) {
        State.IDLE -> Color(0xFF6B7280)
        State.ACTIVE -> AzraelSuccess
        State.DANGER -> Color(0xFFF43F5E)
    }

    /**
     * Цвет подписи состояния. Тёмная схема единственная, но функция всё равно
     * принимает [ColorScheme]: так код не придётся переписывать, если схема
     * вернётся, и проверка контраста останется по-настоящему.
     *
     * ACTIVE берёт [AzraelSuccess] (6.41:1 на подложке баннера), а не
     * [AzraelStrong]: тот давал 4.47 -- на волос ниже порога 4.5, и подпись
     * «работает» на грани читаемости.
     */
    fun stateText(scheme: ColorScheme, state: State): Color = when (state) {
        State.IDLE -> scheme.onSurfaceVariant
        State.ACTIVE -> AzraelSuccess
        State.DANGER -> Color(0xFFFB7185)
    }

    /** Вариант кнопки. */
    enum class ButtonRole { PRIMARY, SECONDARY, DANGER, GHOST }

    /**
     * Пара «заливка / подпись» для кнопки.
     *
     * Primary 10.60:1, danger 5.03:1, secondary и ghost берут `onSurface` на своём
     * фоне (15.46) -- все выше порога 4.5. Опасная кнопка ближе к порогу, чем
     * остальные, поэтому ослаблять её нельзя.
     */
    fun button(scheme: ColorScheme, role: ButtonRole): Pair<Color, Color> = when (role) {
        ButtonRole.PRIMARY -> scheme.primary to scheme.onPrimary
        ButtonRole.SECONDARY -> scheme.surfaceContainerHigh to scheme.onSurface
        ButtonRole.DANGER -> Color(0xFFF43F5E) to Color(0xFF07170C)
        ButtonRole.GHOST -> Color.Transparent to scheme.onSurface
    }

    /**
     * Подпись неактивного элемента. Ниже порога WCAG намеренно: правило 1.4.3
     * освобождает неактивные элементы управления, потому что отключённая кнопка
     * не обязана читаться, а должна выглядеть недоступной.
     *
     * Альфа существенна, а `contrast()` её не учитывает: считать контраст
     * `onSurfaceVariant` само по себе бессмысленно, нужно наложить на карточку.
     * Проверка в `ComponentsTest` идёт через композит, иначе отключённая подпись
     * выглядела бы ровно так же, как включённая.
     */
    fun disabledText(scheme: ColorScheme): Color = scheme.onSurfaceVariant.copy(alpha = 0.38f)

    /**
     * Подложка информационного блока: слабый тон `onSurface`, чтобы блок
     * читался как зона, а не как карточка с рамкой.
     */
    fun bannerFill(scheme: ColorScheme): Color =
        if (scheme.isDark()) Color(0x14FFFFFF) else Color(0x0A000000)

    /** Цвет заголовка секции: приглушённый, чтобы не спорить с содержимым. */
    fun sectionTitle(scheme: ColorScheme): Color = scheme.onSurfaceVariant
}
