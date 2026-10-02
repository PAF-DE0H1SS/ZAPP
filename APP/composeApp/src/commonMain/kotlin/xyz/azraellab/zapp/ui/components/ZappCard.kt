package xyz.azraellab.zapp.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import xyz.azraellab.zapp.ui.glass
import xyz.azraellab.zapp.ui.theme.AzraelCornerGlass
import xyz.azraellab.zapp.ui.theme.AzraelSpace

/**
 * Карточка -- основная поверхность интерфейса: список конфигов, блок настроек,
 * панель результата проверки.
 *
 * Отступы, скругление и заливка берутся из темы, поэтому карточка на светлой и
 * тёмной схеме выглядит одинаково осмысленно, а не «стекло нашлось только в
 * тёмной».
 */
@Composable
fun ZappCard(
    modifier: Modifier = Modifier,
    contentPadding: PaddingValues = PaddingValues(AzraelSpace.cardPadding),
    content: @Composable ColumnScope.() -> Unit
) {
    Column(
        modifier = modifier.fillMaxWidth().glass(corner = AzraelCornerGlass),
        verticalArrangement = Arrangement.spacedBy(AzraelSpace.xs)
    ) {
        Column(modifier = Modifier.padding(contentPadding)) {
            content()
        }
    }
}

/** Заголовок секции: приглушённый, чтобы не спорить с содержимым под ним. */
@Composable
fun ZappSectionTitle(text: String, modifier: Modifier = Modifier) {
    Text(
        text = text,
        style = MaterialTheme.typography.titleSmall,
        color = ComponentColors.sectionTitle(MaterialTheme.colorScheme),
        modifier = modifier.padding(bottom = AzraelSpace.sm)
    )
}

/**
 * Строка списка: заголовок, необязательная подпись и хвост справа.
 *
 * Заголовок и подпись разного веса намеренно: подпись приглушена до
 * `onSurfaceVariant`, и это 4.5:1 проверено тестом, а не подобрано глазом.
 */
@Composable
fun ZappListRow(
    title: String,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    trailing: @Composable (() -> Unit)? = null
) {
    Row(
        modifier = modifier.fillMaxWidth().padding(vertical = AzraelSpace.sm),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(AzraelSpace.md)
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(text = title, style = MaterialTheme.typography.bodyLarge)
            if (subtitle != null) {
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
        trailing?.invoke()
    }
}
