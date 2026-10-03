package xyz.azraellab.zapp.ui.welcome

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import xyz.azraellab.zapp.core.AppState
import xyz.azraellab.zapp.core.Str
import xyz.azraellab.zapp.core.permissions.PermissionState
import xyz.azraellab.zapp.ui.components.ComponentColors.ButtonRole
import xyz.azraellab.zapp.ui.components.ZappButton
import xyz.azraellab.zapp.ui.components.ZappButtonRow
import xyz.azraellab.zapp.ui.components.ZappCard
import xyz.azraellab.zapp.ui.components.ZappListRow
import xyz.azraellab.zapp.ui.pages.PermissionRow
import xyz.azraellab.zapp.ui.theme.AzraelSpace
import xyz.azraellab.zapp.ui.tr

/**
 * Приветствие при первом запуске.
 *
 * Три экрана подряд: приветствие и тур по вкладкам, разрешения (с кнопками
 * запроса прямо здесь), готово. Состояние -- один индекс; назад можно с
 * шага разрешений, вперёд -- только осознанным нажатием. Дальше экран не
 * показывается: флаг welcomeDone в настройках.
 */
@Composable
fun WelcomeScreen(state: AppState) {
    var step by remember { mutableIntStateOf(0) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(AzraelSpace.screenPadding),
        verticalArrangement = Arrangement.spacedBy(AzraelSpace.cardGap),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Spacer(Modifier.height(AzraelSpace.xl))

        when (step) {
            0 -> WelcomeStep(state, onNext = { step = 1 })
            1 -> PermissionsStep(state, onNext = { step = 2 }, onBack = { step = 0 })
            else -> DoneStep(state)
        }
    }
}

@Composable
private fun WelcomeStep(state: AppState, onNext: () -> Unit) {
    Text(
        text = tr(Str.WELCOME_TITLE),
        style = MaterialTheme.typography.headlineMedium,
        color = MaterialTheme.colorScheme.primary,
        textAlign = TextAlign.Center
    )
    Text(
        text = tr(Str.WELCOME_SUBTITLE),
        style = MaterialTheme.typography.bodyLarge,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        textAlign = TextAlign.Center
    )

    ZappCard(modifier = Modifier.fillMaxWidth()) {
        ZappListRow(title = tr(Str.TAB_VPN), subtitle = tr(Str.WELCOME_HINT_VPN))
        ZappListRow(title = tr(Str.TAB_ZAPRET), subtitle = tr(Str.WELCOME_HINT_ZAPRET))
        ZappListRow(title = tr(Str.TAB_GOODBYE_DPI), subtitle = tr(Str.WELCOME_HINT_DPI))
        ZappListRow(title = tr(Str.TAB_GPS), subtitle = tr(Str.WELCOME_HINT_GPS))
        ZappListRow(title = tr(Str.SETTINGS_LOG), subtitle = tr(Str.WELCOME_HINT_LOG))
    }

    ZappButton(
        text = tr(Str.WELCOME_NEXT),
        onClick = { state.finishWelcome(); onNext() },
        modifier = Modifier.fillMaxWidth()
    )
}

@Composable
private fun PermissionsStep(state: AppState, onNext: () -> Unit, onBack: () -> Unit) {
    val statuses by state.permissions.statuses.collectAsState()

    Text(
        text = tr(Str.WELCOME_PERMISSIONS),
        style = MaterialTheme.typography.titleMedium,
        color = MaterialTheme.colorScheme.onSurface,
        textAlign = TextAlign.Center
    )

    ZappCard(modifier = Modifier.fillMaxWidth()) {
        statuses.forEach { status ->
            PermissionRow(
                status = status,
                onAction = { state.permissions.request(status.id) {} }
            )
        }
    }

    ZappButtonRow(modifier = Modifier.fillMaxWidth()) {
        ZappButton(
            text = tr(Str.COMMON_BACK),
            role = ButtonRole.GHOST,
            onClick = onBack,
            modifier = Modifier.weight(1f)
        )
        ZappButton(
            text = tr(Str.WELCOME_NEXT),
            onClick = { state.finishWelcome(); onNext() },
            modifier = Modifier.weight(1f)
        )
    }
}

@Composable
private fun DoneStep(state: AppState) {
    Spacer(Modifier.height(AzraelSpace.xl))
    Text(
        text = tr(Str.WELCOME_DONE),
        style = MaterialTheme.typography.headlineSmall,
        color = MaterialTheme.colorScheme.primary,
        textAlign = TextAlign.Center
    )
    ZappButton(
        text = tr(Str.WELCOME_DONE),
        onClick = { state.finishWelcome() },
        modifier = Modifier.fillMaxWidth()
    )
}
