package xyz.azraellab.zapp.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import xyz.azraellab.zapp.ui.theme.AzraelCornerMd
import xyz.azraellab.zapp.ui.theme.AzraelSpace

/**
 * Информационный блок: заголовок и текст.
 *
 * Подложка [ComponentColors.bannerFill] -- слабый тон `onSurface`, а не цвет
 * состояния: баннер должен читаться как зона, а не как плашка.
 */
@Composable
fun ZappBanner(
    title: String,
    modifier: Modifier = Modifier,
    state: ComponentColors.State = ComponentColors.State.IDLE,
    body: String? = null,
    trailing: @Composable (ColumnScope.() -> Unit)? = null
) {
    val scheme = MaterialTheme.colorScheme
    val shape = RoundedCornerShape(AzraelCornerMd)

    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(shape)
            .background(ComponentColors.bannerFill(scheme))
            .border(AzraelSpace.stroke, scheme.outlineVariant, shape)
            .padding(AzraelSpace.cardPadding),
        verticalArrangement = Arrangement.spacedBy(AzraelSpace.sm)
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.titleSmall,
            color = ComponentColors.stateText(scheme, state)
        )
        if (body != null) {
            Text(
                text = body,
                style = MaterialTheme.typography.bodyMedium,
                color = scheme.onSurfaceVariant
            )
        }
        trailing?.invoke(this)
    }
}

/**
 * Переключатель настроек: заголовок, пояснение и свитч.
 *
 * Высота строки -- [AzraelSpace.touchTarget] (48dp), а не высота свитча: цель
 * касания должна быть не меньше 48dp по WCAG 2.5.8, иначе по строке мимо
 * промахиваются.
 */
@Composable
fun ZappSettingRow(
    title: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    subtitle: String? = null
) {
    val scheme = MaterialTheme.colorScheme
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(vertical = AzraelSpace.sm),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(AzraelSpace.md)
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(text = title, style = MaterialTheme.typography.bodyLarge)
            if (subtitle != null) {
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = scheme.onSurfaceVariant
                )
            }
        }
        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange,
            colors = SwitchDefaults.colors(
                checkedThumbColor = scheme.onPrimary,
                checkedTrackColor = scheme.primary,
                checkedBorderColor = scheme.primary,
                uncheckedThumbColor = scheme.outline,
                uncheckedTrackColor = scheme.surfaceContainerHigh,
                uncheckedBorderColor = scheme.outline
            ),
            modifier = Modifier.padding(vertical = AzraelSpace.xs)
        )
    }
}

/**
 * Пустое состояние: заголовок, пояснение и место под действие.
 *
 * Отдельный компонент, а не пустой `Column`, потому что «ничего не нашлось» и
 * «ещё не проверяли» -- разные состояния с разными подписями, и экран должен
 * уметь показать оба.
 */
@Composable
fun ZappEmptyState(
    title: String,
    modifier: Modifier = Modifier,
    body: String? = null,
    action: @Composable (() -> Unit)? = null
) {
    val scheme = MaterialTheme.colorScheme
    Column(
        modifier = modifier.fillMaxWidth().padding(vertical = AzraelSpace.xl),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(AzraelSpace.sm)
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.titleMedium,
            color = scheme.onSurface
        )
        if (body != null) {
            Text(
                text = body,
                style = MaterialTheme.typography.bodyMedium,
                color = scheme.onSurfaceVariant
            )
        }
        if (action != null) {
            Box(modifier = Modifier.padding(top = AzraelSpace.sm)) { action() }
        }
    }
}
