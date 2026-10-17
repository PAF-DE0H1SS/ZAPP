package xyz.azraellab.zapp.core.connections

import xyz.azraellab.zapp.core.log.AppLog

/**
 * Демон первым, опросный путь -- запасным.
 *
 * Пока монитор отвечает, ss/lsof не трогаются вовсе. Как только он
 * замолчал или умер, каждый тик молча откатывается на прежний источник:
 * таблица остаётся живой, а диагностическая строка в журнале остаётся
 * ровно одна -- на сам факт отката, а не на каждый тик.
 */
internal class FallbackConnectionsSource(
    private val primary: ConnectionsSource,
    private val fallback: ConnectionsSource
) : ConnectionsSource {

    @Volatile
    private var announced = false

    override fun read(): ConnectionsSample = try {
        primary.read()
    } catch (e: Exception) {
        if (!announced) {
            announced = true
            AppLog.log(
                "conn",
                "zappmon unavailable: ${e.message ?: e::class.simpleName.orEmpty()}, polling"
            )
        }
        fallback.read()
    }
}
