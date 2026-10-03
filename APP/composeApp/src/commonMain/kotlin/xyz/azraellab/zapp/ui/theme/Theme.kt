package xyz.azraellab.zapp.ui.theme

import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider

/**
 * Единственная тема приложения: тёмная.
 *
 * Выбора режима нет и не будет. Светлой схемы тоже нет: тёмный фон с
 * полупрозрачными панелями и звёздным полем не имеет осмысленного светлого
 * варианта, а его «светлая» версия выглядела как тёмное приложение с чёрным
 * текстом. Держать переключатель, который ведёт в заведомо сломанное
 * состояние, хуже, чем его не иметь.
 *
 * Отсюда и поведение: [AzraelTheme] не принимает режима и всегда кладёт
 * [AzraelDarkScheme], а хранить «тёмная/светлая» между запусками незачем --
 * сохранять нечего.
 */
@Composable
fun AzraelTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = AzraelDarkScheme,
        typography = AzraelTypography,
        shapes = AzraelShapes
    ) {
        // Цвет текста по умолчанию. Без этой строки в иерархии действует
        // дефолт foundation -- чёрный, потому что нигде нет Surface: заголовки
        // строк настроек и другие Text без явного цвета рисовались чёрным
        // по тёмной карточке и были невидимы. MaterialTheme LocalContentColor
        // не задаёт, поэтому провайдер ставится здесь, один раз на всё приложение.
        CompositionLocalProvider(LocalContentColor provides AzraelDarkScheme.onSurface) {
            AzraelIndicationTheme(content)
        }
    }
}
