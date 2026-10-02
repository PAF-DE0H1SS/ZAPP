package xyz.azraellab.zapp.ui.nav

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import xyz.azraellab.zapp.core.AppState
import xyz.azraellab.zapp.ui.pages.GoodbyeDpiPage
import xyz.azraellab.zapp.ui.pages.GpsPage
import xyz.azraellab.zapp.ui.pages.SettingsPage
import xyz.azraellab.zapp.ui.pages.VpnPage
import xyz.azraellab.zapp.ui.pages.ZapretPage
import xyz.azraellab.zapp.ui.traffic.TrafficWindow
import xyz.azraellab.zapp.ui.tr

/**
 * Корневой экран приложения.
 *
 * Навигация своя, без `navigation-compose`: экранов пять, состояние -- два
 * поля, а библиотека ради этого потянула бы за собой отдельную архитектуру
 * для случая, где переходов три.
 *
 * Состояние хранится как `String` и `Boolean`, а не как sealed-класс,
 * специально ради `rememberSaveable`: enum и boolean переживают пересоздание
 * Activity без Saver, а sealed-класс потребовал бы писать Saver руками.
 */
@Composable
fun AppRoot(state: AppState) {
    var tabName: String by rememberSaveable { mutableStateOf(AppTab.first.name) }
    val trafficOpen by state.trafficWindowOpen.collectAsState()

    val tabs = remember(state.caps.gpsSpoofingSupported) {
        AppTab.available(state.caps.gpsSpoofingSupported)
    }

    // Раздел мог остаться открытым после смены платформы или настроек. Если
    // его больше нет в списке, возвращаемся на первый доступный, иначе
    // пользователь остался бы на странице, которой нет.
    val current = AppTab.entries.firstOrNull { it.name == tabName }
    val activeTab = current?.takeIf { it in tabs } ?: tabs.first()
    if (current != activeTab) tabName = activeTab.name

    val showBottomBar = !trafficOpen

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        bottomBar = {
            AnimatedVisibility(
                visible = showBottomBar,
                enter = slideInVertically { it } + fadeIn(),
                exit = slideOutVertically { it } + fadeOut()
            ) {
                NavigationBar(
                    containerColor = MaterialTheme.colorScheme.surfaceContainer
                ) {
                    tabs.forEach { tab ->
                        NavigationBarItem(
                            selected = tab == activeTab,
                            onClick = {
                                tabName = tab.name
                                state.setTrafficWindowOpen(false)
                            },
                            icon = { Icon(tab.icon, contentDescription = null) },
                            label = { Text(tr(tab.title)) },
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
            if (trafficOpen) {
                TrafficWindow(
                    state = state,
                    onBack = { state.setTrafficWindowOpen(false) }
                )
            } else {
                TabContent(activeTab, state)
            }
        }
    }
}

/**
 * Содержимое вкладки.
 */
@Composable
private fun TabContent(tab: AppTab, state: AppState) {
    when (tab) {
        AppTab.VPN -> VpnPage(state)
        AppTab.ZAPRET -> ZapretPage(state)
        AppTab.GOODBYE_DPI -> GoodbyeDpiPage(state)
        AppTab.GPS -> GpsPage(state)
        AppTab.SETTINGS -> SettingsPage(state)
    }
}
