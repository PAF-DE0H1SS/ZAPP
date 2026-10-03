package xyz.azraellab.zapp.core.permissions

import kotlinx.coroutines.flow.StateFlow
import xyz.azraellab.zapp.core.Str

/**
 * Разрешение, которое приложению нужно для своей работы.
 *
 * Отдельные значения, а не строки: список проверяется тестом на полноту,
 * и опечатка в имени разрешения всплыла бы раньше, чем на устройстве.
 */
enum class PermissionId(val title: Str) {
    /** Доступ к туннелю: системный запрос VpnService.prepare(). */
    VPN(Str.PERM_VPN),

    /**
     * Системные уведомления.
     *
     * Без них события «подмена включилась» и «вышло обновление» всё равно
     * произойдут -- просто останутся незамеченными, поэтому запрос идёт
     * только по прямому действию пользователя, а не само собой.
     */
    NOTIFICATIONS(Str.PERM_NOTIFICATIONS),

    /** Точное местоположение: без него тест-провайдер не пускает подмену. */
    LOCATION_FINE(Str.PERM_LOCATION_FINE),

    /**
     * Разрешение на имитацию местоположения.
     *
     * Это не runtime-разрешение, а AppOps-флаг из настроек разработчика:
     * приложение не может запросить его диалогом, поэтому «запрос» здесь --
     * это переход в системные настройки.
     */
    LOCATION_MOCK(Str.PERM_LOCATION_MOCK)
}

/** Состояние разрешения на устройстве. */
enum class PermissionState {
    /** Дано, приложение может пользоваться. */
    GRANTED,

    /** Не дано; системный диалог покажет, если его показать. */
    DENIED,

    /** Запросится через переход в настройки, а не диалогом. */
    SETTINGS,

    /** На этой платформе разрешения не существует. */
    UNSUPPORTED
}

/** Итог проверки одного разрешения. */
data class PermissionStatus(
    val id: PermissionId,
    val state: PermissionState,
    /** Подсказка: почему не выдано или что делать, если [state] == [PermissionState]. */
    val hint: Str? = null
) {
    val granted: Boolean get() = state == PermissionState.GRANTED
}

/**
 * Шлюз разрешений.
 *
 * Общий код описывает «что нужно», платформа решает «как спросить»:
 * Android -- системные диалоги и AppOps, на десктопе этих разрешений нет.
 *
 * Состояние живёт в [statuses] как StateFlow, а не как разовый вызов:
 * пользователь может ответить на системный диалог, вернуться из настроек
 * или выключить уведомления в шторке -- UI обязан это увидеть сам.
 */
interface PermissionGateway {

    /** Текущее состояние всех разрешений. */
    val statuses: StateFlow<List<PermissionStatus>>

    /** Перечитывает состояние у системы. Дёшево, зовётся после ответа диалога. */
    fun refresh()

    /**
     * Запрашивает разрешение.
     *
     * [onResult] вызывается ровно один раз с итогом; фактический статус
     * всегда перечитывается из [statuses], а не берётся на веру.
     */
    fun request(id: PermissionId, onResult: (Boolean) -> Unit)
}

expect fun createPermissionGateway(): PermissionGateway
