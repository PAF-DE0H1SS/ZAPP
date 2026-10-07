package xyz.azraellab.zapp.ui.pages

import androidx.compose.runtime.Composable
import xyz.azraellab.zapp.core.daemon.DaemonEvent
import xyz.azraellab.zapp.core.gps.GpsEvent
import xyz.azraellab.zapp.core.log.errorEvent
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

/** Причина сбоя демона, готовая к показу под статусом. */
@Composable
fun List<DaemonEvent>.errorReason(): String? = errorEvent()?.rendered()

/** Строка журнала GPS: событие всегда ключ, перевод -- здесь. */
@Composable
fun GpsEvent.rendered(): String = tr(text) + if (arg.isEmpty()) "" else " $arg"

/** Причина сбоя подмены координат, готовая к показу под статусом. */
@Composable
fun List<GpsEvent>.gpsErrorReason(): String? = errorEvent()?.rendered()
