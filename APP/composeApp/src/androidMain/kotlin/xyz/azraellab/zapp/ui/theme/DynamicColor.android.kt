package xyz.azraellab.zapp.ui.theme

import androidx.compose.material3.ColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import xyz.azraellab.zapp.core.AndroidCtx

/**
 * Android 12+ (minSdk у проекта 33): Material You -- цвета из текущих обоев.
 *
 * Контекст берётся из [AndroidCtx]: до attach() динамики нет, возвращаем null,
 * и остаётся схема темы. Ошибки (нет обоев, системный сервис недоступен)
 * гасятся тем же runCatching.
 */
actual fun dynamicColorScheme(isDark: Boolean): ColorScheme? {
    val context = AndroidCtx.current ?: return null
    return runCatching {
        if (isDark) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
    }.getOrNull()
}
