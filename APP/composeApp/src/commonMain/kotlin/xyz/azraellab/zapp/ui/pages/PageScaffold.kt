package xyz.azraellab.zapp.ui.pages

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.material3.MaterialTheme
import xyz.azraellab.zapp.ui.components.ZappSectionTitle
import xyz.azraellab.zapp.ui.theme.AzraelSpace

/**
 * Общая оболочка страницы.
 *
 * Заголовок и прокрутка описаны один раз, потому что пять страниц отличаются
 * содержимым, а не построением. Дублировать эти пять строк в каждом экране
 * означало бы, что одна из них рано или поздно забудет про отступы снизу, и
 * последний элемент уезжал бы под системную панель.
 */
@Composable
fun PageScaffold(
    title: String,
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = AzraelSpace.screenPadding)
            // Нижний отступ нужен и под панель навигации, и под системные
            // жесты: без него последний ряд нельзя будет нажать.
            .padding(bottom = AzraelSpace.xxl),
        verticalArrangement = Arrangement.spacedBy(AzraelSpace.cardGap)
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.headlineSmall,
            color = MaterialTheme.colorScheme.onSurface
        )
        content()
    }
}

/**
 * Заголовок группы настроек внутри страницы.
 *
 * Отдельная обёртка над [ZappSectionTitle], чтобы на страницах отступы между
 * группами были одинаковыми, а не «как получится».
 */
@Composable
fun PageGroup(
    title: String,
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit
) {
    Column(
        modifier = modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(AzraelSpace.sm)
    ) {
        ZappSectionTitle(title)
        content()
    }
}
