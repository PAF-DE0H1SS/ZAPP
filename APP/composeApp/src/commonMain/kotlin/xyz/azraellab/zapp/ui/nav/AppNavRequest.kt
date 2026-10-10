package xyz.azraellab.zapp.ui.nav

import kotlinx.coroutines.flow.MutableStateFlow

/**
 * Запрос навигации извне композиции.
 *
 * Уведомление в шторке живёт вне Activity-дерева и не может ткнуть
 * пальцем в нижнюю панель: кнопка «открыть настройки» приходит сюда
 * через intent, а AppRoot переключает вкладку и тут же гасит флаг.
 *
 * Один поток на одно действие: если завтра появятся другие переходы
 * «откуда-то ещё», они придут тем же способом.
 */
object AppNavRequest {

    /**
     * EXTRA-имя для intent'а: MainActivity кладёт его в launch-intent
     * уведомления, общий код -- только читает результат.
     */
    const val EXTRA_OPEN_SETTINGS: String = "xyz.azraellab.zapp.OPEN_SETTINGS"

    /** Просит переключиться на вкладку настроек; сбрасывается AppRoot'ом. */
    val openSettings = MutableStateFlow(false)
}
