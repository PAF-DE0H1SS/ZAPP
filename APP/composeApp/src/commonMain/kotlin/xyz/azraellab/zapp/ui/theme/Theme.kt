package xyz.azraellab.zapp.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable

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
        AzraelIndicationTheme(content)
    }
}
