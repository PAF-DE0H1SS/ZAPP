package xyz.azraellab.zapp.ui.pages

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.atan2
import xyz.azraellab.zapp.core.AppLang
import xyz.azraellab.zapp.core.AppState
import xyz.azraellab.zapp.core.GpsMode
import xyz.azraellab.zapp.core.GpsRouteCodec
import xyz.azraellab.zapp.core.SavedPoint
import xyz.azraellab.zapp.core.Str
import xyz.azraellab.zapp.core.geo.Place
import xyz.azraellab.zapp.core.geo.TransportProfile
import xyz.azraellab.zapp.core.geo.osrmRoute
import xyz.azraellab.zapp.core.geo.photonSearch
import xyz.azraellab.zapp.core.gps.GpsState
import xyz.azraellab.zapp.core.gps.RouteEta
import xyz.azraellab.zapp.core.log.isError
import xyz.azraellab.zapp.core.log.platformNowMs
import xyz.azraellab.zapp.ui.AppLangState
import xyz.azraellab.zapp.ui.components.ComponentColors.ButtonRole
import xyz.azraellab.zapp.ui.components.Choice
import xyz.azraellab.zapp.ui.components.RouteMiniMap
import xyz.azraellab.zapp.ui.components.ZappButton
import xyz.azraellab.zapp.ui.components.ZappButtonRow
import xyz.azraellab.zapp.ui.components.ZappCard
import xyz.azraellab.zapp.ui.components.ZappChoiceRow
import xyz.azraellab.zapp.ui.components.ZappEmptyState
import xyz.azraellab.zapp.ui.components.ZappDecimalField
import xyz.azraellab.zapp.ui.components.ZappExpandableCard
import xyz.azraellab.zapp.ui.components.ZappEmptyCard
import xyz.azraellab.zapp.ui.components.ZappListRow
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

    // Таймер сессии: отсчёт от первой активации до остановки подмены.
    // Эффект перезапускается на каждой смене состояния: переход
    // STARTING -> ACTIVE отсчёт не обрывает, выключение -- сбрасывает.
    var sessionStart by remember { mutableStateOf(0L) }
    var sessionNow by remember { mutableStateOf(0L) }
    LaunchedEffect(gpsState) {
        val active = gpsState == GpsState.ACTIVE || gpsState == GpsState.STARTING
        if (active) {
            if (sessionStart == 0L) {
                sessionStart = platformNowMs()
                sessionNow = sessionStart
            }
            while (true) {
                delay(1000)
                sessionNow = platformNowMs()
            }
        } else {
            sessionStart = 0L
            sessionNow = 0L
        }
    }
    val sessionLabel = if (sessionStart != 0L && sessionNow >= sessionStart) {
        val seconds = (sessionNow - sessionStart) / 1000
        String.format(Locale.US, "%02d:%02d:%02d", seconds / 3600, (seconds % 3600) / 60, seconds % 60)
    } else {
        null
    }

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
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(AzraelSpace.xs),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        GpsStatusDot(gpsState)
                        Text(
                            text = tr(state.gpsStateLabel()),
                            style = MaterialTheme.typography.titleMedium,
                            color = when (gpsState) {
                                GpsState.ACTIVE -> MaterialTheme.colorScheme.primary
                                GpsState.ERROR -> MaterialTheme.colorScheme.error
                                else -> MaterialTheme.colorScheme.onSurface
                            }
                        )
                        // Таймер сессии: сколько подмена работает подряд.
                        // Слева от него -- пульсирующая точка: работает не
                        // только строка, но и сама подмена.
                        sessionLabel?.let { label ->
                            Text(
                                text = label,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                    gpsFix?.let { fix ->
                        Text(
                            text = formatFix(fix.latitude, fix.longitude, fix.place),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    // Причина сбоя -- под статусом: «Ошибка» без строки под
                    // ним ничего не объясняет, а причину приложение знает.
                    // Строка -- диагностика: время · компонент · причина.
                    if (gpsState == GpsState.ERROR) {
                        gpsLog.gpsDiagnosisReason("gps")?.let { reason ->
                            Text(
                                text = reason,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.error
                            )
                        }
                    }
                }
                val running = gpsState == GpsState.ACTIVE || gpsState == GpsState.STARTING
                if (running) {
                    ZappButton(
                        text = tr(Str.COMMON_STOP),
                        role = ButtonRole.DANGER,
                        onClick = { state.stopGps() },
                        path = "gps.stop"
                    )
                } else {
                    ZappButton(
                        text = tr(Str.COMMON_START),
                        onClick = { state.startGps() },
                        path = "gps.start"
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
                    },
                    path = "gps.mode"
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
                    PlaceSearch(state)
                    ZappDecimalField(
                        label = tr(Str.GPS_LATITUDE),
                        value = config.latitude,
                        onValueChange = { v ->
                            state.mutate { it.copy(gps = it.gps.copy(latitude = v ?: 0.0)) }
                        },
                        placeholder = "55.7558",
                        path = "gps.latitude"
                    )
                    ZappDecimalField(
                        label = tr(Str.GPS_LONGITUDE),
                        value = config.longitude,
                        onValueChange = { v ->
                            state.mutate { it.copy(gps = it.gps.copy(longitude = v ?: 0.0)) }
                        },
                        placeholder = "37.6173",
                        path = "gps.longitude"
                    )
                    ZappNumberField(
                        label = tr(Str.GPS_ACCURACY),
                        value = config.accuracyMeters.toLong().toInt(),
                        onValueChange = { v ->
                            state.mutate { it.copy(gps = it.gps.copy(accuracyMeters = (v ?: 5).toDouble())) }
                        },
                        placeholder = "10",
                        path = "gps.accuracy"
                    )
                    // Высота тоже бывает дробной и отрицательной: берег Мёртвого
                    // моря -- минус сорок с лишним метров.
                    ZappDecimalField(
                        label = tr(Str.GPS_ALTITUDE),
                        value = config.altitudeMeters,
                        onValueChange = { v ->
                            state.mutate { it.copy(gps = it.gps.copy(altitudeMeters = v ?: 0.0)) }
                        },
                        placeholder = "0",
                        path = "gps.altitude"
                    )
                    ZappButtonRow {
                        ZappButton(
                            text = tr(Str.GPS_ADD_FAVORITE),
                            role = ButtonRole.SECONDARY,
                            onClick = {
                                state.addGpsFavorite(
                                    SavedPoint(
                                        name = gpsFix?.place.orEmpty(),
                                        latitude = config.latitude,
                                        longitude = config.longitude
                                    )
                                )
                            },
                            path = "gps.favorite.add"
                        )
                    }
                }
            }
            FavoritesCard(state)
        }

        // --- Джойстик: ручное движение ---
        if (GpsMode.of(config.mode) == GpsMode.JOYSTICK) {
            PageGroup(tr(Str.GPS_MODE)) {
                ZappCard {
                    Text(
                        text = tr(Str.GPS_JOYSTICK_HINT),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    JoystickPad(
                        headingDegrees = config.headingDegrees,
                        speedMps = config.speedMps,
                        onVector = { heading, speed ->
                            state.mutate {
                                it.copy(
                                    gps = it.gps.copy(
                                        headingDegrees = heading,
                                        speedMps = speed
                                    )
                                )
                            }
                        }
                    )
                }
            }
        }

        // --- Маршрут: только для режима «маршрут» ---
        if (GpsMode.of(config.mode) == GpsMode.ROUTE) {
            PageGroup(tr(Str.GPS_ROUTE)) {
                ZappCard {
                    // Профиль движения решает, по каким дорогам строить и
                    // с какой скоростью: пеший маршрут по тротуарам,
                    // машинный -- по развязкам.
                    ZappChoiceRow(
                        label = tr(Str.GPS_TRANSPORT),
                        options = TransportProfile.entries.map {
                            Choice(it.name, if (AppLangState.current == AppLang.RU) it.ru else it.en)
                        },
                        selected = config.routeProfile,
                        onSelect = { code ->
                            // Профиль не только определяет дороги: он же
                            // задаёт скорость. Ручную скорость
                            // перекрываем осознанно -- иначе «пешком» ехал
                            // бы на машинной.
                            state.mutate {
                                it.copy(
                                    gps = it.gps.copy(
                                        routeProfile = code,
                                        speedMps = TransportProfile.of(code).defaultSpeedMps
                                    )
                                )
                            }
                        },
                        path = "gps.route_profile"
                    )
                    ZappTextField(
                        label = tr(Str.GPS_ROUTE_POINTS),
                        value = config.route,
                        onValueChange = { v ->
                            state.mutate { it.copy(gps = it.gps.copy(route = v)) }
                        },
                        supportingText = tr(Str.GPS_ROUTE_FORMAT),
                        singleLine = false,
                        path = "gps.route"
                    )
                    ZappNumberField(
                        label = tr(Str.GPS_ROUTE_SPEED),
                        value = kotlin.math.round(config.speedMps).toInt(),
                        onValueChange = { v ->
                            state.mutate { it.copy(gps = it.gps.copy(speedMps = (v ?: 0).toDouble())) }
                        },
                        placeholder = "20",
                        path = "gps.route_speed"
                    )
                    ZappNumberField(
                        label = tr(Str.GPS_ROUTE_INTERVAL),
                        value = config.routeDwellSeconds,
                        onValueChange = { v ->
                            state.mutate { it.copy(gps = it.gps.copy(routeDwellSeconds = v ?: 0)) }
                        },
                        placeholder = "1",
                        path = "gps.route_interval"
                    )
                    ZappSettingRow(
                        title = tr(Str.GPS_ROUTE_LOOP),
                        checked = config.routeLoop,
                        onCheckedChange = { on ->
                            state.mutate { it.copy(gps = it.gps.copy(routeLoop = on)) }
                        },
                        path = "gps.route_loop"
                    )
                    RouteBuilder(state)
                    // Мини-карта: схема маршрута прямо в карточке -- видно,
                    // где точка сейчас и сколько осталось. Рисуется только
                    // когда есть что рисовать.
                    val routePoints = remember(config.route) { GpsRouteCodec.parse(config.route) }
                    if (routePoints.size >= 2) {
                        RouteMiniMap(
                            route = routePoints,
                            current = gpsFix,
                            modifier = Modifier.padding(top = AzraelSpace.sm)
                        )
                    }
                }
            }
        }

        // --- Журнал: свёрнут, пока его не откроют ---
        ZappExpandableCard(
            title = tr(Str.ENGINE_LOG),
            badge = if (gpsLog.isEmpty()) null else gpsLog.size.toString(),
            path = "gps.log.expand"
        ) {
            if (gpsLog.isEmpty()) {
                // Пусто -- не голая строка: иллюстрация объясняет, что
                // журнал ещё не заполнен.
                ZappEmptyCard(title = tr(Str.ENGINE_LOG_EMPTY))
            } else {
                // Последние строки ближе к низу карточки: свежее событие
                // важнее старого, и глазу не нужно скроллить назад.
                gpsLog.takeLast(LOG_VISIBLE).forEach { event ->
                    Text(
                        text = event.rendered(),
                        style = MaterialTheme.typography.bodySmall,
                        // Сбой -- цветом: причину видно, не разворачивая
                        // журнал и не дочитывая его до конца.
                        color = if (event.isError()) {
                            MaterialTheme.colorScheme.error
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        }
                    )
                }
            }
        }
    }
}

/**
 * Избранное и история подмены.
 *
 * Два списка в одном месте: избранное заполняется рукой, история --
 * сама, когда подмена показала первую точку. Тап по строке кладёт
 * координаты в поля режима «точка»: список нужен, чтобы не вводить
 * координаты заново, а не только чтобы смотреть.
 */
@Composable
private fun FavoritesCard(state: AppState) {
    val config = state.config.gps

    PageGroup(tr(Str.GPS_FAVORITES)) {
        ZappExpandableCard(
            title = tr(Str.GPS_FAVORITES),
            badge = if (config.favorites.isEmpty()) null else config.favorites.size.toString(),
            path = "gps.favorites.expand"
        ) {
            if (config.favorites.isEmpty()) {
                ZappEmptyCard(title = tr(Str.GPS_FAVORITES_EMPTY))
            } else {
                config.favorites.forEach { point ->
                    SavedRow(
                        point = point,
                        onPick = { pickGpsPoint(state, point) },
                        onDelete = { state.removeGpsFavorite(point) },
                        path = "gps.favorites.row"
                    )
                }
            }
        }
        ZappExpandableCard(
            title = tr(Str.GPS_SPOOF_HISTORY),
            badge = if (config.history.isEmpty()) null else config.history.size.toString(),
            path = "gps.history.expand"
        ) {
            if (config.history.isEmpty()) {
                ZappEmptyCard(title = tr(Str.GPS_HISTORY_EMPTY))
            } else {
                config.history.forEach { point ->
                    SavedRow(
                        point = point,
                        onPick = { pickGpsPoint(state, point) },
                        onDelete = { state.removeGpsHistory(point) },
                        path = "gps.history.row"
                    )
                }
            }
        }
    }
}

/** Строка сохранённой точки: тап -- подставить, крестик -- забыть. */
@Composable
private fun SavedRow(
    point: SavedPoint,
    onPick: () -> Unit,
    onDelete: () -> Unit,
    path: String
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onPick)
            .padding(vertical = AzraelSpace.xs),
        horizontalArrangement = Arrangement.spacedBy(AzraelSpace.md),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(text = point.label(), style = MaterialTheme.typography.bodyLarge)
            Text(
                text = String.format(Locale.US, "%.5f, %.5f", point.latitude, point.longitude),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        ZappButton(
            text = tr(Str.COMMON_DELETE),
            role = ButtonRole.GHOST,
            onClick = onDelete,
            path = "$path.del"
        )
    }
}

/** Точка из списка становится координатами режима «точка». */
private fun pickGpsPoint(state: AppState, point: SavedPoint) {
    state.mutate { cfg ->
        cfg.copy(
            gps = cfg.gps.copy(
                mode = GpsMode.FIXED.code,
                latitude = point.latitude,
                longitude = point.longitude
            )
        )
    }
}

/**
 * Джойстик: крутится вектор от центра к точке под пальцем.
 *
 * Угол -- курс, длина -- скорость: один жест задаёт и то и другое, а
 * отпущенная точка возвращается в центр и движение останавливается.
 * Ничего не хранит сам: текущий вектор живёт в конфиге, здесь только
 * рисование и жест.
 */
@Composable
private fun JoystickPad(
    headingDegrees: Double,
    speedMps: Double,
    onVector: (heading: Double, speed: Double) -> Unit
) {
    // Собственное состояние рисунка: конфиг -- истина, а смещение точки
    // в течение жеста -- локальное, чтобы не писать настройки 60 раз в
    // секунду.
    var drag by remember { mutableStateOf(Offset.Zero) }
    val maxSpeed = JOYSTICK_MAX_SPEED_MPS
    val base = MaterialTheme.colorScheme.surfaceContainerHighest
    val line = MaterialTheme.colorScheme.outline
    val knobColor = MaterialTheme.colorScheme.primary

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(JOYSTICK_PAD_DP)
            .pointerInput(Unit) {
                detectDragGestures(
                    onDragEnd = {
                        drag = Offset.Zero
                        onVector(headingDegrees, 0.0)
                    },
                    onDragCancel = {
                        drag = Offset.Zero
                        onVector(headingDegrees, 0.0)
                    },
                    onDrag = { change, amount ->
                        change.consume()
                        drag = (drag + amount).limitTo(radius = minOf(size.width, size.height).toFloat() / 2f)
                        val radius = minOf(size.width, size.height).toFloat() / 2f
                        val distance = drag.getDistance()
                        // Курс: 0° -- север (вверх), по часовой.
                        val angle = Math.toDegrees(
                            atan2(drag.x.toDouble(), -drag.y.toDouble())
                        ).let { if (it < 0) it + 360.0 else it }
                        onVector(angle, (distance / radius) * maxSpeed)
                    }
                )
            },
        contentAlignment = Alignment.Center
    ) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            val radius = minOf(size.width, size.height).toFloat() / 2f
            drawCircle(
                color = base,
                radius = radius
            )
            drawCircle(
                color = line,
                radius = radius,
                style = Stroke(width = 2f)
            )
            // Вектор от центра к точке: направление видно, даже когда
            // скорость почти нулевая.
            val knob = center + drag
            drawLine(
                color = line,
                start = center,
                end = knob,
                strokeWidth = 4f
            )
            drawCircle(color = knobColor, radius = 18f, center = knob)
        }
        // Подпись текущего вектора: цифры нужны, чтобы повторить жест
        // точно, а не на глаз.
        Text(
            text = String.format(
                Locale.US,
                "%.0f° · %.1f m/s",
                headingDegrees,
                speedMps
            ),
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

/** Ограничение точки радиусом пада: дальше пальца вектор не растёт. */
private fun Offset.limitTo(radius: Float): Offset {
    val distance = getDistance()
    if (distance <= radius || distance == 0f) return this
    val scale = radius / distance
    return Offset(x * scale, y * scale)
}

/** Скорость на краю пада, м/с: ~72 км/ч, быстрее вручную не нужно. */
private const val JOYSTICK_MAX_SPEED_MPS = 20.0

/** Размер пада: под палец, но не под всю карточку. */
private val JOYSTICK_PAD_DP = 220.dp

/**
 * Поиск места по названию.
 *
 * Ввод «Кремль» вместо координат: Photon отдаёт список, и выбор строки
 * кладёт широту/долготу в поля внизу. Сеть здесь трёхсекундная, поэтому
 * у запроса есть состояние «ищем» и отдельные строки на «не нашлось» и
 * «нет сети»: это разные вещи, и путать их в интерфейсе нельзя.
 */
@Composable
private fun PlaceSearch(state: AppState) {
    val scope = rememberCoroutineScope()
    var query by remember { mutableStateOf("") }
    var results by remember { mutableStateOf<List<Place>?>(null) }
    var busy by remember { mutableStateOf(false) }

    ZappTextField(
        label = tr(Str.GPS_PLACE_SEARCH),
        value = query,
        onValueChange = { query = it },
        placeholder = "Кремль",
        path = "gps.search.query"
    )
    ZappButtonRow {
        ZappButton(
            text = tr(Str.COMMON_SEARCH),
            enabled = query.isNotBlank() && !busy,
            onClick = {
                val q = query
                scope.launch {
                    busy = true
                    results = photonSearch(q)
                    busy = false
                }
            },
            path = "gps.search.go"
        )
    }
    when {
        busy -> Text(
            text = tr(Str.COMMON_LOADING),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        results == null -> Text(
            text = tr(Str.GPS_PLACE_ERROR),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.error
        )
        results!!.isEmpty() -> Text(
            text = tr(Str.COMMON_NOTHING_FOUND),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        else -> results!!.forEach { place ->
            // Выбор строки -- сразу координаты: поле вручную заполнять
            // больше незачем, оно остаётся для тех, кто знает их наизусть.
            ZappListRow(
                title = place.label,
                subtitle = place.details.ifEmpty { null },
                modifier = Modifier.clickable {
                    state.mutate {
                        it.copy(
                            gps = it.gps.copy(
                                latitude = place.latitude,
                                longitude = place.longitude
                            )
                        )
                    }
                    query = place.label
                    results = emptyList()
                },
                path = "gps.search.row"
            )
        }
    }
}

/**
 * Построение маршрута по дорогам.
 *
 * Точки из текстового поля уходят в OSRM, и обратно приходит ломаная по
 * дорогам с расстоянием и временем: рисовать маршрут по прямой между
 * точками -- врать про путь. Сбой сети или отсутствие дороги не трогают
 * введённое: старые точки остаются, показывается своя строка.
 */
@Composable
private fun RouteBuilder(state: AppState) {
    val scope = rememberCoroutineScope()
    val config = state.config.gps
    var busy by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<Str?>(null) }
    var summary by remember { mutableStateOf("") }
    val points = remember(config.route) { GpsRouteCodec.parse(config.route) }
    // Шаблон берётся здесь, а не в корутине: tr() -- composable и вне
    // композиции его звать нельзя.
    val summaryFormat = tr(Str.GPS_ROUTE_SUMMARY)

    ZappButtonRow {
        ZappButton(
            text = tr(Str.GPS_ROUTE_BUILD),
            enabled = !busy,
            onClick = {
                scope.launch {
                    busy = true
                    message = null
                    if (points.size < 2) {
                        message = Str.GPS_ROUTE_TOO_SHORT
                    } else {
                        val built = osrmRoute(TransportProfile.of(config.routeProfile), points)
                        if (built == null) {
                            message = Str.GPS_ROUTE_FAILED
                        } else {
                            // Плотная геометрия OSRM -- сотни точек: для
                            // подмены хватает сотни, и поле маршрута остаётся
                            // читаемым.
                            val sampled = RouteEta.sampleEvenly(built.points, MAX_ROUTE_POINTS)
                            state.mutate {
                                it.copy(gps = it.gps.copy(route = GpsRouteCodec.write(sampled)))
                            }
                            summary = summaryFormat.format(
                                RouteEta.formatDistance(built.distanceMeters),
                                RouteEta.formatDuration(built.durationSeconds)
                            )
                        }
                    }
                    busy = false
                }
            },
            path = "gps.route.build"
        )
    }
    if (busy) {
        Text(
            text = tr(Str.COMMON_LOADING),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
    message?.let { key ->
        Text(
            text = tr(key),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.error
        )
    }
    if (summary.isNotEmpty()) {
        Text(
            text = summary,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.primary
        )
    }
}

/** Сколько точек ломаной остаётся после построения маршрута. */
private const val MAX_ROUTE_POINTS = 120

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

/**
 * Точка состояния в статусной строке.
 *
 * Активная подмена пульсирует (живой сигнал), ошибка -- горит
 * ровно, выключено -- нейтральный кружок без анимации.
 */
@Composable
private fun GpsStatusDot(state: GpsState) {
    val color = when (state) {
        GpsState.ACTIVE, GpsState.STARTING -> MaterialTheme.colorScheme.primary
        GpsState.ERROR -> MaterialTheme.colorScheme.error
        GpsState.OFF -> MaterialTheme.colorScheme.outline
    }
    val pulse = if (state == GpsState.ACTIVE || state == GpsState.STARTING) {
        rememberInfiniteTransition(label = "gpsDot").animateFloat(
            initialValue = 0.45f,
            targetValue = 1f,
            animationSpec = infiniteRepeatable(tween(900), RepeatMode.Reverse),
            label = "gpsDotAlpha"
        ).value
    } else {
        1f
    }
    Box(
        modifier = Modifier
            .size(8.dp)
            .background(color.copy(alpha = pulse), CircleShape)
    )
}

/** "55.75580, 37.61730 (Москва, Россия)" -- координаты с подписью места. */
private fun formatFix(latitude: Double, longitude: Double, place: String): String {
    val coords = String.format(Locale.US, "%.5f, %.5f", latitude, longitude)
    return if (place.isEmpty()) coords else "$coords ($place)"
}

private const val LOG_VISIBLE = 12
