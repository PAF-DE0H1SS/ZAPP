package xyz.azraellab.zapp.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import xyz.azraellab.zapp.ui.components.ComponentColors.ButtonRole
import xyz.azraellab.zapp.ui.theme.AzraelCornerMd
import xyz.azraellab.zapp.ui.theme.AzraelSpace

/**
 * Кнопка приложения.
 *
 * Высота -- [AzraelSpace.controlHeight] (44dp), а не `controlHeightSm` (36dp):
 * 36dp проходит по WCAG 2.5.8, но это буквально минимум, и на телефоне промах
 * по кнопке частый. Уменьшенная высота остаётся для чипов и переключателей,
 * где промах не стоит ничего.
 *
 * Цвета берутся из [ComponentColors.button], а не из `ButtonDefaults`: дефолт
 * Material не знает про `AzraelDanger` и про разницу тёмной и светлой схемы,
 * поэтому «опасная» кнопка на светлой теме получила бы чёрный текст на
 * светло-розовом и потеряла читаемость.
 */
@Composable
fun ZappButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    role: ButtonRole = ButtonRole.PRIMARY,
    enabled: Boolean = true
) {
    val (container, content) = ComponentColors.button(MaterialTheme.colorScheme, role)
    val shape = RoundedCornerShape(AzraelCornerMd)
    val contentColor = if (enabled) content
    else ComponentColors.disabledText(MaterialTheme.colorScheme)

    // Ghost рисуется контуром, а не заливкой: ему не нужна площадь, иначе
    // вторичное действие начинает спорить с основным.
    if (role == ButtonRole.GHOST) {
        OutlinedButton(
            onClick = onClick,
            enabled = enabled,
            shape = shape,
            border = ButtonDefaults.outlinedButtonBorder(enabled).copy(
                width = AzraelSpace.stroke
            ),
            colors = ButtonDefaults.outlinedButtonColors(
                contentColor = contentColor,
                containerColor = container
            ),
            modifier = modifier.defaultMinSize(minHeight = AzraelSpace.controlHeight)
        ) {
            Text(text = text, style = MaterialTheme.typography.labelLarge)
        }
        return
    }

    Button(
        onClick = onClick,
        enabled = enabled,
        shape = shape,
        colors = ButtonDefaults.buttonColors(
            containerColor = container,
            contentColor = contentColor,
            disabledContainerColor = container.copy(alpha = 0.38f),
            disabledContentColor = ComponentColors.disabledText(MaterialTheme.colorScheme)
        ),
        contentPadding = PaddingValues(
            horizontal = AzraelSpace.xl,
            vertical = AzraelSpace.md
        ),
        modifier = modifier.defaultMinSize(minHeight = AzraelSpace.controlHeight)
    ) {
        Text(text = text, style = MaterialTheme.typography.labelLarge)
    }
}

/**
 * Ряд кнопок: кнопки делят ширину, зазор -- [AzraelSpace.cardGap].
 *
 * Две кнопки рядом и с одинаковым весом выглядят как равноправные альтернативы,
 * а «Отмена» и «Удалить» равноправны быть не должны, поэтому вторичное
 * действие отдаётся отдельной ролью, а не только раскраской.
 */
@Composable
fun ZappButtonRow(
    modifier: Modifier = Modifier,
    content: @Composable RowScope.() -> Unit
) {
    Row(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(AzraelSpace.cardGap)
    ) {
        content()
    }
}
