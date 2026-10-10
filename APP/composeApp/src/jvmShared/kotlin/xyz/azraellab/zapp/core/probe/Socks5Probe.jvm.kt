package xyz.azraellab.zapp.core.probe

import java.io.InputStream
import java.net.InetSocketAddress
import java.net.Socket

/**
 * Опрос готовности SOCKS5-сервера с дедлайном и повторами.
 *
 * Релей после acquire() поднимается в своём потоке, и между «порт занят»
 * и «цикл accept готов отвечать» может пройти мгновение -- поэтому проба
 * не однократная, а с дедлайном: каждые 100 мс новая попытка, пока не
 * пройдёт рукопожатие или не выйдет время. Порт открыт, но приветствие
 * не отвечает -- это не готовность, а зависший сервер, и после дедлайна
 * движок обязан честно уйти в ERROR, а не в «Работает».
 *
 * Возвращает true, как только сервер выбрал no-auth.
 */
internal fun socks5Ready(host: String, port: Int, timeoutMs: Int = 1500): Boolean {
    val deadline = System.currentTimeMillis() + timeoutMs
    while (true) {
        val remaining = deadline - System.currentTimeMillis()
        if (remaining <= 0L) return false
        if (socks5Attempt(host, port, remaining)) return true
        Thread.sleep(minOf(100L, remaining))
    }
}

/** Одна попытка рукопожатия; любая ошибка -- «сейчас не готов», не сбой. */
private fun socks5Attempt(host: String, port: Int, remainingMs: Long): Boolean {
    return try {
        val budget = remainingMs.coerceIn(1L, Int.MAX_VALUE.toLong()).toInt()
        Socket().use { socket ->
            socket.tcpNoDelay = true
            socket.connect(InetSocketAddress(host, port), budget)
            socket.soTimeout = budget
            socket.getOutputStream().apply {
                write(socks5Greeting())
                flush()
            }
            socks5SelectedNoAuth(socket.getInputStream().readExactly(2))
        }
    } catch (_: Exception) {
        false
    }
}

/** Ровно n байт: частичный ответ -- не ответ, это не готовность. */
private fun InputStream.readExactly(n: Int): ByteArray? {
    val buffer = ByteArray(n)
    var read = 0
    while (read < n) {
        val got = read(buffer, read, n - read)
        if (got < 0) return null
        read += got
    }
    return buffer
}
