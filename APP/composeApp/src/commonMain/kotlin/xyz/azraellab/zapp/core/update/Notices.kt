package xyz.azraellab.zapp.core.update

/**
 * Объявление разработчика из тела релиза.
 *
 * Фид обновлений (`/releases/latest`) -- единственный канал: бэкенда у
 * приложения нет, а релиз и так разбирается при проверке обновлений.
 */
data class NoticeInfo(
    /** Стабильный хеш текста: закрытое объявление не всплывает. */
    val id: String,
    val title: String,
    val content: String = "",
    /** Ссылка из объявления; пусто -- кнопки «Открыть» нет. */
    val url: String = "",
    /** Срок годности (эпоха, мс); 0 -- бессрочное. */
    val expiresMs: Long = 0L
)

/** Метка блока в теле релиза: `<!-- zapp-notice ... -->`. */
private const val NOTICE_MARK = "<!-- zapp-notice"

/**
 * Разбирает блок объявления из тела релиза; null -- блока нет или он битый.
 *
 * Формат: метка, строки `title:` / `expires:` / `url:` (необязательные,
 * кроме title), остальные строки -- текст объявления. Битая дата срока
 * гасит всё объявление: висеть вечно под видом срочного -- хуже, чем не
 * показать. Немаркированный changelog возвращает null как есть.
 */
fun parseNotice(body: String): NoticeInfo? {
    val start = body.indexOf(NOTICE_MARK)
    if (start < 0) return null
    val end = body.indexOf("-->", start + NOTICE_MARK.length)
    if (end < 0) return null
    var title = ""
    var url = ""
    var expires = 0L
    val text = StringBuilder()
    for (raw in body.substring(start + NOTICE_MARK.length, end).lines()) {
        val line = raw.trim()
        when {
            line.isEmpty() -> Unit
            line.startsWith("title:") -> title = line.removePrefix("title:").trim()
            line.startsWith("expires:") ->
                expires = parseExpiry(line.removePrefix("expires:").trim()) ?: return null
            line.startsWith("url:") -> {
                val candidate = line.removePrefix("url:").trim()
                if (candidate.startsWith("http://") || candidate.startsWith("https://")) {
                    url = candidate
                }
            }
            else -> {
                if (text.isNotEmpty()) text.append('\n')
                text.append(line)
            }
        }
    }
    if (title.isEmpty()) return null
    return NoticeInfo(
        id = noticeId(title, text.toString()),
        title = title,
        content = text.toString(),
        url = url,
        expiresMs = expires
    )
}

/** `2026-11-01` -> конец этой даты (эпоха, мс); null -- формат битый. */
private fun parseExpiry(value: String): Long? {
    val parts = value.split('-')
    if (parts.size != 3) return null
    val y = parts[0].toIntOrNull() ?: return null
    val m = parts[1].toIntOrNull() ?: return null
    val d = parts[2].toIntOrNull() ?: return null
    if (m !in 1..12 || d !in 1..31) return null
    return (epochDay(y, m, d) + 1) * 86_400_000L
}

/** Дней от эпохи по календарной дате (алгоритм Ховарда Хиннанта). */
private fun epochDay(y: Int, m: Int, d: Int): Long {
    val yy = (if (m <= 2) y - 1 else y).toLong()
    val era = (if (yy >= 0) yy else yy - 399) / 400
    val yoe = yy - era * 400
    val doy = (153 * (if (m > 2) m - 3 else m + 9) + 2) / 5 + d - 1
    val doe = yoe * 365 + yoe / 4 - yoe / 100 + doy
    return era * 146097 + doe - 719468
}

/** Стабильный хеш текста объявления: другая формулировка -- новый ID. */
fun noticeId(title: String, content: String): String {
    var hash = 5381
    for (ch in "$title\u0000$content") hash = (hash shl 5) + hash + ch.code
    return (hash.toLong() and 0xFFFFFFFFL).toString(16)
}

/** Показывать ли объявление: не закрыто пользователем и не просрочено. */
fun noticeVisible(notice: NoticeInfo, nowMs: Long, dismissedId: String): Boolean =
    notice.id != dismissedId &&
        (notice.expiresMs == 0L || nowMs < notice.expiresMs)
