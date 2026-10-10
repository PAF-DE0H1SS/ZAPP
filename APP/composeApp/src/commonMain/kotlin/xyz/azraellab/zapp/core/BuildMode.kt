package xyz.azraellab.zapp.core

/**
 * Режим сборки: разрешено ли поднимать права через `su`.
 *
 * Android-приложение выставляет значение из `BuildConfig.ZAPP_ROOT`
 * (флавор `root` / `noroot`) в `ZappApp.onCreate` -- до Activity, до
 * первого кадра и до инициализации каких-либо движков: плитки шторки
 * поднимают процесс и без окна. На десктопе остаётся `true`: там
 * полный режим, `RootShell` и так работает от текущего пользователя.
 *
 * Когда `rootAllowed == false`, `RootShell.mode()` возвращает `Mode.NONE`
 * без единой попытки запустить `su`: noroot-сборка не должна дёргать
 * Magisk-промпты и не должна зависеть от их ответа.
 */
object BuildMode {

    /** Разрешены ли root-команды (пробы `su`, iptables, NFQUEUE и т.п.). */
    var rootAllowed: Boolean = true
}
