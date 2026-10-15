package xyz.azraellab.zapp.ui.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import xyz.azraellab.zapp.core.RegionRegistry
import xyz.azraellab.zapp.core.Str
import xyz.azraellab.zapp.ui.AppLangState
import xyz.azraellab.zapp.ui.tr

/**
 * Выпадающий список стран.
 *
 * Чипов для __всех__ стран не сделаешь (их почти двести), поэтому страна --
 * единственное поле настроек в виде dropdown. Открывается по тапу на строку,
 * закрывается выбором или тапом мимо. Выбранная страна подсвечена галочкой.
 */
@Composable
fun RegionDropdown(
    selected: String,
    onSelect: (String) -> Unit,
    modifier: Modifier = Modifier,
    path: String = "region"
) {
    var expanded by remember { mutableStateOf(false) }
    val lang = AppLangState.current

    Box(modifier = modifier.fillMaxWidth()) {
        ZappListRow(
            title = if (selected.isBlank()) tr(Str.WELCOME_REGION_ANYWHERE)
            else RegionRegistry.localized(selected, lang),
            trailing = {
                Text(
                    text = if (expanded) "▴" else "▾",
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.primary
                )
            },
            modifier = Modifier
                .debugPath(path)
                .clickable { expanded = true }
        )
        DropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false }
        ) {
            // Очистка выбора (B7): после того как страна поставлена,
            // пути обратно к «везде» не было вовсе. Первым пунктом и
            // с тем же текстом, что и строка в пустом состоянии, --
            // набор сходится, а не приходится угадывать.
            DropdownMenuItem(
                text = { Text(tr(Str.WELCOME_REGION_ANYWHERE)) },
                trailingIcon = {
                    if (selected.isBlank()) {
                        Text(
                            text = "\u2713",
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.primary
                        )
                    }
                },
                onClick = {
                    expanded = false
                    if (selected.isNotBlank()) onSelect("")
                },
                modifier = Modifier.debugPath("$path.anywhere")
            )
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)

            // Порядок -- по текущему языку: по-русски список должен
            // читаться по-русски, а не по английскому имени (B7).
            RegionRegistry.allSorted(lang).forEach { region ->
                DropdownMenuItem(
                    text = { Text(RegionRegistry.localized(region.code, lang)) },
                    trailingIcon = {
                        if (region.code == selected) {
                            Text(
                                text = "✓",
                                style = MaterialTheme.typography.bodyLarge,
                                color = MaterialTheme.colorScheme.primary
                            )
                        }
                    },
                    onClick = {
                        expanded = false
                        if (region.code != selected) onSelect(region.code)
                    }
                )
            }
        }
    }
}