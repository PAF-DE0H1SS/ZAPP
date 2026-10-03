package xyz.azraellab.zapp.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import xyz.azraellab.zapp.core.AppState
import xyz.azraellab.zapp.core.Str
import xyz.azraellab.zapp.core.daemon.DaemonState
import xyz.azraellab.zapp.core.engine.TunnelState
import xyz.azraellab.zapp.core.gps.GpsState
import xyz.azraellab.zapp.core.permissions.PermissionId
import xyz.azraellab.zapp.core.permissions.PermissionState
import xyz.azraellab.zapp.core.update.UpdateState

/**
 * Строка состояния над нижней навигацией.
 *
 * Показывает только то, что требует внимания прямо сейчас: состояние
 * подмены, неполученные разрешения при активной подмене и свежий релиз.
 * Всё остальное ушло бы в шум -- строка должна читаться за взгляд.
 *
 * Фон совпадает с фоном NavigationBar, поэтому строка и панель выглядят
 * как один блок, а не как два наложенных элемента.
 */
@Composable
fun StatusBar(state: AppState) {
    val gpsState by state.gpsEngine.state.collectAsState()
    val updateState by state.updateState.collectAsState()
    val permissions by state.permissions.statuses.collectAsState()
    val zapretState by state.zapretDaemon.state.collectAsState()
    val dpiState by state.dpiDaemon.state.collectAsState()
    val tunnelState by state.tunnel.state.collectAsState()

    val locationReady = remember(permissions) {
        listOf(PermissionId.LOCATION_FINE, PermissionId.LOCATION_MOCK).all { id ->
            permissions.firstOrNull { it.id == id }?.state == PermissionState.GRANTED
        }
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surfaceContainer)
            .padding(horizontal = 16.dp, vertical = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(14.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        StatusItem(
            color = when (gpsState) {
                GpsState.ACTIVE -> MaterialTheme.colorScheme.primary
                GpsState.ERROR -> MaterialTheme.colorScheme.error
                GpsState.STARTING -> MaterialTheme.colorScheme.tertiary
                GpsState.OFF -> MaterialTheme.colorScheme.onSurfaceVariant
            },
            label = tr(gpsState.labelKey())
        )

        // Демоны и туннель видны только когда работают: выключенный раздел
        // не требует внимания и не должен занимать строку. Показ активного
        // демона здесь же сообщает, что правила обхода сейчас применены.
        DaemonStatus(zapretState, tr(Str.TAB_ZAPRET))
        DaemonStatus(dpiState, tr(Str.STATUS_DPI))
        TunnelStatus(tunnelState, tr(Str.TAB_VPN))

        // Разрешения важны только рядом с работающей или запрошенной
        // подменой: спокойному пользователю строка не должна напоминать
        // о гео-разрешениях, которыми он не пользуется.
        if (gpsState != GpsState.OFF && !locationReady) {
            StatusItem(
                color = MaterialTheme.colorScheme.error,
                label = tr(Str.STATUS_PERMS_NEEDED)
            )
        }

        Spacer(modifier = Modifier.weight(1f))

        if (updateState == UpdateState.AVAILABLE) {
            StatusItem(
                color = MaterialTheme.colorScheme.primary,
                label = tr(Str.UPD_STATE_AVAILABLE),
                onClick = { state.openReleasePage() }
            )
        }
    }
}

/** Точка с подписью; с кликом обновление открывает страницу релиза. */
@Composable
private fun StatusItem(color: androidx.compose.ui.graphics.Color, label: String, onClick: (() -> Unit)? = null) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        modifier = if (onClick != null) {
            Modifier.clickable(onClick = onClick)
        } else {
            Modifier
        }
    ) {
        Box(
            modifier = Modifier
                .size(8.dp)
                .clip(CircleShape)
                .background(color)
        )
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

/** Точка демона: появляется только когда процесс не остановлен. */
@Composable
private fun DaemonStatus(state: DaemonState, label: String) {
    if (state == DaemonState.STOPPED) return
    StatusItem(
        color = when (state) {
            DaemonState.RUNNING -> MaterialTheme.colorScheme.primary
            DaemonState.ERROR -> MaterialTheme.colorScheme.error
            DaemonState.STARTING -> MaterialTheme.colorScheme.tertiary
            DaemonState.STOPPED -> return
        },
        label = label
    )
}

/** Точка туннеля: та же логика -- только когда он поднимался. */
@Composable
private fun TunnelStatus(state: TunnelState, label: String) {
    if (state == TunnelState.DISCONNECTED) return
    StatusItem(
        color = when (state) {
            TunnelState.CONNECTED -> MaterialTheme.colorScheme.primary
            TunnelState.ERROR -> MaterialTheme.colorScheme.error
            TunnelState.CONNECTING -> MaterialTheme.colorScheme.tertiary
            TunnelState.DISCONNECTED -> return
        },
        label = label
    )
}

/** Состояние движка -- ключ строки для перевода. */
private fun GpsState.labelKey(): Str = when (this) {
    GpsState.OFF -> Str.GPS_STATE_OFF
    GpsState.STARTING -> Str.GPS_STATE_STARTING
    GpsState.ACTIVE -> Str.GPS_STATE_ACTIVE
    GpsState.ERROR -> Str.GPS_STATE_ERROR
}
