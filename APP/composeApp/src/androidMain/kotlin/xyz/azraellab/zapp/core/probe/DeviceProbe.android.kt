package xyz.azraellab.zapp.core.probe

import android.os.Build
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import xyz.azraellab.zapp.core.root.RootShell
import java.io.File

/**
 * Разведка Android.
 *
 * Корень проверяется запуском `su`, а не по файлам вроде `/system/xbin/su`:
 * файл может лежать, а запрет на запуск -- стоять (Forced-Root, защищённые
 * прошивки). Единственный честный признак -- отвечает ли `su` и нулевым ли
 * кодом выхода. Форма вызова подбирается в [RootShell]: на разных прошивках
 * это то `su 0 sh -c`, то `su -c`, и жёстко записанная форма даёт ложный
 * «root: нет» на устройствах с другой утилитой.
 */
actual suspend fun probeDevice(): DeviceProbe = withContext(Dispatchers.IO) {
    DeviceProbe(
        root = probeSu(),
        magisk = probeMagisk(),
        system = "Android ${Build.VERSION.RELEASE}",
        kernel = System.getProperty("os.version") ?: "",
        model = "${Build.MANUFACTURER} ${Build.MODEL}",
        apiLevel = Build.VERSION.SDK_INT,
        vpnTunnel = Probe.YES
    )
}

private fun probeSu(): Probe = when {
    RootShell.available() -> Probe.YES
    RootShell.mode() == RootShell.Mode.NONE -> Probe.NO
    else -> Probe.UNKNOWN
}

/** Magisk живёт не в PATH, а в своём каталоге; проверка -- по кандидатам. */
private fun probeMagisk(): Probe {
    val candidates = listOf(
        "/sbin/magisk",
        "/system/bin/magisk",
        "/system/xbin/magisk",
        "/data/adb/magisk/magisk"
    )
    return if (candidates.any { File(it).exists() }) Probe.YES else Probe.NO
}
