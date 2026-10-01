package xyz.azraellab.zapp.ui.theme

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ThemeTest {

    @Test
    fun modeParsesKnownCodesIgnoringCaseAndSpaces() {
        assertEquals(AppThemeMode.SYSTEM, AppThemeMode.of("system"))
        assertEquals(AppThemeMode.LIGHT, AppThemeMode.of("LIGHT"))
        assertEquals(AppThemeMode.DARK, AppThemeMode.of("  dark "))
    }

    @Test
    fun unknownOrMissingCodeFallsBackToSystem() {
        assertEquals(AppThemeMode.SYSTEM, AppThemeMode.of(null))
        assertEquals(AppThemeMode.SYSTEM, AppThemeMode.of(""))
        assertEquals(AppThemeMode.SYSTEM, AppThemeMode.of("solarized"))
    }

    @Test
    fun explicitModesIgnoreSystemValue() {
        for (systemDark in listOf(true, false)) {
            assertFalse(resolveDark(AppThemeMode.LIGHT, systemDark))
            assertTrue(resolveDark(AppThemeMode.DARK, systemDark))
        }
    }

    @Test
    fun systemModeFollowsSystemValue() {
        assertTrue(resolveDark(AppThemeMode.SYSTEM, true))
        assertFalse(resolveDark(AppThemeMode.SYSTEM, false))
    }

    @Test
    fun schemesAreDetectedCorrectly() {
        assertTrue(AzraelDarkScheme.isDark())
        assertFalse(AzraelLightScheme.isDark())
    }

    @Test
    fun everySchemeSlotIsReadableOnItsOwnBackground() {
        // Регрессия на светлую тему: `primary = #16A34A` давал 3.0:1 с белым,
        // а `outline = #D4D4D8` - 1.4:1. Обе схемы проверяются по всем
        // foreground-парам, которые реально рисует UI.
        //
        // `surface` в тёмной схеме прозрачный (5% белого), поэтому контраст
        // считается по фактической подложке: `surface`, положенная на
        // `background`, а не по самому прозрачному цвету.
        listOf("dark" to AzraelDarkScheme, "light" to AzraelLightScheme).forEach { (name, scheme) ->
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
                    "$name: $label = $ratio (нужно ≥ $TEXT_CONTRAST_MIN)"
                )
            }
        }
    }

    @Test
    fun outlineIsVisibleAsABoundaryInBothThemes() {
        // Граница - не текст, но она единственное, что отделяет «стеклянную»
        // карточку от фона. Светлый `outline` был #D4D4D8 и давал 1.4:1, карточка
        // просто исчезала; теперь #78787F.
        //
        // Считаем именно `contrastOver`, а не `contrast`: тёмный `outline` -
        // это 40% белого (`0x66FFFFFF`), и его собственные каналы равны единице.
        // Наивный `contrast()` увидел бы чистый белый и рапортовал 18.4:1 -
        // правдоподобное, но ложное число, потому что подложка под полупрозрачной
        // границей - тёмный фон, а не белый.
        listOf("dark" to AzraelDarkScheme, "light" to AzraelLightScheme).forEach { (name, scheme) ->
            val ratio = contrastOver(scheme.outline, scheme.background)
            assertTrue(
                ratio >= NON_TEXT_CONTRAST_MIN,
                "$name: outline на фоне = $ratio (нужно ≥ $NON_TEXT_CONTRAST_MIN)"
            )
        }
    }

    @Test
    fun alphaColorsAreMeasuredAfterCompositing() {
        // Проверяем не сам цвет, а результат наложения: `outline` тёмной схемы -
        // это 18% белого поверх почти чёрного, и по нему самому судить нельзя.
        val dark = AzraelDarkScheme
        val composited = contrastOver(AzraelBorder, dark.background)
        assertTrue(
            composited > 1.0,
            "полупрозрачная рамка должна хоть что-то давать на фоне, получили $composited"
        )
    }
}
