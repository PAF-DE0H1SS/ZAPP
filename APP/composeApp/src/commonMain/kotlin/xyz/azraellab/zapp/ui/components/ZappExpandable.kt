package xyz.azraellab.zapp.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import xyz.azraellab.zapp.ui.glass
import xyz.azraellab.zapp.ui.theme.AzraelCornerGlass
import xyz.azraellab.zapp.ui.theme.AzraelSpace

/**
 * Свёрнутая по умолчанию карточка: заголовок с бейджем и кнопками справа,
 * содержимое раскрывается вниз с анимацией.
 *
 * Главные экраны держат только то, чем пользуются каждый день, а всё
 * остальное -- здесь: на виду (заголовок с числом), но не занимает места,
 * пока не открыли. Один компонент вместо семи копипаст с `AnimatedVisibility`.
 */
@Composable
fun ZappExpandableCard(
    title: String,
    modifier: Modifier = Modifier,
    badge: String? = null,
    subtitle: String? = null,
    initiallyExpanded: Boolean = false,
    actions: @Composable RowScope.() -> Unit = {},
    content: @Composable ColumnScope.() -> Unit
) {
    var expanded by rememberSaveable { mutableStateOf(initiallyExpanded) }
    val chevron by animateFloatAsState(
        targetValue = if (expanded) 180f else 0f,
        animationSpec = tween(220),
        label = "expand-chevron"
    )

    Column(
        modifier = modifier.fillMaxWidth().glass(corner = AzraelCornerGlass)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable { expanded = !expanded }
                .padding(AzraelSpace.cardPadding),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(AzraelSpace.md)
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(AzraelSpace.sm)
                ) {
                    Text(
                        text = title,
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.weight(1f, fill = false),
                        maxLines = 2
                    )
                    if (badge != null) {
                        Text(
                            text = badge,
                            style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.primary
                        )
                    }
                }
                if (subtitle != null) {
                    Text(
                        text = subtitle,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            actions()
            Icon(
                imageVector = Icons.Filled.KeyboardArrowDown,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.rotate(chevron)
            )
        }

        AnimatedVisibility(
            visible = expanded,
            enter = expandVertically(tween(220)) + fadeIn(tween(180)),
            exit = shrinkVertically(tween(180)) + fadeOut(tween(120))
        ) {
            Column(
                modifier = Modifier.padding(
                    horizontal = AzraelSpace.cardPadding,
                    vertical = AzraelSpace.sm
                ),
                verticalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(AzraelSpace.xs)
            ) {
                content()
            }
        }
    }
}
