package xyz.azraellab.zapp.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import xyz.azraellab.zapp.ui.components.ComponentColors.ButtonRole
import xyz.azraellab.zapp.ui.theme.AzraelSpace

/**
 * Подтверждение необратимого действия.
 *
 * Отдельный компонент, а не `if (clicked) Text(...)`: сброс пресета
 * стирает профили, домены и статистику одним тапом, и без перечисления
 * того, что именно исчезнет, пользователь узнаёт об этом слишком поздно.
 *
 * [bullets] -- список того, что будет затронуто; каждая строка уже
 * переведена у вызывающего. Кнопка отмены -- GHOST: она не должна
 * спорить с основным действием, но обязана быть на виду.
 */
@Composable
fun ZappDialog(
    title: String,
    confirmText: String,
    cancelText: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
    body: String? = null,
    bullets: List<String> = emptyList(),
    danger: Boolean = false,
    path: String? = null
) {
    val scheme = MaterialTheme.colorScheme
    AlertDialog(
        onDismissRequest = onDismiss,
        modifier = modifier.debugPath(path ?: "dialog:$title"),
        title = {
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium,
                color = scheme.onSurface
            )
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(AzraelSpace.sm)) {
                if (body != null) {
                    Text(
                        text = body,
                        style = MaterialTheme.typography.bodyMedium,
                        color = scheme.onSurfaceVariant
                    )
                }
                bullets.forEach { line ->
                    Row(horizontalArrangement = Arrangement.spacedBy(AzraelSpace.sm)) {
                        Text(
                            text = "•",
                            style = MaterialTheme.typography.bodyMedium,
                            color = scheme.onSurfaceVariant
                        )
                        Text(
                            text = line,
                            style = MaterialTheme.typography.bodyMedium,
                            color = scheme.onSurface
                        )
                    }
                }
            }
        },
        confirmButton = {
            ZappButton(
                text = confirmText,
                onClick = onConfirm,
                role = if (danger) ButtonRole.DANGER else ButtonRole.PRIMARY,
                path = path?.let { "$it.confirm" }
            )
        },
        dismissButton = {
            ZappButton(
                text = cancelText,
                onClick = onDismiss,
                role = ButtonRole.GHOST,
                path = path?.let { "$it.cancel" }
            )
        }
    )
}
