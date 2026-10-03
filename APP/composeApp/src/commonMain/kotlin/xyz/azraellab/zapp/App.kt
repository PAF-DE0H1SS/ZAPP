package xyz.azraellab.zapp

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import xyz.azraellab.zapp.core.AppState
import xyz.azraellab.zapp.ui.AppThemeRoot
import xyz.azraellab.zapp.ui.GlassBackground
import xyz.azraellab.zapp.ui.nav.AppRoot
import xyz.azraellab.zapp.ui.welcome.WelcomeScreen

/**
 * Точка входа приложения.
 *
 * Переведена с демонстрационной витрины на минимальный рабочий корневой экран.
 * Темная тема форсируется через `AppThemeRoot`, навигация -- собственная.
 */
@Composable
fun App() {
    val state = remember { AppState() }

    LaunchedEffect(Unit) {
        state.load()
    }

    AppThemeRoot {
        GlassBackground {
            // Первый запуск: приветствие с туров и разрешениями.
            if (state.config.welcomeDone) {
                AppRoot(state = state)
            } else {
                WelcomeScreen(state = state)
            }
        }
    }
}
