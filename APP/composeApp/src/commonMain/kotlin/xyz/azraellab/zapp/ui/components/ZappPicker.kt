package xyz.azraellab.zapp.ui.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
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
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import xyz.azraellab.zapp.core.Str
import xyz.azraellab.zapp.ui.components.ComponentColors.ButtonRole
import xyz.azraellab.zapp.ui.theme.AppIcons
import xyz.azraellab.zapp.ui.theme.AzraelSpace
import xyz.azraellab.zapp.ui.tr

/**
 * Выбор из длинного списка: строка с текущим значением, список -- в диалоге.
 *
 * Чипы не годятся там, где вариантов сотни: 935 коннектов в инлайновом
 * `FlowRow` считаются и рисуются целиком, и kill switch в редакторе
 * профиля уезжает на ~15 000 px вниз -- свайпом до него не добраться (B6).
 *
 * Здесь два спасения сразу: на экран попадает одна строка, а внутри
 * диалога список ленивый (компонент видит только видимые позиции) и
 * с поиском, иначе нужный коннект между тысячами находится глазами.
 * Выбор закрывает диалог: случайное нажатие не оставляет его открытым.
 */
@Composable
fun <T> ZappPickerRow(
    label: String,
    options: List<Choice<T>>,
    selected: T,
    onSelect: (T) -> Unit,
    modifier: Modifier = Modifier,
    path: String? = null,
    enabled: Boolean = true
) {
    var open by rememberSaveable { mutableStateOf(false) }
    val selectedLabel = options.firstOrNull { it.value == selected }?.label ?: "—"

    Row(
        modifier = modifier
            .fillMaxWidth()
            .alpha(if (enabled) 1f else 0.5f)
            .clickable(enabled = enabled) { open = true }
            .debugPath(path ?: "picker:$label")
            .padding(vertical = AzraelSpace.xs),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(AzraelSpace.md)
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
        Text(
            text = selectedLabel,
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.weight(1f),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
        Icon(
            imageVector = AppIcons.Chevron,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }

    if (open) {
        var query by rememberSaveable { mutableStateOf("") }
        val needle = query.trim()
        val filtered = if (needle.isEmpty()) options
        else options.filter { it.label.contains(needle, ignoreCase = true) }

        AlertDialog(
            onDismissRequest = { open = false },
            modifier = Modifier.debugPath(path?.let { "$it.dialog" } ?: "picker:$label.dialog"),
            title = {
                Text(
                    text = label,
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface
                )
            },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(AzraelSpace.sm)) {
                    ZappTextField(
                        label = tr(Str.COMMON_SEARCH),
                        value = query,
                        onValueChange = { query = it },
                        path = path?.let { "$it.search" }
                    )
                    if (filtered.isEmpty()) {
                        // Пустой результат -- с картинкой и подсказкой,
                        // а не одна строка (UX2).
                        ZappEmptyCard(
                            title = tr(Str.COMMON_NOTHING_FOUND),
                            body = tr(Str.PICKER_SEARCH_HINT)
                        )
                    } else {
                        LazyColumn(
                            modifier = Modifier
                                .fillMaxWidth()
                                .heightIn(max = 420.dp),
                            verticalArrangement = Arrangement.spacedBy(AzraelSpace.xs)
                        ) {
                            items(filtered) { option ->
                                val isSelected = option.value == selected
                                Text(
                                    text = option.label,
                                    style = MaterialTheme.typography.bodyLarge,
                                    color = if (isSelected) MaterialTheme.colorScheme.primary
                                    else MaterialTheme.colorScheme.onSurface,
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clickable {
                                            onSelect(option.value)
                                            open = false
                                        }
                                        .padding(vertical = AzraelSpace.xs)
                                        .debugPath(
                                            path?.let { "$it.option.${option.label}" }
                                                ?: "picker:$label.${option.label}"
                                        ),
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                        }
                    }
                }
            },
            confirmButton = {
                ZappButton(
                    text = tr(Str.COMMON_CLOSE),
                    onClick = { open = false },
                    role = ButtonRole.GHOST,
                    path = path?.let { "$it.close" }
                )
            }
        )
    }
}
