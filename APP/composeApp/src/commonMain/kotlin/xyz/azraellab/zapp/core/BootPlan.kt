package xyz.azraellab.zapp.core

import kotlinx.serialization.Serializable

/**
 * Что поднимать после перезагрузки: снимок намерений пользователя.
 *
 * Реестр пишется при каждом сохранении конфига и живёт отдельно от
 * него (AtomicFile в noBackupFilesDir): ресивер BOOT_COMPLETED читает
 * дешёвый файл, а не грузит всё состояние, а пустой план -- сигнал
 * системе выключить ресивер и не будить приложение зря.
 *
 * Поля -- это не «состояние процесса», а намерения: тумблеры
 * отдельных сессий под мастер-флагом `bootAutostart`. Как именно
 * поднимать (и поднимать ли -- разрешения, профили), решает уже
 * `startAutoServices` по живому конфигу.
 */
@Serializable
data class BootPlan(
    val vpn: Boolean = false,
    val dpi: Boolean = false,
    val gps: Boolean = false
) {

    /** Пустой план -- автозапуск не нужен, ресивер можно погасить. */
    val isEmpty: Boolean get() = !vpn && !dpi && !gps

    companion object {

        /**
         * Намерения из конфига: мастер-тумблер и флаги сессий.
         *
         * Условия зеркалят `autostartOnBoot()` и то, что реально
         * делает `startAutoServices`, чтобы реестр не обещал того,
         * чего автоматика не сделает.
         */
        fun of(config: AppConfig): BootPlan = BootPlan(
            vpn = config.bootAutostart && config.vpn.autoStart,
            dpi = config.bootAutostart &&
                (config.zapret.enabled || config.goodbyeDpi.autoStart),
            gps = config.bootAutostart && config.gps.autostart
        )
    }
}
