package xyz.azraellab.zapp.ui.theme

import androidx.compose.material3.ColorScheme
import androidx.compose.material3.darkColorScheme
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

fun ColorScheme.isDark(): Boolean = background.luminanceIsDark()

private fun Color.luminanceIsDark(): Boolean =
    (0.299f * red + 0.587f * green + 0.114f * blue) < 0.5f
