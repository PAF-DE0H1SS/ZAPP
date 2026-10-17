package xyz.azraellab.zapp.core.instance

import java.io.File
import java.io.RandomAccessFile
import java.net.InetAddress
import java.net.ServerSocket
import java.nio.channels.FileChannel
import java.nio.channels.FileLock
import java.nio.channels.OverlappingFileLockException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import xyz.azraellab.zapp.core.zappConfigRoot

/**
 * Один экземпляр приложения на машину.
 *
 * Две копии на десктопе конфликтуют напрямую: пары sing-box держат одни
 * порты, система прокси и каталог конфига делятся на всех. Решает это
 * file-lock на [lockFile]: владеет им первая копия, вторая захват
 * проигрывает и выходит, предварительно попросив первую поднять окно.
 *
 * Блокировка region-based и снимается ядром при смерти процесса, поэтому
 * упавшая первая копия не оставляет «застрявший» lock.
 */
class SingleInstance(private val lockFile: File) {

    private var channel: FileChannel? = null

    private var lock: FileLock? = null

    /** true -- lock наш, это первая копия; false -- приложение уже запущено. */
    fun tryAcquire(): Boolean {
        lockFile.parentFile?.mkdirs()
        val ch = runCatching { RandomAccessFile(lockFile, "rw").channel }.getOrNull() ?: return false
        // OverlappingFileLockException -- та же копия держит lock (гонка
        // двух потоков внутри одного процесса): это тоже «уже запущено».
        val taken = try {
            ch.tryLock()
        } catch (_: OverlappingFileLockException) {
            null
        } catch (_: Exception) {
            null
        }
        if (taken == null) {
            runCatching { ch.close() }
            return false
        }
        channel = ch
        lock = taken
        return true
    }

    /** Снимает lock: можно перезапускать приложение из этого же процесса. */
    fun release() {
        runCatching { lock?.release() }
        runCatching { channel?.close() }
        lock = null
        channel = null
    }

    companion object {

        /** Порт «подними окно» на loopback; занят чужим приложением -- просто без фокуса. */
        const val FOCUS_PORT = 39501

        private val _focusRequests = MutableStateFlow(0)

        /** Счётчик просьб поднять окно: первая копия слушает его в окне. */
        val focusRequests: StateFlow<Int> = _focusRequests.asStateFlow()

        /** Пометить, что окно пора поднять (зовёт слушатель из accept-потока). */
        fun raiseFocus() {
            _focusRequests.value++
        }

        /** Lock-файл в общем каталоге конфига приложения. */
        fun default(): SingleInstance = SingleInstance(File(zappConfigRoot(), "zapp.lock"))

        /**
         * Вторая копия: весточку первой, «подними окно».
         *
         * best-effort: если первая копия ещё не подняла слушатель или порт
         * занял кто-то чужой, вызывающий просто выходит без фокуса.
         */
        fun pingFocus(port: Int = FOCUS_PORT): Boolean = runCatching {
            java.net.Socket(InetAddress.getLoopbackAddress(), port).use { sock ->
                sock.getOutputStream().write("focus\n".toByteArray())
                sock.getOutputStream().flush()
            }
            true
        }.getOrDefault(false)

        /**
         * Первая копия: слушать просьбы поднять окно.
         *
         * [onFocus] вызывается из потока accept; null -- порт занят, функция
         * доработала без фокуса.
         */
        fun startFocusListener(onFocus: () -> Unit, port: Int = FOCUS_PORT): ServerSocket? =
            runCatching {
                val server = ServerSocket(port, 8, InetAddress.getLoopbackAddress())
                Thread {
                    while (!server.isClosed) {
                        val client = runCatching { server.accept() }.getOrNull() ?: break
                        client.use { sock ->
                            val line = runCatching {
                                sock.getInputStream().bufferedReader().readLine()
                            }.getOrNull()
                            if (line == "focus") onFocus()
                        }
                    }
                }.apply {
                    isDaemon = true
                    name = "zapp-focus"
                    start()
                }
                server
            }.getOrNull()
    }
}
