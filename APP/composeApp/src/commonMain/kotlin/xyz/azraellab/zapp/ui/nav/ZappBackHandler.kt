package xyz.azraellab.zapp.ui.nav

import androidx.compose.runtime.Composable

/**
 * Кнопка «назад» платформы, привязанная к конкретному окну.
 *
 * На Android это системная кнопка/жест: открытое подокно настроек
 * закрывается, а не сворачивает всё приложение. На десктопе кнопки нет,
 * поэтому там заглушка -- общий код вызывает единообразно и не знает
 * разницы.
 */
@Composable
expect fun ZappBackHandler(enabled: Boolean, onBack: () -> Unit)
