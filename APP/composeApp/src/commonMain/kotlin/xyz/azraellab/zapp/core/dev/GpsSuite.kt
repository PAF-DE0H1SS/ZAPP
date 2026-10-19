package xyz.azraellab.zapp.core.dev

import xyz.azraellab.zapp.core.GpsConfig
import xyz.azraellab.zapp.core.GpsMode
import xyz.azraellab.zapp.core.GpsTarget
import xyz.azraellab.zapp.core.RoutePoint
import xyz.azraellab.zapp.core.gps.DriftAccumulator
import xyz.azraellab.zapp.core.gps.RouteProjection
import xyz.azraellab.zapp.core.gps.RouteProgress
import xyz.azraellab.zapp.core.gps.RubberBand

/**
 * Геолокация: режимы, цель, расстояния, проигрывание маршрута.
 *
 * [RouteProgress] -- единственное место, где «проехали маршрут» превращается
 * в координаты: арифметика чистая, поэтому на десктопе проверяется ровно то,
 * что на телефоне крутится в фоне раз в секунду.
 */
internal fun gpsTests(out: MutableList<DevTest>) {

    // --- Режимы ---

    out += DevTest("gps/mode-of", DevCategory.GPS, "GpsMode.of") {
        GpsMode.entries.forEach { mode ->
            checkEq(mode, GpsMode.of(mode.code), mode.code)
            checkEq(mode, GpsMode.of(" ${mode.code.uppercase()} "), mode.code)
        }
        checkEq(GpsMode.DISABLED, GpsMode.of("не то"), "неизвестный")
        checkEq(GpsMode.DISABLED, GpsMode.of(null), "null")
    }

    out += DevTest("gps/mode-codes-unique", DevCategory.GPS, "Коды режимов уникальны") {
        val codes = GpsMode.entries.map { it.code }
        checkEq(codes.size, codes.toSet().size, "коды: $codes")
        val ru = GpsMode.entries.map { it.ru }
        checkEq(ru.size, ru.toSet().size, "имена: $ru")
    }

    // --- Цель ---

    out += DevTest("gps/target-fixed", DevCategory.GPS, "Точка: цель несёт координаты") {
        val target = GpsConfig(mode = "fixed", latitude = 55.75, longitude = 37.61).target
        checkTrue(target is GpsTarget.Fixed, "тип: $target")
        target as GpsTarget.Fixed
        checkEq(55.75, target.latitude, "широта")
        checkEq(37.61, target.longitude, "долгота")
    }

    out += DevTest("gps/target-route", DevCategory.GPS, "Маршрут: цель парсит строку") {
        val target = GpsConfig(mode = "route", route = "0,0;1,1;2,2").target
        checkTrue(target is GpsTarget.Route, "тип: $target")
        checkEq(3, (target as GpsTarget.Route).points.size, "три точки")
    }

    out += DevTest("gps/target-vpn", DevCategory.GPS, "Координаты VPN: точка ещё не разрешена") {
        checkTrue(GpsConfig(mode = "vpn").target is GpsTarget.VpnLocation, "VpnLocation")
    }

    // C6: enabled раньше был мёртвым полем -- подмена не переживала рестарт.
    // Теперь это намерение пользователя, по которому поднимается автостарт.
    out += DevTest("gps/autostart", DevCategory.GPS, "Тумблер переживает рестарт") {
        checkTrue(GpsConfig(enabled = true).autostart, "включено + точка по умолчанию")
        checkTrue(GpsConfig(enabled = true, mode = "route", route = "0,0;1,1").autostart, "маршрут")
        checkFalse(GpsConfig(enabled = false).autostart, "выключено")
        checkFalse(GpsConfig(enabled = true, mode = "disabled").autostart, "режим отменяет тумблер")
        checkFalse(GpsConfig(enabled = true, mode = "что-то").autostart, "мусорный режим")
    }

    out += DevTest("gps/target-disabled", DevCategory.GPS, "Выключено и неизвестный режим -- Off") {
        checkTrue(GpsConfig(mode = "disabled").target is GpsTarget.Off, "disabled")
        checkTrue(GpsConfig(mode = "что-то").target is GpsTarget.Off, "мусор")
    }

    // --- Списки и мелочи конфига ---

    out += DevTest("gps/app-list", DevCategory.GPS, "Список приложений") {
        val config = GpsConfig(apps = " com.a, com.b ,, com.c ")
        checkEq(listOf("com.a", "com.b", "com.c"), config.appList(), "appList")
        checkTrue(GpsConfig().appList().isEmpty(), "пусто")
    }

    out += DevTest("gps/route-points-from-config", DevCategory.GPS, "routePoints читает строку маршрута") {
        val config = GpsConfig(route = "10,20;11,21")
        checkEq(2, config.routePoints().size, "две точки")
        checkTrue(GpsConfig().routePoints().isEmpty(), "без маршрута")
    }

    // --- Расстояние ---

    out += DevTest("gps/distance-zero", DevCategory.GPS, "До самой себя -- ноль") {
        val point = RoutePoint(55.75, 37.61)
        checkEq(0.0, point.distanceTo(point), "ноль")
    }

    out += DevTest("gps/distance-known-cities", DevCategory.GPS, "Москва -- Петербург ~634 км") {
        val moscow = RoutePoint(55.7558, 37.6173)
        val spb = RoutePoint(59.9343, 30.3351)
        val km = moscow.distanceTo(spb) / 1000.0
        checkTrue(km in 600.0..700.0, "получилось $km км")
        checkEq(km, spb.distanceTo(moscow) / 1000.0, "симметрия")
    }

    out += DevTest("gps/distance-one-degree-latitude", DevCategory.GPS, "Градус широты -- ~111 км") {
        val km = RoutePoint(0.0, 0.0).distanceTo(RoutePoint(1.0, 0.0)) / 1000.0
        checkTrue(km in 110.0..112.0, "получилось $km км")
    }

    // --- Проигрывание маршрута ---

    out += DevTest("gps/route-empty", DevCategory.GPS, "Пустой маршрут: шага нет, позиции нет") {
        val progress = RouteProgress(emptyList(), loop = false, routeDwellSeconds = 0)
        checkEq(null, progress.current, "current")
        checkEq(null, progress.step(1.0, 1.0), "step")
        checkEq(0, progress.index, "index")
    }

    out += DevTest("gps/route-starts-at-first-point", DevCategory.GPS, "Старт -- в первой точке") {
        val a = RoutePoint(0.0, 0.0)
        val progress = RouteProgress(listOf(a, RoutePoint(0.0, 1.0)), loop = false, routeDwellSeconds = 0)
        checkEq(a, progress.current, "current")
        checkEq(0, progress.index, "index")
        checkEq(0.0, progress.dwellLeft, "dwell без паузы")
    }

    out += DevTest("gps/route-interpolates", DevCategory.GPS, "Сегмент не пройден -- линейная интерполяция") {
        // Градус долготы на экваторе ~111 км; идём 1000 м за шаг из 111195 м.
        val progress = RouteProgress(listOf(RoutePoint(0.0, 0.0), RoutePoint(0.0, 1.0)), false, 0)
        val point = requireNotNull(progress.step(1.0, 1000.0)) { "шаг" }
        checkEq(0.0, point.latitude, "широта не двигается")
        checkTrue(point.longitude > 0.0 && point.longitude < 1.0, "между точками: ${point.longitude}")
        checkEq(0, progress.index, "сегмент не пройден")
    }

    out += DevTest("gps/route-dwell-starts-here", DevCategory.GPS, "Стартовая пауза держит позицию") {
        val a = RoutePoint(0.0, 0.0, dwellSeconds = 3)
        val progress = RouteProgress(listOf(a, RoutePoint(0.0, 1.0)), false, 0)
        checkEq(3.0, progress.dwellLeft, "пауза первой точки")
        checkEq(a, progress.step(1.0, 1000.0), "шаг 1 -- стоим")
        checkEq(a, progress.step(1.0, 1000.0), "шаг 2 -- стоим")
        checkEq(a, progress.step(1.0, 1000.0), "шаг 3 -- стоим")
        checkEq(0.0, progress.dwellLeft, "пауза кончилась")
        val moving = requireNotNull(progress.step(1.0, 1000.0)) { "шаг 4 -- движемся" }
        checkTrue(moving.longitude > 0.0, "пошли: ${moving.longitude}")
    }

    out += DevTest("gps/route-route-dwell-at-ends", DevCategory.GPS, "Общая пауза действует на концах маршрута") {
        val progress = RouteProgress(
            listOf(RoutePoint(0.0, 0.0), RoutePoint(0.0, 1.0)),
            loop = false,
            routeDwellSeconds = 2
        )
        checkEq(2.0, progress.dwellLeft, "старт берёт общую паузу")
    }

    out += DevTest("gps/route-end-returns-null", DevCategory.GPS, "Без зацикливания шаг в конце -- null") {
        val a = RoutePoint(0.0, 0.0)
        val b = RoutePoint(0.0, 1.0)
        val segmentMeters = a.distanceTo(b)

        // Без паузы в конце дошедший шаг сразу отдаёт конец.
        val progress = RouteProgress(listOf(a, b), loop = false, routeDwellSeconds = 0)
        checkEq(null, progress.step(1.0, segmentMeters), "дошли -- идти больше некуда")
        checkEq(b, progress.current, "позиция -- последняя точка")
        checkEq(1, progress.index, "индекс в конце")
        checkEq(null, progress.step(1.0, segmentMeters), "повторный шаг тоже null")

        // С паузой прибытие отдаёт точкой, а конец -- следующим шагом.
        // С общей паузой старт стоит в ней, прибытие отдаёт точкой, конец -- после.
        val withDwell = RouteProgress(listOf(a, b), loop = false, routeDwellSeconds = 1)
        checkEq(a, withDwell.step(1.0, segmentMeters), "стартовая пауза")
        checkEq(b, withDwell.step(1.0, segmentMeters), "прибытие видно")
        checkEq(b, withDwell.step(1.0, segmentMeters), "пауза удерживает")
        checkEq(null, withDwell.step(5.0, segmentMeters), "после паузы -- конец")
    }

    out += DevTest("gps/route-loop-wraps", DevCategory.GPS, "Кольцо возвращается к первой точке") {
        val progress = RouteProgress(
            listOf(RoutePoint(0.0, 0.0), RoutePoint(0.0, 1.0)),
            loop = true,
            routeDwellSeconds = 0
        )
        val segmentMeters = RoutePoint(0.0, 0.0).distanceTo(RoutePoint(0.0, 1.0))
        val point = progress.step(1.0, segmentMeters)
        checkTrue(point != null, "в кольце всегда есть куда идти")
        checkTrue(progress.index == 0 || progress.index == 1, "индекс в пределах маршрута")
    }

    out += DevTest("gps/route-min-speed", DevCategory.GPS, "Нулевая скорость не вешает маршрут") {
        val progress = RouteProgress(listOf(RoutePoint(0.0, 0.0), RoutePoint(0.0, 1.0)), false, 0)
        val point = requireNotNull(progress.step(60.0, 0.0)) { "шаг" }
        checkTrue(point.longitude > 0.0, "минимум 0.1 м/с двигает: ${point.longitude}")
    }

    out += DevTest("gps/route-copy-isolated", DevCategory.GPS, "Внешние правки списка не двигают проигрывание") {
        val points = mutableListOf(RoutePoint(0.0, 0.0), RoutePoint(0.0, 1.0))
        val progress = RouteProgress(points, loop = false, routeDwellSeconds = 0)
        points.clear()
        checkEq(0, progress.index, "маршрут внутри копии")
        checkTrue(progress.current != null, "позиция сохранилась")
        checkTrue(progress.step(1.0, 1000.0) != null, "шаг всё ещё возможен")
    }

    out += DevTest("gps/route-point-speed-overrides-default", DevCategory.GPS, "Скорость точки важнее общей") {
        val slowSegment = RoutePoint(0.0, 0.0, speedMps = 1.0)
        val progress = RouteProgress(listOf(slowSegment, RoutePoint(0.0, 1.0)), false, 0)
        val point = requireNotNull(progress.step(1.0, 1000000.0)) { "шаг" }
        // 1 м за шаг из ~111195 м: почти не сдвинулись, но сдвинулись.
        checkTrue(point.longitude > 0.0, "маленький шаг: ${point.longitude}")
        checkTrue(point.longitude < 0.001, "быстрая общая скорость проигнорирована")
    }

    // --- Анти rubber-banding: джиттер, дрейф, профиль скорости ---

    out += DevTest("gps/jitter-bounds", DevCategory.GPS, "Джиттер скорости держит ±процент") {
        // 100 засевок: любой результат внутри ±20 %, среднее рядом с базой.
        val random = kotlin.random.Random(7)
        val values = (1..100).map { RubberBand.jitterSpeed(10.0, 20, random) }
        checkTrue(values.all { it in 8.0..12.0 }, "границы: min=${values.min()} max=${values.max()}")
        val avg = values.average()
        checkTrue(avg in 9.5..10.5, "среднее ~ база: $avg")
    }

    out += DevTest("gps/jitter-zero-is-exact", DevCategory.GPS, "Нулевой разброс -- точная скорость") {
        checkEq(10.0, RubberBand.jitterSpeed(10.0, 0), "0 %")
        checkEq(10.0, RubberBand.jitterSpeed(10.0, -5), "минус -- тоже точная")
        checkEq(0.0, RubberBand.jitterSpeed(0.0, 50), "ноль не двигается")
    }

    out += DevTest("gps/drift-accumulates", DevCategory.GPS, "Дрейф копится по тактам") {
        val drift = DriftAccumulator()
        checkEq(0.0, drift.meters, "чистый старт")
        drift.tick(0.5, 1.0)
        drift.tick(0.5, 1.0)
        checkEq(1.0, drift.meters, "два такта по 0.5 м/с")
        drift.tick(2.0, 0.0)   // нулевой шаг не копит
        drift.tick(0.0, 5.0)   // нулевой дрейф не копит
        checkEq(1.0, drift.meters, "нулевые такты не двигают")
        drift.reset()
        checkEq(0.0, drift.meters, "сброс")
    }

    out += DevTest("gps/profile-ramps", DevCategory.GPS, "Профиль: разгон с нуля, торможение к концу") {
        // 100-метровый маршрут, разгон/торможение по 25 м.
        val up = RubberBand.speedProfile(0.0, 100.0, 25.0, 25.0)
        checkEq(RubberBand.MIN_FACTOR, up, "старт не зависает на нуле")
        checkTrue(RubberBand.speedProfile(12.5, 100.0, 25.0, 25.0) in 0.1..0.999, "середина разгона")
        checkEq(1.0, RubberBand.speedProfile(50.0, 100.0, 25.0, 25.0), "середина -- полная")
        checkTrue(RubberBand.speedProfile(90.0, 100.0, 25.0, 25.0) < 1.0, "торможение")
        checkTrue(RubberBand.speedProfile(100.0, 100.0, 25.0, 25.0) >= 0.1, "прибытие не ноль")
    }

    out += DevTest("gps/profile-off-and-short", DevCategory.GPS, "Профиль выключается и не душит короткий маршрут") {
        // Нулевые accel/decel -- профиль отключён, старое поведение.
        checkEq(1.0, RubberBand.speedProfile(0.0, 100.0, 0.0, 0.0), "выключен")
        checkEq(1.0, RubberBand.speedProfile(0.0, 0.0, 25.0, 25.0), "ноль длины")
        // Короткий маршрут: разгон ограничен четвертью длины, в середине -- полная.
        checkEq(1.0, RubberBand.speedProfile(5.0, 10.0, 25.0, 25.0), "короткий в середине")
    }

    out += DevTest("gps/route-profile-slows-start", DevCategory.GPS, "Маршрут стартует медленнее и доходит") {
        val points = listOf(RoutePoint(0.0, 0.0), RoutePoint(0.001, 0.0))
        val withProfile = RouteProgress(points, false, 0, accelMeters = 25.0, decelMeters = 25.0)
        val plain = RouteProgress(points, false, 0)
        val a = requireNotNull(withProfile.step(1.0, 10.0)) { "шаг с профилем" }
        val b = requireNotNull(plain.step(1.0, 10.0)) { "шаг без профиля" }
        checkTrue(a.latitude < b.latitude, "старт медленнее: ${a.latitude} < ${b.latitude}")
        // Маршрут всё равно доходит: пол не даёт старту зависнуть.
        var done = false
        repeat(40) { if (withProfile.step(1.0, 10.0) == null) done = true }
        checkTrue(done, "маршрут пройден до конца")
    }

    // --- Мини-карта: проекция маршрута на плоскость ---

    out += DevTest("gps/projection-bounds", DevCategory.GPS, "Все точки маршрута внутри холста") {
        val route = listOf(
            RoutePoint(55.75, 37.61),
            RoutePoint(55.76, 37.63),
            RoutePoint(55.74, 37.60)
        )
        val projection = RouteProjection.transform(route, 300f, 140f, 10f)
        projection.project(route).forEach { (x, y) ->
            checkTrue(x in 10f..290f, "x в холсте: $x")
            checkTrue(y in 10f..130f, "y в холсте: $y")
        }
    }

    out += DevTest("gps/projection-degenerate", DevCategory.GPS, "Вырожденные маршруты -- в центр") {
        // Одна точка -- центр холста.
        val single = RouteProjection.transform(listOf(RoutePoint(55.75, 37.61)), 300f, 140f, 10f)
        val (sx, sy) = single.xy(55.75, 37.61)
        checkEq(150f, sx, "центр по x")
        checkEq(70f, sy, "центр по y")
        // Вертикальная линия: x по центру, y тянется на весь холст.
        val vertical = RouteProjection.transform(
            listOf(RoutePoint(55.75, 37.61), RoutePoint(55.76, 37.61)), 300f, 140f, 10f
        )
        val (vx1, vy1) = vertical.xy(55.75, 37.61)
        val (vx2, vy2) = vertical.xy(55.76, 37.61)
        checkEq(vx1, vx2, "одна ось")
        checkTrue(kotlin.math.abs(vy2 - vy1) > 100f, "растянулась по высоте")
        // Пустой список не падает.
        val empty = RouteProjection.transform(emptyList(), 300f, 140f, 10f)
        checkEq(150f, empty.xy(0.0, 0.0).first, "пусто -- центр")
    }

    out += DevTest("gps/projection-preserves-ratio", DevCategory.GPS, "Пропорции сохраняются: градусы на широте 60 честно делятся") {
        // На широте 60 косинус = 0.5: градус долготы -- вдвое короче градуса
        // широты. Проекция обязана это учесть: 2° долготы при широте 60
        // равно 1° широты, и на холсте фигура выходит с равными сторонами.
        // (1° x 1° при широте 60 -- честный прямоугольник 1:2, не квадрат.)
        val route = listOf(
            RoutePoint(59.5, 30.0),
            RoutePoint(60.5, 30.0),
            RoutePoint(60.5, 32.0),
            RoutePoint(59.5, 32.0)
        )
        val projection = RouteProjection.transform(route, 400f, 400f, 0f)
        val pts = projection.project(route)
        val dx = kotlin.math.abs(pts[2].first - pts[1].first)
        val dy = kotlin.math.abs(pts[2].second - pts[3].second)
        checkTrue(kotlin.math.abs(dx - dy) < 0.01f, "стороны равны: $dx vs $dy")
    }
}
