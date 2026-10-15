package xyz.azraellab.zapp.ui.pages

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Checkbox
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import xyz.azraellab.zapp.core.AppState
import xyz.azraellab.zapp.core.BuildMode
import xyz.azraellab.zapp.core.Str
import xyz.azraellab.zapp.core.engine.TunnelState
import xyz.azraellab.zapp.core.perApp.AppSelection
import xyz.azraellab.zapp.core.perApp.InstalledApp
import xyz.azraellab.zapp.core.perApp.PerAppConfig
import xyz.azraellab.zapp.core.perApp.RouteMode
import xyz.azraellab.zapp.core.perApp.installedApps
import xyz.azraellab.zapp.core.perApp.ownPackageName
import xyz.azraellab.zapp.ui.components.Choice
import xyz.azraellab.zapp.ui.components.ZappBanner
import xyz.azraellab.zapp.ui.components.ZappCard
import xyz.azraellab.zapp.ui.components.ZappChoiceRow
import xyz.azraellab.zapp.ui.components.ZappEmptyState
import xyz.azraellab.zapp.ui.components.ZappListRow
import xyz.azraellab.zapp.ui.components.ZappSettingRow
import xyz.azraellab.zapp.ui.components.ZappSkeletonList
import xyz.azraellab.zapp.ui.components.ZappTextField
import xyz.azraellab.zapp.ui.components.debugPath
import xyz.azraellab.zapp.ui.tr

/**
 * Функция, которой владеет выбор приложений.
 *
 * Один общий пикер на четыре функции: что именно меняется, решает
 * [read]/[write], а не четыре копии экрана. VPN и Tor делят один
 * туннель, но не делят выбор -- у каждой вкладки своё намерение.
 */
enum class PerAppScope(val title: Str, val path: String) {
    VPN(Str.TAB_VPN, "perapp.vpn") {
        override fun read(cfg: PerAppConfig): AppSelection = cfg.vpn
        override fun write(cfg: PerAppConfig, value: AppSelection): PerAppConfig =
            cfg.copy(vpn = value)
    },
    TOR(Str.TAB_TOR, "perapp.tor") {
        override fun read(cfg: PerAppConfig): AppSelection = cfg.tor
        override fun write(cfg: PerAppConfig, value: AppSelection): PerAppConfig =
            cfg.copy(tor = value)
    },
    ZAPRET(Str.TAB_ZAPRET, "perapp.zapret") {
        override fun read(cfg: PerAppConfig): AppSelection = cfg.zapret
        override fun write(cfg: PerAppConfig, value: AppSelection): PerAppConfig =
            cfg.copy(zapret = value)
    },
    GDPI(Str.TAB_GOODBYE_DPI, "perapp.gdpi") {
        override fun read(cfg: PerAppConfig): AppSelection = cfg.goodbyeDpi
        override fun write(cfg: PerAppConfig, value: AppSelection): PerAppConfig =
            cfg.copy(goodbyeDpi = value)
    };

    abstract fun read(cfg: PerAppConfig): AppSelection
    abstract fun write(cfg: PerAppConfig, value: AppSelection): PerAppConfig

    /** Обход DPI живёт в ядре, а не в туннеле: без root списку нечего применять. */
    fun requiresRoot(): Boolean = this == ZAPRET || this == GDPI

    /** Туннельная сессия: её смена списка видна только после переподключения. */
    fun isTunnel(): Boolean = this == VPN || this == TOR
}

/**
 * Счётчик выбора для корневой строки: «Выбрано» в include-режиме,
 * «Кроме» -- в exclude. Выключенный выбор всегда «все приложения».
 */
@Composable
private fun AppSelection.countLabel(): String = when {
    !enabled || isEmpty() -> tr(Str.PERAPP_ALL_APPS)
    mode == RouteMode.INCLUDE -> "${tr(Str.PERAPP_SELECTED)}: ${packages.size}"
    else -> "${tr(Str.PERAPP_EXCEPT)}: ${packages.size}"
}

/**
 * Корень раздела «Приложения»: четыре функции со счётчиками.
 *
 * Счётчик показывает размер выбора, а не состояние включённости:
 * выключенный тумблер с сохранённым списком -- норма, и число
 * напоминает, что фильтр вернётся вместе с тумблером.
 */
@Composable
fun PerAppRoot(state: AppState, onOpen: (String) -> Unit) {
    PageGroup(tr(Str.SETTINGS_PERAPP)) {
        ZappCard {
            for (scope in PerAppScope.entries) {
                val selection = scope.read(state.config.perApp)
                ZappListRow(
                    title = tr(scope.title),
                    subtitle = selection.countLabel(),
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { onOpen(scope.path) },
                    trailing = {
                        Text(
                            text = ">",
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    },
                    path = "settings.${scope.path}"
                )
            }
        }
    }
}

/**
 * Пикер приложений одной функции: тумблер, поиск и список с галочками.
 *
 * Список читается из платформы (PackageManager) в фоне: сотни пакетов
 * на главном потоке дают видимую паузу кадра. Пустой каталог -- не
 * ошибка, а состояние платформы: десктоп приложений не имеет, и экран
 * честно сообщает «ничего не нашлось».
 */
@Composable
fun PerAppPicker(state: AppState, scope: PerAppScope) {
    val perApp = state.config.perApp
    val selection = scope.read(perApp)
    val tunnelState by state.tunnel.state.collectAsState()
    var query by remember { mutableStateOf("") }
    // Поиск отложен на четверть секунды: каталог в сотни пакетов
    // пересчитывается на каждый символ, и фильтр тут же дергает
    // рекомпозицию всего списка. Пока тишина -- показываем прежний
    // результат, набор слов не превращается в подвисания.
    var pattern by remember { mutableStateOf("") }
    LaunchedEffect(query) {
        delay(250)
        pattern = query
    }
    var catalog by remember { mutableStateOf<List<InstalledApp>?>(null) }

    LaunchedEffect(Unit) {
        catalog = withContext(Dispatchers.IO) {
            runCatching { installedApps() }.getOrDefault(emptyList())
        }
    }

    // Своё приложение из списка уходит: его трафик всегда мимо туннеля,
    // и галка на нём означала бы то, чего система не делает.
    val self = remember { ownPackageName() }
    val visible = remember(catalog, perApp.showSystemApps, pattern, self) {
        val q = pattern.trim().lowercase()
        val base = (catalog ?: emptyList())
            .filter { it.packageName != self }
            .filter { perApp.showSystemApps || !it.isSystem }
        if (q.isEmpty()) return@remember base
        // Поиск работает по семьям: совпавший узел тянет за собой
        // всю ветку (процессы-потомки на ПК, uid-соседи на Android) --
        // так «связанные процессы» видны, а не только точное имя.
        val direct = base.filter {
            it.label.lowercase().contains(q) || it.packageName.lowercase().contains(q)
        }
        if (direct.isEmpty()) return@remember emptyList()
        val families = direct.mapNotNull { it.familyId ?: it.uid }.toSet()
        base.filter {
            (it.label.lowercase().contains(q) || it.packageName.lowercase().contains(q)) ||
                (it.familyId ?: it.uid) in families
        }
    }

    ZappCard {
        ZappSettingRow(
            title = tr(Str.PERAPP_SELECT),
            checked = selection.enabled,
            onCheckedChange = { on ->
                state.mutate { cfg ->
                    val cur = scope.read(cfg.perApp)
                    cfg.copy(perApp = scope.write(cfg.perApp, cur.copy(enabled = on)))
                }
            },
            subtitle = if (selection.enabled && !selection.isEmpty()) {
                selection.countLabel()
            } else {
                null
            },
            path = "perapp.enabled"
        )
        // Режим выбирается до списка: галочки означают разные вещи --
        // «через функцию» или «мимо функции», и это должно быть видно
        // до того, как пользователь начнёт отмечать приложения.
        if (selection.enabled) {
            ZappChoiceRow(
                label = tr(Str.PERAPP_MODE),
                options = listOf(
                    Choice(RouteMode.INCLUDE, tr(Str.PERAPP_MODE_INCLUDE)),
                    Choice(RouteMode.EXCLUDE, tr(Str.PERAPP_MODE_EXCLUDE))
                ),
                selected = selection.mode,
                onSelect = { mode ->
                    state.mutate { cfg ->
                        val cur = scope.read(cfg.perApp)
                        cfg.copy(perApp = scope.write(cfg.perApp, cur.copy(mode = mode)))
                    }
                },
                path = "perapp.mode"
            )
        }
        if (scope.requiresRoot() && !BuildMode.rootAllowed) {
            ZappBanner(title = tr(Str.PERAPP_SCOPE_UNAVAILABLE))
        }
        if (scope.isTunnel() && tunnelState == TunnelState.CONNECTED) {
            ZappBanner(title = tr(Str.PERAPP_NEXT_CONNECT))
        }
        ZappListRow(
            title = tr(Str.PERAPP_SELF_EXCLUDED),
            path = "perapp.self"
        )
    }

    ZappTextField(
        label = tr(Str.PERAPP_SEARCH),
        value = query,
        onValueChange = { query = it },
        path = "perapp.search"
    )

    ZappCard {
        ZappSettingRow(
            title = tr(Str.PERAPP_SHOW_SYSTEM),
            checked = perApp.showSystemApps,
            onCheckedChange = { on ->
                state.mutate { cfg -> cfg.copy(perApp = cfg.perApp.copy(showSystemApps = on)) }
            },
            path = "perapp.showSystem"
        )
        if (catalog == null) {
            // Каталог в сотни пакетов грузится заметно: раньше список
            // молча пустел, и непонятно -- нет приложений или ещё не
            // успели. Скелетон даёт видимость работы (UX2).
            Spacer(modifier = Modifier.height(4.dp))
            ZappSkeletonList(rows = 4)
        }
        if (catalog != null && visible.isEmpty()) {
            Spacer(modifier = Modifier.height(4.dp))
            ZappEmptyState(title = tr(Str.PERAPP_NO_MATCH))
        }
    }

    // Список отдельной карточкой: сотни строк внутри карточки с
    // тумблерами раздули бы её рекомпозицию на каждое нажатие чекбокса.
    if (visible.isNotEmpty()) {
        ZappCard {
            // Длинный список -- не карточка с пустотой, а линованная
            // лента: тонкая линейка между строками держит глаз на строке,
            // когда прокручиваешь сотни пунктов.
            for ((index, app) in visible.withIndex()) {
                val checked = app.packageName in selection.packages
                ZappListRow(
                    title = app.label,
                    subtitle = app.packageName,
                    modifier = Modifier
                        .fillMaxWidth()
                        // Отступ уровня: на десктопе это ветка дерева
                        // процессов (потомок видно под родителем).
                        .padding(start = (app.depth * 14).dp)
                        .clickable {
                            // Свежий выбор читается внутри mutate: серия
                            // быстрых тапов не должна работать по кадру
                            // старому списку.
                            state.mutate { cfg ->
                                val cur = scope.read(cfg.perApp)
                                val next = if (app.packageName in cur.packages) {
                                    cur.packages - app.packageName
                                } else {
                                    cur.packages + app.packageName
                                }
                                cfg.copy(perApp = scope.write(cfg.perApp, cur.copy(packages = next)))
                            }
                        },
                    trailing = {
                        Checkbox(
                            checked = checked,
                            onCheckedChange = null
                        )
                    },
                    path = "perapp.app.${app.packageName}"
                )
                if (index < visible.lastIndex) {
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                }
            }
        }
    }
}
