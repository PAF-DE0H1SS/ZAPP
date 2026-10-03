package xyz.azraellab.zapp.core.probe

import xyz.azraellab.zapp.core.Str

/** Ответ разведки: есть, нет или ещё не проверено. */
enum class Probe {
    YES, NO, UNKNOWN;

    val label: Str
        get() = when (this) {
            YES -> Str.PROBE_YES
            NO -> Str.PROBE_NO
            UNKNOWN -> Str.PROBE_UNKNOWN
        }
}

/**
 * Результат разведки устройства.
 *
 * Разведка нужна до того, как показывать кнопки: предлагать «запустить с
 * root» на устройстве без root -- это вести пользователя в тупик. Поэтому
 * возможности спрашиваются один раз при старте и лежат в состоянии, а не
 * переспрашиваются у системы при каждой перерисовке.
 */
data class DeviceProbe(
    /** Есть ли `su` и отвечает ли он. */
    val root: Probe = Probe.UNKNOWN,
    /** Android: установлен ли Magisk. */
    val magisk: Probe = Probe.UNKNOWN,
    /** Номер и имя ОС, например "Android 16". */
    val system: String = "",
    /** Ядро: "Linux 6.9.4-android14". */
    val kernel: String = "",
    /** Модель устройства / модель машины. */
    val model: String = "",
    /** API-уровень Android или 0. */
    val apiLevel: Int = 0,
    /** Можно ли поднять туннель системным способом. */
    val vpnTunnel: Probe = Probe.UNKNOWN
)

expect suspend fun probeDevice(): DeviceProbe
