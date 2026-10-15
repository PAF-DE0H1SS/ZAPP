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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
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
    supportingText: String? = null,
    path: String? = null
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
            modifier = Modifier
                .fillMaxWidth()
                .debugPath(path ?: "input:$label")
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
    supportingText: String? = null,
    path: String? = null
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
            modifier = Modifier
                .fillMaxWidth()
                .debugPath(path ?: "input:$label")
        )
    }
}

/**
 * Поле для дробного числа.
 *
 * Целочисленного [ZappNumberField] координатам не хватает: широта и долгота
 * живут с дробной частью, а фильтр цифр точку отбрасывал молча -- в поле
 * уходило `557558` вместо `55.7558`, значение уезжало за пределы диапазона
 * и подмена GPS отказывалась стартовать. Здесь разрешены одна точка и
 * минус в начале.
 *
 * Текст живёт отдельно от значения: иначе наборная `55.` превратилась бы в
 * `55` при следующей перерисовке, потому что значение уже число 55.0.
 */
@Composable
fun ZappDecimalField(
    label: String,
    value: Double?,
    onValueChange: (Double?) -> Unit,
    modifier: Modifier = Modifier,
    placeholder: String? = null,
    supportingText: String? = null,
    path: String? = null
) {
    var text by rememberSaveable { mutableStateOf(value?.let { decimalText(it) } ?: "") }

    LaunchedEffect(value) {
        // Тянется только чужое изменение: свой ввод значение уже догнало.
        if (text.toDoubleOrNull() != value) {
            text = value?.let { decimalText(it) } ?: ""
        }
    }

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
                val filtered = buildString {
                    var dotted = false
                    raw.forEachIndexed { index, ch ->
                        when {
                            ch.isDigit() -> append(ch)
                            ch == '-' && index == 0 -> append(ch)
                            ch == '.' && !dotted -> {
                                append(ch)
                                dotted = true
                            }
                        }
                    }
                }
                text = filtered
                onValueChange(filtered.toDoubleOrNull())
            },
            placeholder = placeholder?.let { { Text(it) } },
            singleLine = true,
            supportingText = supportingText?.let { { Text(it) } },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
            shape = MaterialTheme.shapes.small,
            modifier = Modifier
                .fillMaxWidth()
                .debugPath(path ?: "input:$label")
        )
    }
}

/** Целое без хвоста `.0`, дробное -- как есть: `0`, `55.7558`. */
private fun decimalText(value: Double): String =
    if (value == value.toLong().toDouble()) value.toLong().toString() else value.toString()
data class Choice<T>(
    val value: T,
    val label: String
)

/**
 * Сколько вариантов ещё рисуются чипами.
 *
 * Порог -- про «вариантов мало, они должны быть видны сразу» из
 * документации компонента: протоколы, транспорты и прочие короткие
 * наборы остаются на виду, всё длиннее уходит в ленивый диалог.
 */
private const val CHIPS_MAX = 12

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
    modifier: Modifier = Modifier,
    path: String? = null,
    enabled: Boolean = true
) {
    if (options.isEmpty()) return

    // Длинный список чипами не рисуем: 935 коннектов в инлайновом FlowRow
    // считаются целиком и толкают остальной экран на тысячи пикселей вниз,
    // пока kill switch остаётся за пределами короткого свайпа (B6). До
    // порога -- привычные чипы, дальше -- свёрнутая строка с ленивым
    // списком и поиском.
    if (options.size > CHIPS_MAX) {
        ZappPickerRow(
            label = label,
            options = options,
            selected = selected,
            onSelect = onSelect,
            modifier = modifier,
            path = path,
            enabled = enabled
        )
        return
    }

    Column(
        modifier = modifier.fillMaxWidth().alpha(if (enabled) 1f else 0.5f),
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
                    onClick = { if (enabled) onSelect(option.value) },
                    label = { Text(option.label, maxLines = 1, softWrap = false) },
                    colors = FilterChipDefaults.filterChipColors(
                        selectedContainerColor = MaterialTheme.colorScheme.primary,
                        selectedLabelColor = MaterialTheme.colorScheme.onPrimary
                    ),
                    modifier = Modifier
                        .padding(vertical = AzraelSpace.xxs)
                        .debugPath("${path ?: "choice:$label"}.${option.label}")
                )
            }
        }
    }
}
