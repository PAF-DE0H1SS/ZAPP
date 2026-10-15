package xyz.azraellab.zapp.core.dev

import xyz.azraellab.zapp.core.GpsConfig
import xyz.azraellab.zapp.core.GpsMode
import xyz.azraellab.zapp.core.GpsTarget
import xyz.azraellab.zapp.core.RoutePoint
import xyz.azraellab.zapp.core.gps.RouteProgress

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
}
