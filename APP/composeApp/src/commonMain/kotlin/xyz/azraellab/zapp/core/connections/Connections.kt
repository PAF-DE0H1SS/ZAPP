package xyz.azraellab.zapp.core.connections

/**
 * Одна строка живой таблицы соединений: кто к чему подключён.
 *
 * [owner] -- имя процесса (десктоп) или пакет/uid (Android); [remote]
 * -- адрес и порт удалённой стороны, "-" у слушателей и датаграмм без
 * адресата. Байты по сокету платформенные таблицы не считают, поэтому
 * колонки трафика здесь нет: точные счётчики есть только у ядра
 * (clash_api), а не у таблиц процесса.
 */
data class ConnRow(
    val proto: String,
    val localPort: Int,
    val remote: String,
    val owner: String
)

/** Почему таблица пуста: не «соединений нет», а «прочитать не смогли». */
enum class ConnNote {
    /** Файлы /proc/net недоступны (Android без чтения и без root). */
    NO_PROC,

    /** Системная утилита (ss, lsof, PowerShell) не запустилась. */
    NO_TOOL
}

/** Снимок таблицы соединений. */
data class ConnectionsSample(
    val rows: List<ConnRow>,
    val note: ConnNote? = null
) {
    companion object {
        val EMPTY = ConnectionsSample(emptyList())
    }
}

/** Источник таблицы соединений платформы. */
interface ConnectionsSource {
    fun read(): ConnectionsSample
}

expect fun createConnectionsSource(): ConnectionsSource
