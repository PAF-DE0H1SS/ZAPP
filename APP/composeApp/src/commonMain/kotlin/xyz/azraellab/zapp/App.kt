package xyz.azraellab.zapp

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import xyz.azraellab.zapp.core.AppState
import xyz.azraellab.zapp.ui.AppThemeRoot
import xyz.azraellab.zapp.ui.GlassBackground
import xyz.azraellab.zapp.ui.components.ZappSkeleton
import xyz.azraellab.zapp.ui.components.ZappSkeletonList
import xyz.azraellab.zapp.ui.nav.AppRoot
import xyz.azraellab.zapp.ui.theme.AzraelSpace
import xyz.azraellab.zapp.ui.welcome.WelcomeScreen

/**
 * Точка входа приложения.
 *
 * Переведена с демонстрационной витрины на минимальный рабочий корневой экран.
 * Темная тема форсируется через `AppThemeRoot`, навигация -- собственная.
 */
@Composable
fun App() {
    // Синглтон процесса: Activity может пересоздаться (смена иконки,
    // поворот), а состояние -- нет. Повторный AppState означал бы второй
    // туннель и драку демонов за порт.
    val state = remember { AppState.obtain() }
    val booted by state.booted.collectAsState()

    LaunchedEffect(Unit) {
        state.load()
    }

    AppThemeRoot {
        GlassBackground(perf = state.config.perf.sanitized()) {
            // До чтения конфига -- скелетон, а не «пусто»: первый кадр
            // стартует раньше load(), и без заглушки страницы успевали
            // показать «нет коннектов» как настоящий ответ (UX2).
            if (!booted) {
                BootSkeleton()
                return@GlassBackground
            }
            // Первый запуск: приветствие с туров и разрешениями.
            if (state.config.welcomeDone) {
                AppRoot(state = state)
            } else {
                WelcomeScreen(state = state)
            }
        }
    }
}

/**
 * Заглушка первых кадров: каркас карточек с пульсирующими полосками.
 *
 * Живёт доли секунды (чтение config.json), поэтому анимация скелетона
 * не успевает накопить расход -- но и мигания «нет данных» не бывает.
 */
@Composable
private fun BootSkeleton() {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(AzraelSpace.screenPadding),
        verticalArrangement = Arrangement.spacedBy(AzraelSpace.cardGap)
    ) {
        ZappSkeleton(width = 180.dp, height = 24.dp)
        ZappSkeletonList(rows = 3)
        ZappSkeleton(width = 240.dp, height = 14.dp)
        ZappSkeletonList(rows = 2)
    }
}
