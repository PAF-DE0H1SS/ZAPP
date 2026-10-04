package xyz.azraellab.zapp.ui.pages

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import xyz.azraellab.zapp.core.AppState
import xyz.azraellab.zapp.core.GpsMode
import xyz.azraellab.zapp.core.Str
import xyz.azraellab.zapp.core.gps.GpsState
import xyz.azraellab.zapp.ui.AppLangState
import xyz.azraellab.zapp.ui.components.ComponentColors.ButtonRole
import xyz.azraellab.zapp.ui.components.Choice
import xyz.azraellab.zapp.ui.components.ZappButton
import xyz.azraellab.zapp.ui.components.ZappCard
import xyz.azraellab.zapp.ui.components.ZappChoiceRow
import xyz.azraellab.zapp.ui.components.ZappEmptyState
import xyz.azraellab.zapp.ui.components.ZappExpandableCard
import xyz.azraellab.zapp.ui.components.ZappNumberField
import xyz.azraellab.zapp.ui.components.ZappSettingRow
import xyz.azraellab.zapp.ui.components.ZappTextField
import xyz.azraellab.zapp.ui.theme.AzraelSpace
import xyz.azraellab.zapp.ui.tr
import java.util.Locale

/**
 * Страница подмены координат.
 *
 * Страница намеренно короткая: управление, режим и его поля. Состояние
 * подмены, журнал и живая точка живут в одной карточке сверху -- это
 * всё, что нужно видеть во время работы; детали, которые меняют редко,
 * лежат ниже и не мешают глазу.
 *
 * На десктопе подмены нет, и вместо заглушки с кнопкой показывается
 * пустое состояние: раздел неактивен концептуально, и предлагать
 * невозможное не стоит.
 */
@Composable
fun GpsPage(state: AppState) {
    val config = state.config.gps
    val gpsState by state.gpsEngine.state.collectAsState()
    val gpsFix by state.gpsEngine.fix.collectAsState()
    val gpsLog by state.gpsEngine.log.collectAsState()

    PageScaffold(title = tr(Str.TAB_GPS)) {
        if (!state.caps.gpsSpoofingSupported) {
            ZappCard {
                ZappEmptyState(
                    title = tr(Str.GPS_UNSUPPORTED_TITLE),
                    body = tr(Str.GPS_UNSUPPORTED_DESC)
                )
            }
            return@PageScaffold
        }

        // --- Управление и состояние ---
        ZappCard {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(AzraelSpace.md),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = tr(state.gpsStateLabel()),
                        style = MaterialTheme.typography.titleMedium,
                        color = when (gpsState) {
                            GpsState.ACTIVE -> MaterialTheme.colorScheme.primary
                            GpsState.ERROR -> MaterialTheme.colorScheme.error
                            else -> MaterialTheme.colorScheme.onSurface
                        }
                    )
                    gpsFix?.let { fix ->
                        Text(
                            text = formatFix(fix.latitude, fix.longitude, fix.place),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
                val running = gpsState == GpsState.ACTIVE || gpsState == GpsState.STARTING
                if (running) {
                    ZappButton(
                        text = tr(Str.COMMON_STOP),
                        role = ButtonRole.DANGER,
                        onClick = { state.stopGps() }
                    )
                } else {
                    ZappButton(
                        text = tr(Str.COMMON_START),
                        onClick = { state.startGps() }
                    )
                }
            }
        }

        // --- Режим ---
        PageGroup(tr(Str.GPS_MODE)) {
            ZappCard {
                ZappChoiceRow(
                    label = tr(Str.GPS_MODE),
                    options = GpsMode.entries.map { Choice(it.code, it.label()) },
                    selected = config.mode,
                    onSelect = { code ->
                        state.mutate { it.copy(gps = it.gps.copy(mode = code)) }
                    }
                )
                // Режим VPN не имеет своих полей: всё решает адрес шлюза,
                // а о найденной точке страница сообщает сверху.
                if (GpsMode.of(config.mode) == GpsMode.VPN_LOCATION) {
                    Text(
                        text = tr(Str.GPS_VPN_HINT),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }

        // --- Точка: только для режима «фиксированная» ---
        if (GpsMode.of(config.mode) == GpsMode.FIXED) {
            PageGroup(tr(Str.GPS_LOCATION)) {
                ZappCard {
                    ZappNumberField(
                        label = tr(Str.GPS_LATITUDE),
                        value = config.latitude.toLong().takeIf { it.toDouble() == config.latitude }
                            ?.toInt(),
                        onValueChange = { v ->
                            state.mutate { it.copy(gps = it.gps.copy(latitude = v?.toDouble() ?: 0.0)) }
                        },
                        placeholder = "55.7558"
                    )
                    ZappNumberField(
                        label = tr(Str.GPS_LONGITUDE),
                        value = config.longitude.toLong().takeIf { it.toDouble() == config.longitude }
                            ?.toInt(),
                        onValueChange = { v ->
                            state.mutate { it.copy(gps = it.gps.copy(longitude = v?.toDouble() ?: 0.0)) }
                        },
                        placeholder = "37.6173"
                    )
                    ZappNumberField(
                        label = tr(Str.GPS_ACCURACY),
                        value = config.accuracyMeters.toLong().toInt(),
                        onValueChange = { v ->
                            state.mutate { it.copy(gps = it.gps.copy(accuracyMeters = (v ?: 5).toDouble())) }
                        },
                        placeholder = "10"
                    )
                    ZappNumberField(
                        label = tr(Str.GPS_ALTITUDE),
                        value = config.altitudeMeters.toLong().toInt(),
                        onValueChange = { v ->
                            state.mutate { it.copy(gps = it.gps.copy(altitudeMeters = (v ?: 0).toDouble())) }
                        }
                    )
                }
            }
        }

        // --- Маршрут: только для режима «маршрут» ---
        if (GpsMode.of(config.mode) == GpsMode.ROUTE) {
            PageGroup(tr(Str.GPS_ROUTE)) {
                ZappCard {
                    ZappTextField(
                        label = tr(Str.GPS_ROUTE_POINTS),
                        value = config.route,
                        onValueChange = { v ->
                            state.mutate { it.copy(gps = it.gps.copy(route = v)) }
                        },
                        supportingText = tr(Str.GPS_ROUTE_FORMAT),
                        singleLine = false
                    )
                    ZappNumberField(
                        label = tr(Str.GPS_ROUTE_SPEED),
                        value = kotlin.math.round(config.speedMps).toInt(),
                        onValueChange = { v ->
                            state.mutate { it.copy(gps = it.gps.copy(speedMps = (v ?: 0).toDouble())) }
                        },
                        placeholder = "20"
                    )
                    ZappNumberField(
                        label = tr(Str.GPS_ROUTE_INTERVAL),
                        value = config.routeDwellSeconds,
                        onValueChange = { v ->
                            state.mutate { it.copy(gps = it.gps.copy(routeDwellSeconds = v ?: 0)) }
                        },
                        placeholder = "1"
                    )
                    ZappSettingRow(
                        title = tr(Str.GPS_ROUTE_LOOP),
                        checked = config.routeLoop,
                        onCheckedChange = { on ->
                            state.mutate { it.copy(gps = it.gps.copy(routeLoop = on)) }
                        }
                    )
                }
            }
        }

        // --- Журнал: свёрнут, пока его не откроют ---
        ZappExpandableCard(
            title = tr(Str.ENGINE_LOG),
            badge = if (gpsLog.isEmpty()) null else gpsLog.size.toString()
        ) {
            if (gpsLog.isEmpty()) {
                Text(
                    text = tr(Str.ENGINE_LOG_EMPTY),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            } else {
                // Последние строки ближе к низу карточки: свежее событие
                // важнее старого, и глазу не нужно скроллить назад.
                gpsLog.takeLast(LOG_VISIBLE).forEach { event ->
                    val suffix = if (event.arg.isEmpty()) "" else " ${event.arg}"
                    Text(
                        text = tr(event.text) + suffix,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }
}

/** Метка режима на языке интерфейса. */
@Composable
private fun GpsMode.label(): String {
    val lang = AppLangState.current
    return if (lang == xyz.azraellab.zapp.core.AppLang.RU) ru else en
}

/** Состояние подмены -- ключ строки состояния. */
@Composable
private fun AppState.gpsStateLabel(): Str = when (gpsEngine.state.collectAsState().value) {
    GpsState.OFF -> Str.GPS_STATE_OFF
    GpsState.STARTING -> Str.GPS_STATE_STARTING
    GpsState.ACTIVE -> Str.GPS_STATE_ACTIVE
    GpsState.ERROR -> Str.GPS_STATE_ERROR
}

/** "55.75580, 37.61730 (Москва, Россия)" -- координаты с подписью места. */
private fun formatFix(latitude: Double, longitude: Double, place: String): String {
    val coords = String.format(Locale.US, "%.5f, %.5f", latitude, longitude)
    return if (place.isEmpty()) coords else "$coords ($place)"
}

private const val LOG_VISIBLE = 12
