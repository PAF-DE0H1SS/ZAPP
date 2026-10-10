package xyz.azraellab.zapp.ui.pages

import androidx.compose.runtime.Composable
import xyz.azraellab.zapp.core.daemon.DaemonEvent
import xyz.azraellab.zapp.core.gps.GpsEvent
import xyz.azraellab.zapp.core.log.diagnosisLine
import xyz.azraellab.zapp.core.log.errorEvent
import xyz.azraellab.zapp.core.log.platformFormatTime
import xyz.azraellab.zapp.ui.tr

/**
 * Причины сбоя для карточек состояний.
 *
 * Логика выбора строки живёт в `core.log` (чистая и тестируемая), здесь --
 * только перевод: события приложения хранят ключ [xyz.azraellab.zapp.core.Str],
 * а сырой вывод демона остаётся как есть. Состояние ERROR без показанной
 * причины -- «голая ошибка»: эти функции существуют, чтобы её не было.
 */

/** Строка журнала демона: событие -- переведённым, вывод -- как есть. */
@Composable
fun DaemonEvent.rendered(): String =
    if (text != null) tr(text) + if (arg.isEmpty()) "" else " $arg" else raw

/** Строка журнала GPS: событие всегда ключ, перевод -- здесь. */
@Composable
fun GpsEvent.rendered(): String = tr(text) + if (arg.isEmpty()) "" else " $arg"

/**
 * Последняя ошибка демона одной строкой: `время · компонент · причина`.
 *
 * Компонент берётся снаружи (движок знает свой бинарь), время -- из
 * штампа события. Без причины -- null: под статусом висеть нечему.
 */
@Composable
fun List<DaemonEvent>.diagnosisReason(component: String): String? {
    val event = errorEvent() ?: return null
    return diagnosisLine(
        time = if (event.at > 0) platformFormatTime(event.at) else "",
        component = component,
        causes = listOf(event.rendered())
    )
}

/** Последняя ошибка подмены координат той же строкой диагностики. */
@Composable
fun List<GpsEvent>.gpsDiagnosisReason(component: String): String? {
    val event = errorEvent() ?: return null
    return diagnosisLine(
        time = if (event.at > 0) platformFormatTime(event.at) else "",
        component = component,
        causes = listOf(event.rendered())
    )
}
