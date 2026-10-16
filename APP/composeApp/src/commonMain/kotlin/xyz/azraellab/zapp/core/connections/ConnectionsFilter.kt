package xyz.azraellab.zapp.core.connections

/**
 * Фильтр таблицы соединений: по процессу, адресу и порту (§31).
 *
 * Чистая логика без UI, чтобы гоняться в тестах: поиск по подстроке
 * для процесса и адреса, совпадение номера -- по локальному порту и
 * по порту удалённой стороны (то, что набирают как «443»).
 */
object ConnectionsFilter {

    fun apply(rows: List<ConnRow>, query: String): List<ConnRow> {
        val q = query.trim()
        if (q.isEmpty()) return rows
        return rows.filter { row ->
            row.owner.contains(q, ignoreCase = true) ||
                row.remote.contains(q, ignoreCase = true) ||
                row.proto.contains(q, ignoreCase = true) ||
                q.toIntOrNull() == row.localPort ||
                row.remote.endsWith(":$q")
        }
    }
}
