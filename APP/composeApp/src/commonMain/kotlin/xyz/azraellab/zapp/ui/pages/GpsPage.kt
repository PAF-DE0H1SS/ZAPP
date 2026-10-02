package xyz.azraellab.zapp.ui.pages

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import xyz.azraellab.zapp.core.AppState
import xyz.azraellab.zapp.core.GpsMode
import xyz.azraellab.zapp.core.Str
import xyz.azraellab.zapp.ui.components.Choice
import xyz.azraellab.zapp.ui.components.ZappCard
import xyz.azraellab.zapp.ui.components.ZappChoiceRow
import xyz.azraellab.zapp.ui.components.ZappEmptyState
import xyz.azraellab.zapp.ui.components.ZappNumberField
import xyz.azraellab.zapp.ui.components.ZappSettingRow
import xyz.azraellab.zapp.ui.components.ZappTextField
import xyz.azraellab.zapp.ui.theme.AzraelSpace
import xyz.azraellab.zapp.ui.tr

/**
 * Страница GPS.
 *
 * На десктопе спуфинг координат не поддерживается -- `PlatformCaps.gpsSpoofingSupported=false`.
 * Вместо заглушки «недоступно» с кнопкой показываем пустое состояние без действия:
 * раздел просто неактивен концептуально, и UI не предлагает невозможного.
 */
@Composable
fun GpsPage(state: AppState) {
    val config = state.config.gps

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

        ZappCard {
            ZappSettingRow(
                title = tr(Str.TAB_GPS),
                checked = config.enabled,
                onCheckedChange = { on ->
                    state.mutate { it.copy(gps = it.gps.copy(enabled = on)) }
                }
            )
        }

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
            }
        }

        PageGroup(tr(Str.GPS_LOCATION)) {
            ZappCard {
                ZappNumberField(
                    label = tr(Str.GPS_LATITUDE),
                    value = config.latitude.toLong().takeIf { it.toDouble() == config.latitude }?.toInt(),
                    onValueChange = { v ->
                        state.mutate { it.copy(gps = it.gps.copy(latitude = v?.toDouble() ?: 0.0)) }
                    },
                    placeholder = "55.7558"
                )
                ZappNumberField(
                    label = tr(Str.GPS_LONGITUDE),
                    value = config.longitude.toLong().takeIf { it.toDouble() == config.longitude }?.toInt(),
                    onValueChange = { v ->
                        state.mutate { it.copy(gps = it.gps.copy(longitude = v?.toDouble() ?: 0.0)) }
                    },
                    placeholder = "37.6173"
                )
                ZappNumberField(
                    label = tr(Str.GPS_ACCURACY),
                    value = config.accuracyMeters.toLong().toInt(),
                    onValueChange = { v ->
                        state.mutate { it.copy(gps = it.gps.copy(accuracyMeters = v?.toDouble() ?: 5.0)) }
                    },
                    placeholder = "10"
                )
                ZappNumberField(
                    label = tr(Str.GPS_ALTITUDE),
                    value = config.altitudeMeters.toLong().toInt(),
                    onValueChange = { v ->
                        state.mutate { it.copy(gps = it.gps.copy(altitudeMeters = v?.toDouble() ?: 0.0)) }
                    }
                )
                ZappNumberField(
                    label = tr(Str.GPS_SPEED),
                    value = config.speedMps.toLong().toInt(),
                    onValueChange = { v ->
                        state.mutate { it.copy(gps = it.gps.copy(speedMps = v?.toDouble() ?: 0.0)) }
                    }
                )
                ZappNumberField(
                    label = tr(Str.GPS_HEADING),
                    value = null,
                    onValueChange = {}
                )
            }
        }

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

        PageGroup(tr(Str.SETTINGS_SYSTEM)) {
            ZappCard {
                ZappSettingRow(
                    title = tr(Str.GPS_FAKE_PROVIDER),
                    checked = false,
                    onCheckedChange = {}
                )
                ZappSettingRow(
                    title = tr(Str.GPS_ALLOW_MOCK),
                    checked = false,
                    onCheckedChange = {}
                )
                ZappTextField(
                    label = tr(Str.GPS_APP_WHITELIST),
                    value = config.apps,
                    onValueChange = { v ->
                        state.mutate { it.copy(gps = it.gps.copy(apps = v)) }
                    },
                    placeholder = "com.example.app"
                )
                ZappTextField(
                    label = tr(Str.GPS_MOCK_APP),
                    value = "",
                    onValueChange = {}
                )
            }
        }
    }
}

/** Метка режима GPS. */
@Composable
private fun GpsMode.label(): String = code
