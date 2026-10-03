package xyz.azraellab.zapp.core.gps

import android.content.Context
import android.app.AppOpsManager
import android.location.Location
import android.location.LocationManager
import android.os.SystemClock
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import xyz.azraellab.zapp.core.AndroidCtx
import xyz.azraellab.zapp.core.GpsConfig
import xyz.azraellab.zapp.core.GpsTarget
import xyz.azraellab.zapp.core.Str
import xyz.azraellab.zapp.core.geo.GeoPoint
import xyz.azraellab.zapp.core.geo.geolocate
import kotlin.math.cos
import kotlin.random.Random

actual fun createGpsEngine(): GpsEngine = AndroidGpsEngine()

/**
 * Подмена координат на Android.
 *
 * Механизм системный, без root: приложение, отмеченное в настройках
 * разработчика как «для имитации местоположения», регистрирует
 * тест-провайдер на имя `gps` и каждую секунду кладёт в него свежую
 * точку. Система и приложения получают её вместо настоящих спутников.
 *
 * Три режима:
 *  - фиксированная точка -- просто конфиг;
 *  - маршрут -- чистая математика из [RouteProgress], здесь только такт;
 *  - координаты VPN -- точка ищется по адресу шлюза через гео-сервис
 *    и дальше ведёт себя как фиксированная.
 */
private class AndroidGpsEngine : BaseGpsEngine() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var loopJob: Job? = null
    private var locationManager: LocationManager? = null

    /** Последний конфиг: движок читает его на каждом такте. */
    private var config: GpsConfig? = null

    override val supported: Boolean
        get() = AndroidCtx.current != null

    override fun onEvent(event: GpsEvent) {
        // Дубль в logcat: журнал виден в UI, но если экран закрыт или
        // приложение падает, причина остаётся только здесь.
        android.util.Log.d(TAG, "${event.text.name} ${event.arg}".trim())
    }

    override fun start(config: GpsConfig, vpnGateway: String) {
        // Перезапуск: старый цикл снимается молча, статус задаст новый.
        stopLoop()
        this.config = config
        setState(GpsState.STARTING)
        log(GpsEvent(Str.GPS_LOG_STARTING, config.mode))

        loopJob = scope.launch {
            val target = resolveTarget(config, vpnGateway) ?: run {
                setState(GpsState.ERROR)
                return@launch
            }
            if (!installProvider()) {
                setState(GpsState.ERROR)
                return@launch
            }
            log(GpsEvent(Str.GPS_LOG_PROVIDER))
            setState(GpsState.ACTIVE)
            runLoop(target)
            // runLoop вернулся: кончился маршрут, упала система или нас остановили.
            if (isActive) {
                // Самостоятельный выход (маршрут без зацикливания) -- это стоп.
                stopInternal(notifyStopped = true)
            }
        }
    }

    override fun stop() {
        if (state.value == GpsState.OFF) return
        stopInternal(notifyStopped = true)
    }

    override fun applyConfig(config: GpsConfig) {
        this.config = config
    }

    // --- Целевая точка ---

    /** Разрешает, что именно подсовывать: VPN-точку, фикс или маршрут. */
    private suspend fun resolveTarget(config: GpsConfig, vpnGateway: String): ResolvedTarget? =
        when (val target = config.target) {
            is GpsTarget.Fixed -> ResolvedTarget.Fixed(target.latitude, target.longitude, "")

            is GpsTarget.Route -> {
                val points = target.points
                if (points.isEmpty()) {
                    log(GpsEvent(Str.GPS_LOG_ROUTE_EMPTY))
                    null
                } else {
                    ResolvedTarget.Route(points)
                }
            }

            is GpsTarget.VpnLocation -> {
                val address = vpnGateway.trim()
                if (address.isEmpty()) {
                    log(GpsEvent(Str.GPS_LOG_NO_GATEWAY))
                    null
                } else {
                    val geo: GeoPoint? = geolocate(address)
                    if (geo == null || !geo.valid) {
                        log(GpsEvent(Str.GPS_LOG_RESOLVE_FAIL))
                        null
                    } else {
                        val place = listOf(geo.city, geo.country)
                            .filter { it.isNotBlank() }
                            .joinToString(", ")
                        log(GpsEvent(Str.GPS_LOG_RESOLVED, formatPoint(geo.latitude, geo.longitude, place)))
                        ResolvedTarget.Fixed(geo.latitude, geo.longitude, place)
                    }
                }
            }

            GpsTarget.Off -> null
        }

    // --- Провайдер ---

    /** Настраивает тест-провайдер. false, если разрешения или сервиса нет. */
    private fun installProvider(): Boolean {
        val context = AndroidCtx.current ?: return false

        // Проверка через AppOps, а не через разрешение: доступ к имитации
        // выдаётся флагом «приложение для имитации местоположения», и сам
        // ACCESS_MOCK_LOCATION приложение не получает никогда -- проверка
        // по нему врала бы всегда.
        val mockAllowed = runCatching {
            val appOps = context.getSystemService(Context.APP_OPS_SERVICE) as AppOpsManager
            appOps.checkOpNoThrow(
                AppOpsManager.OPSTR_MOCK_LOCATION,
                android.os.Process.myUid(),
                context.packageName
            ) == AppOpsManager.MODE_ALLOWED
        }.getOrDefault(false)
        if (!mockAllowed) {
            log(GpsEvent(Str.GPS_LOG_NO_MOCK))
            return false
        }

        val manager = context.getSystemService(Context.LOCATION_SERVICE) as? LocationManager
            ?: return false

        // Провайдер мог остаться от прошлого запуска -- это не ошибка.
        runCatching {
            manager.addTestProvider(
                LocationManager.GPS_PROVIDER,
                false, false, false, false,
                true, true, true,
                android.location.Criteria.POWER_HIGH,
                android.location.Criteria.ACCURACY_FINE
            )
        }
        runCatching { manager.setTestProviderEnabled(LocationManager.GPS_PROVIDER, true) }

        locationManager = manager
        return true
    }

    /** Кладёт точку в провайдер. false, если система её не приняла. */
    private fun publish(fix: GpsFix, cfg: GpsConfig): Boolean {
        val manager = locationManager ?: return false
        return runCatching {
            val location = Location(LocationManager.GPS_PROVIDER).apply {
                latitude = fix.latitude
                longitude = fix.longitude
                altitude = cfg.altitudeMeters
                accuracy = fix.accuracyMeters
                speed = cfg.speedMps.toFloat()
                bearing = 0f
                time = System.currentTimeMillis()
                elapsedRealtimeNanos = SystemClock.elapsedRealtimeNanos()
            }
            manager.setTestProviderLocation(LocationManager.GPS_PROVIDER, location)
        }.isSuccess
    }

    // --- Тактовый цикл ---

    private suspend fun CoroutineScope.runLoop(target: ResolvedTarget) {
        // Маршрут держит свой прогресс вне конфига: перезапуск маршрута
        // -- событие, а не следствие смены точности в настройках.
        var walker: RouteProgress? = (target as? ResolvedTarget.Route)?.let {
            RouteProgress(it.points, config?.routeLoop == true, config?.routeDwellSeconds ?: 0)
        }
        var baseLatitude = (target as? ResolvedTarget.Fixed)?.latitude
        var baseLongitude = (target as? ResolvedTarget.Fixed)?.longitude

        while (isActive) {
            val cfg = config ?: return

            val point: RoutePointCoords = when {
                walker != null -> {
                    val p = walker.step(TICK_SECONDS, cfg.speedMps)
                    if (p == null) {
                        log(GpsEvent(Str.GPS_LOG_ROUTE_DONE))
                        return
                    }
                    RoutePointCoords(p.latitude, p.longitude, null)
                }
                baseLatitude != null && baseLongitude != null -> {
                    val jitter = jitterMeters(cfg.positionJitterMeters)
                    RoutePointCoords(baseLatitude!!, baseLongitude!!, jitter)
                }
                else -> return
            }

            val jittered = applyJitter(point.latitude, point.longitude, point.jitterMeters ?: 0.0)
            val fix = GpsFix(
                latitude = jittered.first,
                longitude = jittered.second,
                accuracyMeters = cfg.accuracyMeters.coerceAtLeast(1.0).toFloat(),
                place = (target as? ResolvedTarget.Fixed)?.place ?: ""
            )

            if (!publish(fix, cfg)) {
                // Система не приняла точку: обычно это снятое разрешение
                // на имитацию. Причина видна в журнале, состояние -- ошибка.
                log(GpsEvent(Str.GPS_LOG_PROVIDER_ERROR))
                setState(GpsState.ERROR)
                return
            }
            setFix(fix)
            delay(TICK_MS)
        }
    }

    // --- Остановка ---

    private fun stopLoop() {
        loopJob?.cancel()
        loopJob = null
    }

    private fun stopInternal(notifyStopped: Boolean) {
        stopLoop()
        val manager = locationManager
        if (manager != null) {
            // Снимаем тест-провайдер, чтобы система вернулась к настоящим
            // спутникам; оба вызова возможных исключений не критичны --
            // провайдера может и не оказаться, если подмена не поднималась.
            runCatching { manager.setTestProviderEnabled(LocationManager.GPS_PROVIDER, false) }
            runCatching { manager.removeTestProvider(LocationManager.GPS_PROVIDER) }
        }
        locationManager = null
        config = null
        setFix(null)
        setState(GpsState.OFF)
        if (notifyStopped) log(GpsEvent(Str.GPS_LOG_STOPPED))
    }

    // --- Мелкая математика ---

    /** Случайное смещение в метрах по обеим осям. */
    private fun jitterMeters(range: Double): Double =
        if (range <= 0.0) 0.0 else Random.nextDouble(-range, range)

    /**
     * Применяет смещение к координатам.
     *
     * Долгота пересчитывается через косинус широты: один градус долготы
     * на экваторе -- 111 км, а на 60-й параллели -- вдвое меньше, и без
     * этого поправка растянула бы разброс вдоль параллели.
     */
    private fun applyJitter(latitude: Double, longitude: Double, jitterMeters: Double): Pair<Double, Double> {
        if (jitterMeters == 0.0) return latitude to longitude
        val lat = latitude + jitterMeters / METERS_PER_DEGREE
        val cosLat = cos(Math.toRadians(latitude)).coerceAtLeast(0.01)
        val lon = longitude + jitterMeters / (METERS_PER_DEGREE * cosLat)
        return lat to lon
    }

    private data class RoutePointCoords(val latitude: Double, val longitude: Double, val jitterMeters: Double?)

    private sealed interface ResolvedTarget {
        data class Fixed(val latitude: Double, val longitude: Double, val place: String) : ResolvedTarget
        data class Route(val points: List<xyz.azraellab.zapp.core.RoutePoint>) : ResolvedTarget
    }

    private companion object {
        const val TAG = "ZAPP-GPS"
        const val TICK_MS = 1000L
        const val TICK_SECONDS = 1.0
        const val METERS_PER_DEGREE = 111_320.0
    }
}

/** "55.7558, 37.6173 (Москва, Россия)" -- строка для журнала и уведомления. */
private fun formatPoint(latitude: Double, longitude: Double, place: String): String {
    // Locale.US: иначе в русской локали запятая станет и разделителем
    // градусов, и разделителем частей координат.
    val coords = String.format(java.util.Locale.US, "%.5f, %.5f", latitude, longitude)
    return if (place.isEmpty()) coords else "$coords ($place)"
}
