package xyz.azraellab.zapp.ui.theme

import androidx.compose.material3.ColorScheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.graphics.Color

/**
 * Палитра проекта. Значения тёмной схемы - те же, что были до редизайна, редизайн
 * не должен выглядеть как другой бренд; светлая схема добавлена.
 *
 * ## Как выбирать `outline` и `outlineVariant`
 *
 * Раньше обе границы брали из одного места и потому незаметно разъехались: где-то
 * `outline`, где-то `alpha=0.10` от `onSurface`. Токены делятся по смыслу, а не по красоте:
 *
 * - [outlineVariant] - **декоративная** граница: стеклянная карточка, разделитель,
 *   пассивная подложка. Её не нужно искать глазом, чтобы начать взаимодействие,
 *   поэтому 3:1 к ней не применяется.
 * - [outline] - **смысловая** граница: всё, что надо опознать как элемент управления
 *   или как границу поля ввода. Здесь WCAG 1.4.11 требует 3:1, и обе схемы его дают
 *   (3.77/4.20 на фоне страницы).
 *
 * Проверки контраста живут в `desktopTest/.../ui/theme/Contrast.kt` и
 * `ThemeTest`/`ComponentsTest` - палитра и компоненты не должны разъезжаться снова.
 */
val AzraelPrimary = Color(0xFF4ADE80)
val AzraelOnPrimary = Color(0xFF07170C)
val AzraelSecondary = Color(0xFFEDEDED)
val AzraelDanger = Color(0xFFF43F5E)
val AzraelBgDeep = Color(0xFF0A0A0A)
val AzraelBgCard = Color(0x0DFFFFFF)
val AzraelBorder = Color(0x2EFFFFFF)
val AzraelTextDim = Color(0xD9EDEDED)

val AzraelWarning = Color(0xFFF59E0B)
val AzraelInfo = Color(0xFF38BDF8)
val AzraelSuccess = Color(0xFF22C55E)
val AzraelStrong = Color(0xFF16A34A)
val AzraelErrorSoft = Color(0xFFF87171)

val AzraelScorePoor = Color(0xFFF4585A)
val AzraelScoreFair = Color(0xFFF59E0B)
val AzraelScoreGood = Color(0xFF22C55E)
val AzraelScoreTop = Color(0xFF16A34A)

val AzraelRoleRf = Color(0xFFFFB74D)
val AzraelRolePremium = Color(0xFFE6C86A)

val AzraelDarkScheme: ColorScheme = darkColorScheme(
    primary = AzraelPrimary,
    onPrimary = AzraelOnPrimary,
    primaryContainer = Color(0xFF12351F),
    onPrimaryContainer = Color(0xFFBBF7D0),
    secondary = AzraelSecondary,
    onSecondary = AzraelOnPrimary,
    secondaryContainer = Color(0xFF1B1B1B),
    onSecondaryContainer = AzraelSecondary,
    tertiary = AzraelWarning,
    onTertiary = AzraelOnPrimary,
    background = AzraelBgDeep,
    onBackground = AzraelSecondary,
    surface = AzraelBgCard,
    onSurface = AzraelSecondary,
    surfaceVariant = Color(0x1AFFFFFF),
    onSurfaceVariant = AzraelTextDim,
    surfaceContainerLowest = Color(0xFF050505),
    surfaceContainerLow = Color(0xFF0D0D0D),
    surfaceContainer = Color(0xFF121212),
    surfaceContainerHigh = Color(0xFF171717),
    surfaceContainerHighest = Color(0xFF1E1E1E),
    inverseSurface = AzraelSecondary,
    inverseOnSurface = AzraelBgDeep,
    inversePrimary = AzraelStrong,
    error = AzraelDanger,
    onError = AzraelOnPrimary,
    errorContainer = Color(0xFF3B0D1C),
    onErrorContainer = Color(0xFFFECDD3),
    outline = Color(0x66FFFFFF),
    outlineVariant = AzraelBorder,
    scrim = Color(0xFF000000)
)

val AzraelLightScheme: ColorScheme = lightColorScheme(
    // `#15803D` не годился для светлой темы: он давал 4.14 на заливке поля в
    // фокусе и 3.77 на собственной 18%-заливке чипа, то есть акцент не мог
    // нести ни подпись поля, ни свою рамку. `#166534` проходит обе (5.88/5.18)
    // и по-прежнему даёт 7.13 на белом `onPrimary`.
    primary = Color(0xFF166534),
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFFDCFCE7),
    onPrimaryContainer = Color(0xFF14532D),
    secondary = Color(0xFF18181B),
    onSecondary = Color(0xFFFFFFFF),
    secondaryContainer = Color(0xFFF4F4F5),
    onSecondaryContainer = Color(0xFF18181B),
    tertiary = Color(0xFFB45309),
    onTertiary = Color(0xFFFFFFFF),
    background = Color(0xFFFAFAFA),
    onBackground = Color(0xFF0A0A0A),
    surface = Color(0xFFFFFFFF),
    onSurface = Color(0xFF0A0A0A),
    surfaceVariant = Color(0xFFF4F4F5),
    onSurfaceVariant = Color(0xFF52525B),
    surfaceContainerLowest = Color(0xFFFFFFFF),
    surfaceContainerLow = Color(0xFFF7F7F8),
    surfaceContainer = Color(0xFFF1F1F2),
    surfaceContainerHigh = Color(0xFFEBEBEC),
    surfaceContainerHighest = Color(0xFFE4E4E7),
    inverseSurface = Color(0xFF18181B),
    inverseOnSurface = Color(0xFFF4F4F5),
    inversePrimary = AzraelPrimary,
    error = Color(0xFFE11D48),
    onError = Color(0xFFFFFFFF),
    errorContainer = Color(0xFFFFE4E6),
    onErrorContainer = Color(0xFF881337),
    outline = Color(0xFF78787F),
    outlineVariant = Color(0xFFB4B4BC),
    scrim = Color(0xFF000000)
)

fun ColorScheme.isDark(): Boolean = background.luminanceIsDark()

private fun Color.luminanceIsDark(): Boolean =
    (0.299f * red + 0.587f * green + 0.114f * blue) < 0.5f
