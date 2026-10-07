package xyz.azraellab.zapp.ui.components

import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import xyz.azraellab.zapp.core.dev.isDebugBuild

/**
 * Отладочный путь интерактивного элемента.
 *
 * Тестовый драйвер находит кнопку/поле/переключатель по пути, а не по
 * координатам или тексту: uiautomator отдаёт его как contentDescription
 * вида `btn:<path>` (например `btn:tab.tor`, `btn:tor.start`,
 * `btn:settings.vpn`).
 *
 * Работает только в debug-сборке: в релизе это пустой модификатор, дерево
 * семантики не раздувается и скрин-ридер языка пользователя не затирается.
 */
fun Modifier.debugPath(path: String): Modifier =
    if (isDebugBuild()) semantics { contentDescription = "btn:$path" }
    else this