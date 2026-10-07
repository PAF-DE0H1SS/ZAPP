package xyz.azraellab.zapp.core.dev

import androidx.compose.ui.graphics.Color
import xyz.azraellab.zapp.ui.components.ComponentColors
import xyz.azraellab.zapp.ui.components.ComponentColors.ButtonRole
import xyz.azraellab.zapp.ui.theme.AzraelDarkScheme
import xyz.azraellab.zapp.ui.theme.AzraelPrimary
import xyz.azraellab.zapp.ui.theme.AzraelSuccess
import xyz.azraellab.zapp.ui.theme.NON_TEXT_CONTRAST_MIN
import xyz.azraellab.zapp.ui.theme.TEXT_CONTRAST_MIN
import xyz.azraellab.zapp.ui.theme.composite
import xyz.azraellab.zapp.ui.theme.contrast
import xyz.azraellab.zapp.ui.theme.contrastOnScheme
import xyz.azraellab.zapp.ui.theme.contrastOver
import xyz.azraellab.zapp.ui.theme.isDark
import xyz.azraellab.zapp.ui.theme.relativeLuminance

/**
 * Тема и цвета: палитра, контраст, компоненты.
 *
 * Формулы -- из `ui/theme/Contrast.kt` (того же, что использует отрисовка):
 * тест вычисляет контраст теми же функциями, которыми пользовался бы UI,
 * иначе проверка проверяла бы саму себя.
 *
 * Пороги WCAG: текст 4.5:1, нетекстовое (границы, иконки) 3:1.
 */
private val scheme = AzraelDarkScheme

/** Фактическая подложка карточки: surface в тёмной схеме полупрозрачный. */
private fun card(): Color =
    if (scheme.surface.alpha < 1f) composite(scheme.surface, scheme.background) else scheme.surface

private val SCHEME_PAIRS = listOf(
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

internal fun themeTests(out: MutableList<DevTest>) {

    out += DevTest("theme/scheme-is-dark", DevCategory.THEME, "Схема тёмная") {
        checkTrue(scheme.isDark(), "AzraelDarkScheme должна быть тёмной")
    }

    // Каждая foreground-пара -- отдельный тест: после провала видно, какой
    // именно элемент стал нечитаемым. Порядок аргументов контрактный:
    // (фон, задник, текст) -- как в contrastOnScheme.
    SCHEME_PAIRS.forEach { (label, pair) ->
        val foreground = pair.first
        val background = pair.second
        out += DevTest(
            "theme/pair-${label.lowercase().replace(Regex("[^a-z]+"), "-")}",
            DevCategory.THEME,
            "Контраст $label >= $TEXT_CONTRAST_MIN"
        ) {
            val ratio = contrastOnScheme(background, scheme.background, foreground)
            checkTrue(
                ratio >= TEXT_CONTRAST_MIN,
                "$label = $ratio (нужно >= $TEXT_CONTRAST_MIN)"
            )
        }
    }

    out += DevTest("theme/outline-visible", DevCategory.THEME, "Граница видима на фоне (>= 3:1)") {
        val ratio = contrastOver(scheme.outline, scheme.background)
        checkTrue(ratio >= NON_TEXT_CONTRAST_MIN, "outline на фоне = $ratio")
    }

    out += DevTest("theme/outline-composited", DevCategory.THEME, "Полупрозрачная граница считается после наложения") {
        val ratio = contrastOver(scheme.outlineVariant, scheme.background)
        checkTrue(ratio > 1.0, "рамка должна давать контраст на фоне, получили $ratio")
    }

    // --- Формулы ---

    out += DevTest("theme/contrast-black-white-max", DevCategory.THEME, "Контраст чёрного к белому -- 21:1") {
        val ratio = contrast(Color.Black, Color.White)
        checkTrue(kotlin.math.abs(ratio - 21.0) < 0.01, "получили $ratio")
    }

    out += DevTest("theme/contrast-same-color", DevCategory.THEME, "Один и тот же цвет -- контраст 1:1") {
        val ratio = contrast(Color(0xFF4ADE80), Color(0xFF4ADE80))
        checkEq(1.0, ratio, "контраст одинаковых цветов")
    }

    out += DevTest("theme/luminance-bounds", DevCategory.THEME, "Яркость: чёрный 0, белый 1") {
        checkEq(0.0, relativeLuminance(Color.Black), "чёрный")
        checkEq(1.0, relativeLuminance(Color.White), "белый")
    }

    out += DevTest("theme/luminance-monotonic", DevCategory.THEME, "Яркость растёт со светлотой") {
        val dark = relativeLuminance(Color(0xFF111111))
        val mid = relativeLuminance(Color(0xFF888888))
        val light = relativeLuminance(Color(0xFFEEEEEE))
        checkTrue(dark < mid && mid < light, "порядок яркости нарушен: $dark $mid $light")
    }

    out += DevTest("theme/composite-opaque-noop", DevCategory.THEME, "Непрозрачный цвет не меняется от наложения") {
        val fg = Color(0xFF22C55E)
        val bg = Color(0xFF000000)
        checkEq(fg, composite(fg, bg), "непрозрачный fg")
    }

    out += DevTest("theme/composite-alpha-blends", DevCategory.THEME, "Полупрозрачный цвет смешивается с фоном") {
        // 50% белого поверх чёрного -- серый с альфой 1.
        val half = composite(Color(0x80FFFFFF), Color(0xFF000000))
        checkEq(1f, half.alpha, "результат непрозрачен")
        checkTrue(half.red > 0.4f && half.red < 0.6f, "смесь ближе к серому, red=${half.red}")
    }

    out += DevTest("theme/contrast-over-alpha-matters", DevCategory.THEME, "contrastOver учитывает альфу") {
        val solid = contrastOver(Color(0xFFFFFFFF), Color(0xFF000000))
        val faint = contrastOver(Color(0x33FFFFFF), Color(0xFF000000))
        checkTrue(faint < solid, "слабая альфа должна давать меньший контраст: $faint vs $solid")
    }

    // --- Компоненты ---

    out += DevTest("theme/state-text-readable", DevCategory.THEME, "Подписи состояний читаются на карточке") {
        val base = card()
        ComponentColors.State.entries.forEach { state ->
            val ratio = contrast(ComponentColors.stateText(scheme, state), base)
            checkTrue(
                ratio >= TEXT_CONTRAST_MIN,
                "stateText($state) = $ratio (нужно >= $TEXT_CONTRAST_MIN)"
            )
        }
    }

    ComponentColors.State.entries.forEach { state ->
        out += DevTest(
            "theme/saturated-${state.name.lowercase()}",
            DevCategory.THEME,
            "Насыщенный цвет $state виден на карточке (>= 1.3)"
        ) {
            val ratio = contrast(ComponentColors.saturated(state), card())
            checkTrue(ratio >= 1.3, "saturated($state) слишком тускл: $ratio")
        }
    }

    ButtonRole.entries.forEach { role ->
        out += DevTest(
            "theme/button-${role.name.lowercase()}",
            DevCategory.THEME,
            "Подпись кнопки $role читается (>= $TEXT_CONTRAST_MIN)"
        ) {
            val (container, content) = ComponentColors.button(scheme, role)
            val base = card()
            val effective = if (container.alpha < 1f) base else container
            val ratio = contrastOver(content, effective)
            checkTrue(ratio >= TEXT_CONTRAST_MIN, "кнопка $role = $ratio")
        }
    }

    out += DevTest("theme/disabled-dimmer", DevCategory.THEME, "Отключённая подпись заметно тусклее активной") {
        val base = card()
        val enabled = contrast(scheme.onSurface, base)
        val disabled = contrast(composite(ComponentColors.disabledText(scheme), base), base)
        checkTrue(
            disabled < enabled * 0.6,
            "disabled=$disabled должна быть слабее enabled=$enabled"
        )
    }

    out += DevTest("theme/banner-readable", DevCategory.THEME, "Текст баннера читается на его подложке") {
        val base = card()
        val fill = ComponentColors.bannerFill(scheme)
        val effective = if (fill.alpha < 1f) composite(fill, base) else fill
        val body = contrastOver(scheme.onSurfaceVariant, effective)
        checkTrue(body >= TEXT_CONTRAST_MIN, "тело баннера = $body")
        ComponentColors.State.entries.forEach { state ->
            val title = contrastOver(ComponentColors.stateText(scheme, state), effective)
            checkTrue(
                title >= TEXT_CONTRAST_MIN,
                "заголовок баннера $state = $title"
            )
        }
    }

    out += DevTest("theme/section-title-readable", DevCategory.THEME, "Заголовок секции читается на карточке") {
        val ratio = contrast(ComponentColors.sectionTitle(scheme), card())
        checkTrue(ratio >= TEXT_CONTRAST_MIN, "sectionTitle = $ratio")
    }

    out += DevTest("theme/banner-fill-translucent", DevCategory.THEME, "Подложка баннера полупрозрачная") {
        val fill = ComponentColors.bannerFill(scheme)
        checkTrue(fill.alpha < 1f, "баннер должен быть полупрозрачным, alpha=${fill.alpha}")
        checkTrue(fill.alpha > 0f, "баннер не должен быть полностью прозрачным")
    }

    // --- Палитра ---

    val palette = listOf(
        "primary" to (AzraelPrimary to Color(0xFF4ADE80)),
        "success" to (AzraelSuccess to Color(0xFF22C55E)),
        "danger" to (scheme.error to Color(0xFFF43F5E)),
        "warning" to (scheme.tertiary to Color(0xFFF59E0B)),
        "background" to (scheme.background to Color(0xFF0A0A0A))
    )
    palette.forEach { (label, pair) ->
        out += DevTest(
            "theme/palette-$label",
            DevCategory.THEME,
            "Цвет $label не изменился (locked design token)"
        ) {
            checkEq(pair.second, pair.first, "$label")
        }
    }

    out += DevTest("theme/primary-with-on-primary", DevCategory.THEME, "Основная пара primary/onPrimary >= 7:1") {
        val ratio = contrast(scheme.primary, scheme.onPrimary)
        checkTrue(ratio >= 7.0, "primary/onPrimary = $ratio")
    }

    out += DevTest("theme/danger-with-on-error", DevCategory.THEME, "Опасный цвет читается со своим текстом") {
        val ratio = contrast(scheme.error, scheme.onError)
        checkTrue(ratio >= TEXT_CONTRAST_MIN, "error/onError = $ratio")
    }

    out += DevTest("theme/containers-pair", DevCategory.THEME, "Контейнер primary читается со своим текстом") {
        val ratio = contrast(scheme.primaryContainer, scheme.onPrimaryContainer)
        checkTrue(ratio >= TEXT_CONTRAST_MIN, "primaryContainer = $ratio")
    }
}
