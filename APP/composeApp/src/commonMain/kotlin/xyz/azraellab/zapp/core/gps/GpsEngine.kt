package xyz.azraellab.zapp.core.gps

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import xyz.azraellab.zapp.core.GpsConfig
import xyz.azraellab.zapp.core.RoutePoint
import xyz.azraellab.zapp.core.Str

/** Состояние подмены геолокации. */
enum class GpsState {
    /** Подмена выключена, система отдаёт настоящие координаты. */
    OFF,

    /** Идёт запуск: точка VPN ещё разрешается, провайдер настраивается. */
    STARTING,

    /** Тест-провайдер отдаёт подменные координаты. */
    ACTIVE,

    /** Запустить не удалось; причина -- в журнале. */
    ERROR
}

/** Точка, которую движок сейчас подсовывает системе. */
data class GpsFix(
    val latitude: Double,
    val longitude: Double,
    val accuracyMeters: Float,

    /** Подпись места -- город и страна из гео-сервиса; пусто для своих точек. */
    val place: String = ""
)

/**
 * Событие журнала.
 *
 * Строка не собирается в core сразу, потому что здесь нет языка: движок
 * отдаёт ключ [Str] и число, а перевод -- дело UI. Так журнал одинаково
 * живёт на трёх языках, как и весь остальной интерфейс.
 */
data class GpsEvent(val text: Str, val arg: String = "")

/**
 * Движок подмены геолокации.
 *
 * Общий код знает состояния, журнал и текущую точку; то, как поднимается
 * тест-провайдер, живёт в actual-реализации. Движок не просит разрешения
 * сам: это обязанность UI -- сначала получить доступ, потом звать [start].
 */
interface GpsEngine {

    /** Есть ли на платформе способ подменять координаты. */
    val supported: Boolean

    val state: StateFlow<GpsState>

    /** Журнал событий: новые в конце, ограничен буфером. */
    val log: StateFlow<List<GpsEvent>>

    /** Текущая подменяемая точка; null, когда подмены нет. */
    val fix: StateFlow<GpsFix?>

    /**
     * Запускает подмену.
     *
     * [vpnGateway] -- адрес шлюза активного профиля: нужен только режиму
     * «координаты VPN», остальным режимам он игнорируется.
     */
    fun start(config: GpsConfig, vpnGateway: String)

    /** Снимает подмену. Не бросает, даже если подмены и не было. */
    fun stop()

    /**
     * Применяет новые настройки на лету.
     *
     * Движок читает конфиг каждый тик, поэтому здесь достаточно сохранить
     * ссылку: пересобирать цикл ради смены точности -- лишняя работа.
     */
    fun applyConfig(config: GpsConfig)
}

/** Базовая реализация с буфером журнала и общим состоянием. */
abstract class BaseGpsEngine : GpsEngine {

    private val _state = MutableStateFlow(GpsState.OFF)
    override val state: StateFlow<GpsState> = _state.asStateFlow()

    private val _log = MutableStateFlow<List<GpsEvent>>(emptyList())
    override val log: StateFlow<List<GpsEvent>> = _log.asStateFlow()

    private val _fix = MutableStateFlow<GpsFix?>(null)
    override val fix: StateFlow<GpsFix?> = _fix.asStateFlow()

    protected fun setState(value: GpsState) {
        _state.value = value
    }

    protected fun log(event: GpsEvent) {
        val next = _log.value + event
        _log.value = if (next.size > LOG_LIMIT) next.takeLast(LOG_LIMIT) else next
        onEvent(event)
    }

    /**
     * Второй канал события мимо UI-журнала.
     *
     * Нужен платформе: Android дублирует события в logcat, чтобы причину
     * было видно, даже когда экран с журналом закрыт.
     */
    protected open fun onEvent(event: GpsEvent) = Unit

    protected fun setFix(value: GpsFix?) {
        _fix.value = value
    }

    private companion object {
        const val LOG_LIMIT = 200
    }
}

/**
 * Проигрывание маршрута.
 *
 * Отдельный перечисляемый класс, а не код внутри платформенного цикла:
 * вся арифметика -- чистая математика без Android и системы, поэтому её
 * можно проверить тестом прямо на десктопе.
 *
 * Работа по шагам: [step] зовётся раз в секунду и возвращает точку,
 * которая действует на этом шаге. null означает, что маршрут пройден
 * и без зацикливания дальше идти некуда.
 */
class RouteProgress(
    points: List<RoutePoint>,
    private val loop: Boolean,
    private val routeDwellSeconds: Int
) {
    /** Копия: внешние правки списка не должны двигать проигрывание. */
    private val points: List<RoutePoint> = points.toList()

    /** Текущая точка маршрута, из которой идёт движение. */
    var index: Int = 0
        private set

    /** Сколько ещё секунд стоять в текущей точке (пауза перед переходом). */
    var dwellLeft: Double = 0.0
        private set

    /** Пройдено метров в текущем сегменте. */
    private var traveledMeters = 0.0

    /** Текущая позиция: точка маршрута или интерполяция между ними. */
    var current: RoutePoint? = points.firstOrNull()
        private set

    init {
        // Старт стоит в первой точке на её паузе: «задержка перед переходом»
        // относится и к самому первому переходу, иначе маршрут начинал бы
        // движение сразу, минуя паузу, объявленную для точки старта.
        val first = points.firstOrNull()
        if (first != null) {
            dwellLeft = first.dwellSeconds.takeIf { it > 0 }
                ?.toDouble()
                ?: routeDwellSeconds.coerceAtLeast(0).toDouble()
        }
    }

    /**
     * Один шаг длительностью [dtSeconds].
     *
     * [defaultSpeedMps] -- скорость, если у точки своей нет. Минимум в
     * 0.1 м/с не даёт зависнуть на нулевой скорости: маршрут встал бы
     * навсегда, а это выглядит как зависшее приложение, а не как стоянка.
     */
    fun step(dtSeconds: Double, defaultSpeedMps: Double): RoutePoint? {
        val pts = points
        if (pts.isEmpty()) return null

        // Пауза после прибытия в точку: стоим и ждём, время не бежит по пути.
        if (dwellLeft > 0) {
            dwellLeft -= dtSeconds
            if (dwellLeft < 0) dwellLeft = 0.0
            return current
        }

        var guard = 0
        while (guard++ < MAX_SEGMENTS_PER_STEP) {
            val from = pts[index]
            val nextIndex = index + 1

            // Конец маршрута: на этом шаге вернуть некуда.
            if (nextIndex >= pts.size) {
                if (!loop) {
                    current = from
                    return null
                }
                // Кольцо: с первого на последний идём как обычно, индекс остаётся
                // на последней точке, а сегмент становится «последняя -> первая».
            }

            val to = if (nextIndex >= pts.size) pts[0] else pts[nextIndex]
            val speed = (from.speedMps ?: defaultSpeedMps).coerceAtLeast(MIN_SPEED_MPS)
            val segment = from.distanceTo(to).coerceAtLeast(1.0)
            traveledMeters += speed * dtSeconds

            if (traveledMeters < segment) {
                // Сегмент не пройден: линейная интерполяция -- на секундных
                // шагах ошибка плоскости меньше заявленной точности GPS.
                val fraction = traveledMeters / segment
                val point = RoutePoint(
                    latitude = from.latitude + (to.latitude - from.latitude) * fraction,
                    longitude = from.longitude + (to.longitude - from.longitude) * fraction,
                    speedMps = from.speedMps
                )
                current = point
                return point
            }

            // Сегмент пройден: встали в следующую точку, включаем её паузу.
            traveledMeters = 0.0
            index = if (nextIndex >= pts.size) 0 else nextIndex
            val arrived = pts[index]
            val atEnd = index == 0 || index == pts.size - 1
            dwellLeft = (arrived.dwellSeconds.takeIf { it > 0 }
                ?: if (atEnd) routeDwellSeconds else 0).toDouble()
            current = arrived
            if (dwellLeft > 0) return arrived
            // Паузы нет -- сразу идём дальше по следующему сегменту.
        }
        return current
    }

    private companion object {
        /** Защита от бесконечного цикла: за один шаг и так не больше ~10 сегментов. */
        const val MAX_SEGMENTS_PER_STEP = 64
        const val MIN_SPEED_MPS = 0.1
    }
}

/** Создаёт движок подмены для текущей платформы. */
expect fun createGpsEngine(): GpsEngine
