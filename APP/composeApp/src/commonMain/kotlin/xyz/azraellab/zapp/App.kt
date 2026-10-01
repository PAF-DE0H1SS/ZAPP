package xyz.azraellab.zapp

import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import xyz.azraellab.zapp.ui.AppThemeRoot
import xyz.azraellab.zapp.ui.GlassBackground

/**
 * Точка входа UI. Пока это заглушка с проверкой, что тема и фон рисуются:
 * P0 закрывает каркас и дизайн-систему, функциональные экраны появятся в P4.
 */
@Composable
fun App() {
    AppThemeRoot {
        GlassBackground {
            Text("ZAPP")
        }
    }
}