package xyz.azraellab.zapp.ui.theme

import kotlin.test.Test
import kotlin.test.assertTrue

class ThemeTest {

    @Test
    fun schemeIsDark() {
        // Тема одна, тёмная: светлой схемы в приложении нет, и проверять
        // «тёмная ли она» -- единственное, что здесь имеет смысл.
        assertTrue(AzraelDarkScheme.isDark())
    }

    @Test
    fun everySchemeSlotIsReadableOnItsOwnBackground() {
        // Регрессия на первые версии палитры: `primary = #16A34A` давал 3.0:1
        // с белым, `outline = #D4D4D8` -- 1.4:1. Проверяются все foreground-пары,
        // которые реально рисует UI.
        //
        // `surface` в тёмной схеме прозрачный (5% белого), поэтому контраст
        // считается по фактической подложке: `surface`, положенная на
        // `background`, а не по самому прозрачному цвету.
        AzraelDarkScheme.let { scheme ->
            val pairs = listOf(
                "onSurface/surface" to (scheme.onSurface to scheme.surface),
                "onSurfaceVariant/surfaceVariant" to (scheme.onSurfaceVariant to scheme.surfaceVariant),
                "onSurface/surfaceVariant" to (scheme.onSurface to scheme.surfaceVariant),
                "onBackground/background" to (scheme.onBackground to scheme.background),
                "onPrimary/primary" to (scheme.onPrimary to scheme.primary),
                "onSecondary/secondary" to (scheme.onSecondary to scheme.secondary),
                "onTertiary/tertiary" to (scheme.onTertiary to scheme.tertiary),
                "onError/error" to (scheme.onError to scheme.error),
                "onPrimaryContainer/primaryContainer" to (scheme.onPrimaryContainer to scheme.primaryContainer),
                "onSecondaryContainer/secondaryContainer" to (scheme.onSecondaryContainer to scheme.secondaryContainer),
                "onErrorContainer/errorContainer" to (scheme.onErrorContainer to scheme.errorContainer)
            )
            pairs.forEach { (label, pair) ->
                val ratio = contrastOnScheme(pair.second, scheme.background, pair.first)
                assertTrue(
                    ratio >= TEXT_CONTRAST_MIN,
                    "$label = $ratio (нужно ≥ $TEXT_CONTRAST_MIN)"
                )
            }
        }
    }

    @Test
    fun outlineIsVisibleAsABoundary() {
        // Граница -- не текст, но она единственное, что отделяет «стеклянную»
        // карточку от фона. Тёмный `outline` -- 40% белого (`0x66FFFFFF`), и его
        // собственные каналы равны единице: наивный `contrast()` увидел бы чистый
        // белый и рапортовал 18.4:1 -- правдоподобное, но ложное число, потому
        // что подложка под полупрозрачной границей -- тёмный фон.
        val ratio = contrastOver(AzraelDarkScheme.outline, AzraelDarkScheme.background)
        assertTrue(
            ratio >= NON_TEXT_CONTRAST_MIN,
            "outline на фоне = $ratio (нужно ≥ $NON_TEXT_CONTRAST_MIN)"
        )
    }

    @Test
    fun alphaColorsAreMeasuredAfterCompositing() {
        // Проверяем не сам цвет, а результат наложения: `outline` тёмной схемы --
        // это 18% белого поверх почти чёрного, и по нему самому судить нельзя.
        val dark = AzraelDarkScheme
        val composited = contrastOver(AzraelBorder, dark.background)
        assertTrue(
            composited > 1.0,
            "полупрозрачная рамка должна хоть что-то давать на фоне, получили $composited"
        )
    }
}
