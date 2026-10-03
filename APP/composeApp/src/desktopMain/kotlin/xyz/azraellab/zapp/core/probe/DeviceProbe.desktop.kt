package xyz.azraellab.zapp.core.probe

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Разведка десктопа.
 *
 * Вопросы те же, что на Android, потому что карточка «Устройство» одна:
 * где корень здесь -- решает пользователь системы, а не приложение, поэтому
 * root на десктопе не проверяется запуском `sudo` (это мог бы заметить
 * пользователь, а разведка не должна ничего спрашивать). Туннель на
 * десктопе не поднимается системным способом -- честно отдаём NO.
 */
actual suspend fun probeDevice(): DeviceProbe = withContext(Dispatchers.IO) {
    val os = System.getProperty("os.name") ?: "Unknown"
    DeviceProbe(
        root = Probe.UNKNOWN,
        magisk = Probe.NO,
        system = os,
        kernel = System.getProperty("os.version") ?: "",
        model = System.getProperty("os.arch") ?: "",
        apiLevel = 0,
        vpnTunnel = Probe.NO
    )
}
