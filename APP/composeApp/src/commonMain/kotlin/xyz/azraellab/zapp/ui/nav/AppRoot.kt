package xyz.azraellab.zapp.ui.nav

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
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
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import xyz.azraellab.zapp.core.AppState
import xyz.azraellab.zapp.ui.pages.GoodbyeDpiPage
import xyz.azraellab.zapp.ui.pages.GpsPage
import xyz.azraellab.zapp.ui.pages.SettingsPage
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

    Scaffold(
        containerColor = Color.Transparent,
        bottomBar = {
            // Строка состояния лежит над панелью и живёт по её правилам:
            // видна всегда, включая окно трафика, -- иначе важное событие
            // пропадало бы ровно в тот момент, когда его смотрят.
            Column {
                StatusBar(state)
                NavigationBar(containerColor = MaterialTheme.colorScheme.surfaceContainer) {
                tabs.forEach { tab ->
                    NavigationBarItem(
                        selected = tab == activeTab && !trafficOpen,
                        onClick = {
                            tabName = tab.name
                            state.setTrafficWindowOpen(false)
                        },
                        icon = { Icon(tab.icon, contentDescription = null) },
                        // maxLines=1: «GoodbyeDPI» иначе переносится на
                        // вторую строку и в панели появляется обрубок.
                        label = {
                            Text(
                                text = tr(tab.title),
                                style = MaterialTheme.typography.labelSmall,
                                maxLines = 1
                            )
                        },
                        colors = NavigationBarItemDefaults.colors(
                            selectedIconColor = MaterialTheme.colorScheme.onPrimary,
                            selectedTextColor = MaterialTheme.colorScheme.primary,
                            indicatorColor = MaterialTheme.colorScheme.primary,
                            unselectedIconColor = MaterialTheme.colorScheme.onSurfaceVariant,
                            unselectedTextColor = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    )
                }
                }
            }
        }
    ) { padding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
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
