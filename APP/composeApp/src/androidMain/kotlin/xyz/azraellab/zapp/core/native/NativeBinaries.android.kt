package xyz.azraellab.zapp.core.native

import android.content.Context
import android.os.Build
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import xyz.azraellab.zapp.core.AndroidCtx
import xyz.azraellab.zapp.core.daemon.daemonLogcat
import java.io.File

private const val TAG = "ZAPP-INSTALL"

/**
 * Нативные бинари Android.
 *
 * Файлы не распаковываются, а поставляются в jniLibs (`lib<tool>.so`):
 * система кладёт их в nativeLibraryDir при установке, и только оттуда их
 * разрешено исполнять. Каталог app data для запуска непригоден -- SELinux
 * отклоняет exec с ошибкой `execute_no_trans`, и никакие права на файл
 * этого не лечат.
 *
 * Старые распакованные файлы в filesDir/bin при первом запуске удаляются:
 * они больше не используются, а весят десятки мегабайт.
 */
actual object NativeBinaries {

    actual val tools: List<String> = listOf("nfqws", "goodbyedpi", "wg", "box", "openvpn", "tor", "lyrebird")

    private val mutex = Mutex()

    @Volatile
    private var prepared = false

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    actual suspend fun awaitReady() {
        mutex.withLock {
            if (prepared) return
            withContext(Dispatchers.IO) { prepare() }
        }
    }

    actual fun path(tool: String): String? {
        val context = AndroidCtx.current ?: return null
        val file = File(context.applicationInfo.nativeLibraryDir, "lib$tool.so")
        return file.absolutePath.takeIf { file.exists() }
    }

    actual fun installed(): List<String> = tools.filter { path(it) != null }

    /**
     * Переустановка.
     *
     * Библиотеки приходят из APK, поэтому «достать заново» извне нельзя:
     * честный максимум -- снести остатки старой распаковки и заново
     * пересчитать, что доступно. Если система не разложила библиотеки,
     * это будет видно в списке компонентов, а не потеряется в кэше.
     */
    actual suspend fun reinstall() {
        mutex.withLock {
            withContext(Dispatchers.IO) {
                prepared = false
                prepare()
            }
        }
    }

    private fun prepare() {
        val context = AndroidCtx.current ?: return
        cleanupLegacyInstall(context)

        val present = installed()
        if (present.size == tools.size) {
            daemonLogcat(TAG, "ready: ${Build.SUPPORTED_ABIS.firstOrNull().orEmpty()} (${tools.joinToString()})")
        } else {
            val missing = tools - present.toSet()
            daemonLogcat(TAG, "missing libraries: ${missing.joinToString()}")
        }
        prepared = true
    }

    /** Убирает распаковку прошлых версий: бинари больше не живут в filesDir. */
    private fun cleanupLegacyInstall(context: Context) {
        val dir = File(context.filesDir, "bin")
        if (!dir.isDirectory) return
        runCatching {
            dir.listFiles()?.forEach { file -> runCatching { file.delete() } }
            dir.delete()
        }
    }
}
