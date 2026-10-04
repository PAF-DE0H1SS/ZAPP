package xyz.azraellab.zapp.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import xyz.azraellab.zapp.ui.theme.AzraelSpace

/**
 * Текстовое поле с подписью.
 *
 * Подпись ставится сверху, а не вместо плейсхолдера: пустое поле без подписи
 * невозможно понять, особенно в настройках, где рядом десяток похожих строк.
 * Плейсхолдер при этом остаётся и показывает формат.
 */
@Composable
fun ZappTextField(
    label: String,
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    placeholder: String? = null,
    singleLine: Boolean = true,
    minLines: Int = 1,
    supportingText: String? = null
) {
    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(AzraelSpace.xs)
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        OutlinedTextField(
            value = value,
            onValueChange = onValueChange,
            placeholder = placeholder?.let { { Text(it) } },
            singleLine = singleLine,
            minLines = minLines,
            supportingText = supportingText?.let { { Text(it) } },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Text),
            shape = MaterialTheme.shapes.small,
            modifier = Modifier.fillMaxWidth()
        )
    }
}

/**
 * Числовое поле.
 *
 * Значение хранится как `Int?`, потому что у большинства флагов zapret есть
 * «не задано» и «задано нулём» -- это разные вещи, и пустая строка должна
 * означать отсутствие флага, а не ноль.
 *
 * При вводе нечислового символа поле молча его отбрасывает, а не показывает
 * ошибку: пользователь набирает цифры с телефона, и красная рамка на каждый
 * символ только раздражает.
 */
@Composable
fun ZappNumberField(
    label: String,
    value: Int?,
    onValueChange: (Int?) -> Unit,
    modifier: Modifier = Modifier,
    placeholder: String? = null,
    supportingText: String? = null
) {
    val text = value?.toString() ?: ""

    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(AzraelSpace.xs)
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        OutlinedTextField(
            value = text,
            onValueChange = { raw ->
                val filtered = raw.filter { it.isDigit() || (it == '-' && raw.startsWith("-")) }
                onValueChange(filtered.toIntOrNull())
            },
            placeholder = placeholder?.let { { Text(it) } },
            singleLine = true,
            supportingText = supportingText?.let { { Text(it) } },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            shape = MaterialTheme.shapes.small,
            modifier = Modifier.fillMaxWidth()
        )
    }
}

/** Вариант выбора со своим кодом и подписью. */
data class Choice<T>(
    val value: T,
    val label: String
)

/**
 * Выбор одного варианта из списка чипами.
 *
 * Чипы, а не выпадающий список: вариантов мало, они должны быть видны сразу,
 * а список ради одного выбора открывать лишний раз -- лишнее действие.
 * [FlowRow] нужен, потому что вариантов бывает больше, чем влезет в строку,
 * и обрезать их нельзя.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun <T> ZappChoiceRow(
    label: String,
    options: List<Choice<T>>,
    selected: T,
    onSelect: (T) -> Unit,
    modifier: Modifier = Modifier
) {
    if (options.isEmpty()) return

    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(AzraelSpace.xs)
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(AzraelSpace.sm),
            verticalArrangement = Arrangement.spacedBy(AzraelSpace.xs)
        ) {
            options.forEach { option ->
                val isSelected = option.value == selected
                FilterChip(
                    selected = isSelected,
                    onClick = { onSelect(option.value) },
                    // Чип -- кнопка: одна строка без переноса. В FlowRow
                    // ширина у чипа естественная, поэтому текст влезает,
                    // и перенос здесь не понадобился бы даже теоретически.
                    label = { Text(option.label, maxLines = 1, softWrap = false) },
                    colors = FilterChipDefaults.filterChipColors(
                        selectedContainerColor = MaterialTheme.colorScheme.primary,
                        selectedLabelColor = MaterialTheme.colorScheme.onPrimary
                    ),
                    modifier = Modifier.padding(vertical = AzraelSpace.xxs)
                )
            }
        }
    }
}
