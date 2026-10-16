package xyz.azraellab.zapp.core.connections

import java.io.File
import xyz.azraellab.zapp.core.perApp.installedApps
import xyz.azraellab.zapp.core.root.RootShell

actual fun createConnectionsSource(): ConnectionsSource = AndroidConnectionsSource()

/**
 * Android: таблицы /proc/net/{tcp,tcp6,udp,udp6}.
 *
 * uid из строки таблицы превращается в пакет по установленным
 * приложениям (обратный индекс, кеш на минуту -- приложение ставят и
 * снимают не каждый опрос). Прямое чтение -- основной путь; если ни
 * один файл не открылся, пробуется root-фолбэк, а если и он пуст --
 * таблица честно возвращается с пометкой [ConnNote.NO_PROC].
 */
internal class AndroidConnectionsSource(
    private val readProc: (String) -> String? = { path ->
        runCatching { File(path).readText() }.getOrNull()
    },
    private val readRoot: (String) -> String? = { path ->
        runCatching { RootShell.run("cat $path", ROOT_TIMEOUT_MS) }.getOrNull()?.takeIf { it.ok }?.output
    },
    private val packages: () -> Map<Int, String> = { installedPackagesByUid() }
) : ConnectionsSource {

    override fun read(): ConnectionsSample {
        val parsed = mutableListOf<ProcNetParser.ProcConn>()
        var reachable = false
        for ((protocol, path) in PROC_FILES) {
            val text = readProc(path) ?: continue
            reachable = true
            parsed += ProcNetParser.parse(protocol, text)
        }
        if (!reachable) {
            for ((protocol, path) in PROC_FILES) {
                val text = readRoot(path) ?: continue
                reachable = true
                parsed += ProcNetParser.parse(protocol, text)
            }
        }
        if (!reachable) return ConnectionsSample(emptyList(), ConnNote.NO_PROC)
        val owners = packages()
        return ConnectionsSample(
            parsed.map { row ->
                ConnRow(row.proto, row.localPort, row.remote, owners[row.uid] ?: "uid:${row.uid}")
            }
        )
    }

    companion object {
        private val PROC_FILES = listOf(
            "tcp" to "/proc/net/tcp",
            "tcp6" to "/proc/net/tcp6",
            "udp" to "/proc/net/udp",
            "udp6" to "/proc/net/udp6"
        )
        private const val ROOT_TIMEOUT_MS = 1500L
    }
}

/** uid -> пакет: таблицы хранят числа, называть их умеет только список пакетов. */
private var uidCache: Map<Int, String> = emptyMap()
private var uidCacheAtMs: Long = 0L

private fun installedPackagesByUid(): Map<Int, String> {
    val now = System.currentTimeMillis()
    if (uidCache.isNotEmpty() && now - uidCacheAtMs < UID_CACHE_MS) return uidCache
    uidCache = runCatching {
        installedApps().mapNotNull { app -> app.uid?.let { it to app.packageName } }.toMap()
    }.getOrDefault(emptyMap())
    uidCacheAtMs = now
    return uidCache
}

private const val UID_CACHE_MS = 60_000L
