package xyz.azraellab.zapp.ui.nav

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import xyz.azraellab.zapp.core.AppState
import xyz.azraellab.zapp.ui.pages.GoodbyeDpiPage
import xyz.azraellab.zapp.ui.pages.GpsPage
import xyz.azraellab.zapp.ui.pages.SettingsPage
import xyz.azraellab.zapp.ui.pages.TorPage
import xyz.azraellab.zapp.ui.pages.VpnPage
import xyz.azraellab.zapp.ui.pages.ZapretPage
import xyz.azraellab.zapp.ui.StatusBar
import xyz.azraellab.zapp.ui.traffic.TrafficWindow
import xyz.azraellab.zapp.ui.tr

/**
 * Корневой экран приложения.
 *
 * Навигация своя, без `navigation-compose`: экранов пять, состояние -- два
 * поля, а библиотека ради этого потянула бы за собой отдельную архитектуру
 * для случая, где переходов три.
 *
 * Два правила, которые держат весь экран:
 *
 *  1. `containerColor` прозрачный. Под контентом лежит звёздное небо, и
 *     непрозрачный Scaffold закрывал его целиком -- фон приложения был
 *     чистой заливкой без единой звезды.
 *  2. Нижняя панель видна всегда, включая окно мониторинга трафика.
 *     Панель -- это способ вернуться, и прятать её в единственном месте,
 *     откуда негде вернуться, -- прямо противоположность назначения.
 *
 * Состояние хранится как `String`, а не как sealed-класс, специально ради
 * `rememberSaveable`: строка переживает пересоздание Activity без Saver.
 */
@Composable
fun AppRoot(state: AppState) {
    var tabName: String by rememberSaveable { mutableStateOf(AppTab.first.name) }
    val trafficOpen by state.trafficWindowOpen.collectAsState()

    val tabs = remember(state.caps.gpsSpoofingSupported) {
        AppTab.available(state.caps.gpsSpoofingSupported)
    }

    // Раздел мог остаться открытым после смены настроек. Если его больше
    // нет в списке, возвращаемся на первый доступный, иначе пользователь
    // остался бы на странице, которой нет.
    val current = AppTab.entries.firstOrNull { it.name == tabName }
    val activeTab = current?.takeIf { it in tabs } ?: tabs.first()
    if (current != activeTab) tabName = activeTab.name

    // Слоты панели: связанные разделы живут в одной кнопке (тор и VPN,
    // zapret и goodbyeDPI), чтобы не тратить полосу на четыре почти
    // одноимённых ячейки. GPS и настройки -- одиночки.
    val slots = remember(tabs) { tabSlots(tabs) }

    ZappBackHandler(enabled = trafficOpen) { state.setTrafficWindowOpen(false) }

    Scaffold(
        containerColor = Color.Transparent,
        bottomBar = {
            // Строка состояния лежит над панелью и живёт по её правилам:
            // видна всегда, включая окно трафика, -- иначе важное событие
            // пропадало бы ровно в тот момент, когда его смотрят.
            Column {
                StatusBar(state)
                NavigationBar(containerColor = MaterialTheme.colorScheme.surfaceContainer) {
                // RowScope нужен явно: внутри Box контент кнопки теряет
                // неявного приёмника строки панели.
                val navRow = this
                slots.forEach { slot ->
                    // Запомненная половина пары: возврат в пару открывает
                    // то, что смотрели раньше, а не всегда первую половину.
                    val choice = rememberSaveable(slot.first().name) {
                        mutableStateOf(slot.first().name)
                    }
                    val shown = activeTab.takeIf { it in slot }
                        ?: slot.firstOrNull { it.name == choice.value }
                        ?: slot.first()
                    val isSelected = !trafficOpen && activeTab in slot
                    // Кнопка прозрачная; выделенный слот обведён зелёным
                    // контуром вместо сплошной заливки-индикатора.
                    // Высота -- своя у NavigationBarItem: fillMaxHeight
                    // здесь растянул бы панель на весь экран, потому что
                    // Scaffold отдаёт bottomBar неограниченную высоту.
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .padding(horizontal = 4.dp)
                            .then(
                                if (isSelected) {
                                    Modifier.border(
                                        width = 1.dp,
                                        color = MaterialTheme.colorScheme.primary,
                                        shape = RoundedCornerShape(14.dp)
                                    )
                                } else {
                                    Modifier
                                }
                            ),
                        contentAlignment = Alignment.Center
                    ) {
                    navRow.NavigationBarItem(
                        selected = isSelected,
                        modifier = Modifier.fillMaxWidth(),
                        onClick = {
                            state.setTrafficWindowOpen(false)
                            val next = when {
                                // На одном из пары: повторное нажатие
                                // переключает на другого.
                                activeTab in slot && slot.size > 1 ->
                                    slot.first { it != activeTab }
                                activeTab in slot -> activeTab
                                // Не на их окнах: простой переход к
                                // запомненному члену слота.
                                else -> slot.firstOrNull { it.name == choice.value }
                                    ?: slot.first()
                            }
                            if (slot.size > 1) choice.value = next.name
                            tabName = next.name
                        },
                        icon = {
                            if (slot.size == 1) {
                                Icon(
                                    imageVector = slot.first().icon,
                                    contentDescription = null,
                                    tint = if (isSelected) {
                                        MaterialTheme.colorScheme.primary
                                    } else {
                                        MaterialTheme.colorScheme.onSurface
                                    }
                                )
                            } else {
                                // Две иконки в одной кнопке: активная
                                // зелёная, вторая белая -- видно, какая
                                // половина открыта.
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    slot.forEachIndexed { index, member ->
                                        if (index > 0) Spacer(Modifier.width(4.dp))
                                        Icon(
                                            imageVector = member.icon,
                                            contentDescription = null,
                                            modifier = Modifier.size(if (member == shown) 22.dp else 16.dp),
                                            tint = if (isSelected && member == activeTab) {
                                                MaterialTheme.colorScheme.primary
                                            } else {
                                                MaterialTheme.colorScheme.onSurface
                                            }
                                        )
                                    }
                                }
                            }
                        },
                        label = {
                            if (slot.size == 1) {
                                Text(
                                    text = tr(shown.short),
                                    style = MaterialTheme.typography.labelSmall,
                                    maxLines = 1,
                                    color = if (isSelected) {
                                        MaterialTheme.colorScheme.primary
                                    } else {
                                        MaterialTheme.colorScheme.onSurface
                                    }
                                )
                            } else {
                                // «T | V» / «Z | GDPI»: активная буква
                                // зелёная, вторая белая.
                                Row {
                                    slot.forEachIndexed { index, member ->
                                        if (index > 0) {
                                            Text(
                                                text = " | ",
                                                style = MaterialTheme.typography.labelSmall,
                                                maxLines = 1,
                                                color = MaterialTheme.colorScheme.onSurface
                                            )
                                        }
                                        Text(
                                            text = tr(member.short),
                                            style = MaterialTheme.typography.labelSmall,
                                            maxLines = 1,
                                            color = if (isSelected && member == activeTab) {
                                                MaterialTheme.colorScheme.primary
                                            } else {
                                                MaterialTheme.colorScheme.onSurface
                                            }
                                        )
                                    }
                                }
                            }
                        },
                        colors = NavigationBarItemDefaults.colors(
                            indicatorColor = Color.Transparent,
                            selectedIconColor = MaterialTheme.colorScheme.primary,
                            selectedTextColor = MaterialTheme.colorScheme.primary,
                            unselectedIconColor = MaterialTheme.colorScheme.onSurface,
                            unselectedTextColor = MaterialTheme.colorScheme.onSurface
                        )
                    )
                    }
                }
                }
            }
        }
    ) { padding ->
        // На широких экранах контент ограничен и отцентрован: лента полей
        // во всю ширину монитора нечитаема, строка становится длиннее глаза.
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
            contentAlignment = Alignment.Center
        ) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .widthIn(max = 720.dp)
            ) {
            // Ключ -- это содержимое, а не имя вкладки: окно трафика живёт
            // поверх выбранной вкладки, и при его закрытии переход должен
            // вернуться к той же странице, а не переигрывать её появление.
            val contentKey = if (trafficOpen) "traffic" else activeTab.name
            AnimatedContent(
                targetState = contentKey,
                transitionSpec = {
                    if (targetState == "traffic" || initialState == "traffic") {
                        fadeIn(tween(180)) togetherWith fadeOut(tween(120))
                    } else {
                        val forward = targetState > initialState
                        val direction = if (forward) 1 else -1
                        fadeIn(tween(200)) + slideInHorizontally(tween(220)) { it / 8 * direction } togetherWith
                            fadeOut(tween(140)) + slideOutHorizontally(tween(200)) { -it / 8 * direction }
                    }
                },
                label = "tab-content"
            ) { key ->
                when {
                    key == "traffic" -> TrafficWindow(
                        state = state,
                        onBack = { state.setTrafficWindowOpen(false) }
                    )
                    key == AppTab.TOR.name -> TorPage(state)
                    key == AppTab.VPN.name -> VpnPage(state)
                    key == AppTab.ZAPRET.name -> ZapretPage(state)
                    key == AppTab.GOODBYE_DPI.name -> GoodbyeDpiPage(state)
                    key == AppTab.GPS.name -> GpsPage(state)
                    else -> SettingsPage(state)
                }
            }
            }
        }
    }
}

/**
 * Слоты нижней панели из списка доступных вкладок.
 *
 * Связанные разделы объединяются в пару, если оба присутствуют:
 * TOR+VPN и Zapret+GoodbyeDPI. Одиночки (GPS, настройки) идут как есть.
 * Порядок входного списка -- канонический порядок [AppTab], пара всегда
 * лежит в нём подряд.
 */
internal fun tabSlots(tabs: List<AppTab>): List<List<AppTab>> {
    val slots = mutableListOf<List<AppTab>>()
    var i = 0
    while (i < tabs.size) {
        val first = tabs[i]
        val next = tabs.getOrNull(i + 1)
        val partner = if (next != null && partnerOf(next) == first) next else null
        slots += if (partner != null) listOf(first, partner) else listOf(first)
        i += if (partner != null) 2 else 1
    }
    return slots
}

/** Вторая половина слота; null, если вкладка -- одиночка. */
internal fun partnerOf(tab: AppTab): AppTab? = when (tab) {
    AppTab.VPN -> AppTab.TOR
    AppTab.GOODBYE_DPI -> AppTab.ZAPRET
    else -> null
}
