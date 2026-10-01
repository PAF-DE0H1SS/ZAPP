package xyz.azraellab.zapp.ui.theme

import androidx.compose.foundation.Indication
import androidx.compose.foundation.LocalIndication
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ripple
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember

@Composable
fun rememberAzraelIndication(bounded: Boolean = true): Indication {
    val color = MaterialTheme.colorScheme.onSurface
    return remember(color, bounded) { ripple(bounded = bounded, color = color) }
}

@Composable
fun AzraelIndicationTheme(content: @Composable () -> Unit) {
    CompositionLocalProvider(
        LocalIndication provides rememberAzraelIndication(),
        content = content
    )
}
