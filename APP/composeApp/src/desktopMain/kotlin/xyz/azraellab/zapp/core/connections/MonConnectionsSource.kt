package xyz.azraellab.zapp.core.connections

import java.io.IOException
import java.net.SocketTimeoutException

/**
 * ConnectionsSource поверх демона zappmon.
 *
 * Снимки приходят сами: клиент ждёт ближайшее письмо (демон шлёт их раз
 * в полсекунды), поэтому период опроса в AppState остался прежним, а на
 * каждом тике больше не спавнятся ss/lsof. Отказ демона -- не пустая
 * таблица, а исключение: [FallbackConnectionsSource] отдаёт опросный
 * путь, и UI не врёт «соединений нет».
 *
 * Политика восстановления простая: обрыв сбрасывает сессию, и следующий
 * тик подключается заново (демон спавнится снова); таймаут сессию не
 * сбрасывает -- разовый глоток машины не должен убивать живой монитор.
 */
internal class MonConnectionsSource(
    private val connect: () -> MonSession?,
    private val timeoutMs: Int = READ_TIMEOUT_MS
) : ConnectionsSource {

    private var session: MonSession? = null

    override fun read(): ConnectionsSample {
        val active = session ?: openSession()
        val snapshot = try {
            active.readSnapshot(timeoutMs)
        } catch (e: SocketTimeoutException) {
            throw IOException("zappmon: timeout")
        } catch (e: Exception) {
            drop()
            throw IOException("zappmon: ${e.message ?: e::class.simpleName.orEmpty()}")
        }
        return ConnectionsSample(snapshot.rows, snapshot.note.toNote())
    }

    /** Новое подключение: прошлая сессия могла умереть между тиками. */
    private fun openSession(): MonSession {
        drop()
        return connect()?.also { session = it }
            ?: throw IOException("zappmon: not running")
    }

    /** Сокет закрыт, демон снят: следующий тик подключится заново. */
    private fun drop() {
        session?.let { runCatching { it.close() } }
        session = null
    }

    private fun Int.toNote(): ConnNote? = when (this) {
        2 -> ConnNote.NO_PROC
        3 -> ConnNote.NO_TOOL
        else -> null
    }

    private companion object {
        const val READ_TIMEOUT_MS = 700
    }
}
