package xyz.azraellab.zapp.ui.nav

import androidx.activity.compose.BackHandler
import androidx.compose.runtime.Composable

@Composable
actual fun ZappBackHandler(enabled: Boolean, onBack: () -> Unit) {
    BackHandler(enabled = enabled, onBack = onBack)
}
