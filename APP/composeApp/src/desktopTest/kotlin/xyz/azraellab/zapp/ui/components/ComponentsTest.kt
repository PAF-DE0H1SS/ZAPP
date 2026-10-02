package xyz.azraellab.zapp.ui.components

import androidx.compose.material3.ColorScheme
import androidx.compose.ui.graphics.Color
import kotlin.test.Test
import kotlin.test.assertTrue
import xyz.azraellab.zapp.ui.components.ComponentColors.ButtonRole
import xyz.azraellab.zapp.ui.theme.AzraelDarkScheme
import xyz.azraellab.zapp.ui.theme.TEXT_CONTRAST_MIN
import xyz.azraellab.zapp.ui.theme.composite
import xyz.azraellab.zapp.ui.theme.contrast
import xyz.azraellab.zapp.ui.theme.contrastOver

/**
 * Проверки контраста компонентов.
 *
 * Тесты идут на чистой JVM и не могут разложить композицию, поэтому проверяют
 * [ComponentColors] -- ту же функцию, которую вызывает отрисовка. Формулы
 * берутся из `Contrast.kt`, а не повторяются здесь: повторённая формула
 * разойдётся с исходной и тест станет проверкой самого себя.
 *
 * Тема одна, тёмная, поэтому здесь только [AzraelDarkScheme]: светлой схемы
 * в приложении нет, и проверять её -- значило бы проверять код, которого нет.
 *
 * Каждая пара считается по фактической подложке. У тёмной схемы `surface`
 * прозрачный (5% белого), поэтому «карточка» -- это `surface`, положенная на
 * `background`, а наивный `contrast()` с прозрачным цветом дал бы правдоподобную
 * и бессмысленную цифру.
 */
class ComponentsTest {

    private val scheme: ColorScheme = AzraelDarkScheme

    private fun ColorScheme.card(): Color =
        if (surface.alpha < 1f) composite(surface, background) else surface

    @Test
    fun stateTextIsReadableOnCard() {
        // Регрессия на первую версию: подпись состояния цветом подложки давала
        // 1.13-1.56:1 вместо 4.5. Подложки больше нет, текст нанесён на карточку.
        val card = scheme.card()
        ComponentColors.State.entries.forEach { state ->
            val ratio = contrast(ComponentColors.stateText(scheme, state), card)
            assertTrue(
                ratio >= TEXT_CONTRAST_MIN,
                "текст состояния $state на карточке = $ratio (нужно ≥ $TEXT_CONTRAST_MIN)"
            )
        }
    }

    @Test
    fun saturatedStateDiffersFromCard() {
        // Насыщенный цвет -- рамка и заливка, а не подпись. Ему не нужен порог
        // текста 4.5, но и сливаться с карточкой он не должен: значение ниже
        // единицы означает, что акцент на фоне не виден.
        val card = scheme.card()
        ComponentColors.State.entries.forEach { state ->
            val ratio = contrast(ComponentColors.saturated(state), card)
            assertTrue(
                ratio >= 1.3,
                "насыщенный цвет $state слишком сливается с карточкой ($ratio)"
            )
        }
    }

    @Test
    fun everyButtonRoleKeepsItsLabelReadable() {
        ButtonRole.entries.forEach { role ->
            val (container, content) = ComponentColors.button(scheme, role)
            val base = scheme.card()
            // Ghost рисуется на прозрачной подложке, поэтому контраст
            // считается по карточке, а не по самому `Transparent`.
            val effective = if (container.alpha < 1f) base else container
            val ratio = contrastOver(content, effective)
            assertTrue(
                ratio >= TEXT_CONTRAST_MIN,
                "кнопка $role, подпись = $ratio (нужно ≥ $TEXT_CONTRAST_MIN)"
            )
        }
    }

    @Test
    fun disabledLabelIsDimmerThanEnabled() {
        // Отключённый элемент ниже порога намеренно (WCAG 1.4.3 освобождает
        // неактивные элементы управления), но он обязан быть заметно тусклее
        // включённого -- иначе состояние «недоступно» не читается.
        //
        // Считаем через `composite`: альфа в `disabledText` существенна, а
        // `contrast()` её не учитывает. По самому `onSurfaceVariant` обе подписи
        // дали бы ровно одно число и тест прошёл бы вхолостую.
        val card = scheme.card()
        val enabled = contrast(scheme.onSurface, card)
        val disabled = contrast(composite(ComponentColors.disabledText(scheme), card), card)
        assertTrue(
            disabled < enabled * 0.6,
            "отключённая подпись $disabled должна быть заметно слабее включённой $enabled"
        )
    }

    @Test
    fun bannerKeepsBodyAndTitleReadable() {
        val fill = ComponentColors.bannerFill(scheme)
        val base = scheme.card()
        val effective = if (fill.alpha < 1f) composite(fill, base) else fill

        val body = contrastOver(scheme.onSurfaceVariant, effective)
        assertTrue(
            body >= TEXT_CONTRAST_MIN,
            "текст баннера = $body (нужно ≥ $TEXT_CONTRAST_MIN)"
        )

        ComponentColors.State.entries.forEach { state ->
            val title = contrastOver(ComponentColors.stateText(scheme, state), effective)
            assertTrue(
                title >= TEXT_CONTRAST_MIN,
                "заголовок баннера $state = $title (нужно ≥ $TEXT_CONTRAST_MIN)"
            )
        }
    }

    @Test
    fun sectionTitleAndListSubtitleAreReadable() {
        val card = scheme.card()
        listOf(
            "sectionTitle" to ComponentColors.sectionTitle(scheme),
            "onSurfaceVariant" to scheme.onSurfaceVariant
        ).forEach { (label, fg) ->
            val ratio = contrast(fg, card)
            assertTrue(
                ratio >= TEXT_CONTRAST_MIN,
                "$label на карточке = $ratio (нужно ≥ $TEXT_CONTRAST_MIN)"
            )
        }
    }
}
