package xyz.azraellab.zapp.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import xyz.azraellab.zapp.core.AppThemeStore

enum class AppThemeMode(val code: String) {
    SYSTEM("system"),
    LIGHT("light"),
    DARK("dark");

    companion object {
        fun of(code: String?): AppThemeMode =
            entries.firstOrNull { it.code == code?.trim()?.lowercase() } ?: SYSTEM
    }
}

/** Разрешение режима в «тёмное/светлое» без доступа к системе - тестируемо и не @Composable. */
fun resolveDark(mode: AppThemeMode, systemDark: Boolean): Boolean = when (mode) {
    AppThemeMode.LIGHT -> false
    AppThemeMode.DARK -> true
    AppThemeMode.SYSTEM -> systemDark
}

object AzraelThemeState {
    var mode: AppThemeMode by mutableStateOf(AppThemeMode.DARK)
        private set

    /**
     * Последнее прочитанное системное значение темы. `isSystemInDarkTheme()` доступна
     * только в композиции, поэтому результат кладём сюда: обработчики кнопок (в том
     * числе [toggle]) - обычные функции и не могут звать composable.
     */
    private var systemDark: Boolean by mutableStateOf(false)

    private var loaded: Boolean = false

    fun load() {
        if (loaded) return
        loaded = true
        mode = AppThemeMode.of(AppThemeStore.read())
    }

    fun set(next: AppThemeMode) {
        mode = next
        loaded = true
        AppThemeStore.write(next.code)
    }

    fun isDark(): Boolean = resolveDark(mode, systemDark)

    /** Переключатель «тёмная ⇄ светлая»: из SYSTEM уходит в явный противоположный режим. */
    fun toggle() {
        set(if (isDark()) AppThemeMode.LIGHT else AppThemeMode.DARK)
    }

    internal fun onSystemDark(value: Boolean) {
        systemDark = value
    }
}

@Composable
fun AzraelTheme(
    mode: AppThemeMode = AzraelThemeState.mode,
    content: @Composable () -> Unit
) {
    val dark = when (mode) {
        AppThemeMode.LIGHT -> false
        AppThemeMode.DARK -> true
        AppThemeMode.SYSTEM -> isSystemInDarkTheme()
    }
    SideEffect { AzraelThemeState.onSystemDark(dark) }
    MaterialTheme(
        colorScheme = if (dark) AzraelDarkScheme else AzraelLightScheme,
        typography = AzraelTypography,
        shapes = AzraelShapes
    ) {
        AzraelIndicationTheme(content)
    }
}
