package xyz.azraellab.zapp.core.deeplink

/**
 * Разобранный deep-link `zapp://`.
 *
 * Два действия: `zapp://import?url=...` тянет конфиг по ссылке,
 * `zapp://connect?profile=...` выбирает профиль по имени или id.
 */
sealed class DeepLink {
    /** Импорт конфига по прямой ссылке. */
    data class Import(val url: String) : DeepLink()

    /** Выбор профиля по имени или id. */
    data class Connect(val profile: String) : DeepLink()
}

private const val SCHEME = "zapp://"

/**
 * Разбирает deep-link; null — чужая схема, неизвестное действие или
 * нет нужного параметра.
 *
 * Чистая функция без сети и платформы: разбор живёт в commonMain,
 * а превращение интента в строку — работа платформы.
 */
fun parseDeepLink(uri: String): DeepLink? {
    val text = uri.trim()
    if (!text.startsWith(SCHEME, ignoreCase = true)) return null
    val rest = text.substring(SCHEME.length)
    val cut = rest.indexOfFirst { it == '?' || it == '/' }
    val action = (if (cut >= 0) rest.substring(0, cut) else rest).lowercase()
    val query = if (cut >= 0 && rest[cut] == '?') rest.substring(cut + 1) else ""
    val params = parseQuery(query)
    return when (action) {
        "import" -> params["url"]?.takeIf { it.isNotBlank() }?.let { DeepLink.Import(it) }
        "connect" -> params["profile"]?.takeIf { it.isNotBlank() }?.let { DeepLink.Connect(it) }
        else -> null
    }
}

/** `a=1&b=2` -> пара ключ/значение с раскодированными значениями. */
private fun parseQuery(query: String): Map<String, String> =
    query.split('&').mapNotNull { pair ->
        val idx = pair.indexOf('=')
        if (idx <= 0) return@mapNotNull null
        percentDecode(pair.substring(0, idx)) to percentDecode(pair.substring(idx + 1))
    }.toMap()

/** Раскодирует `%XX` и `+` (пробел) в строке query. */
private fun percentDecode(s: String): String {
    val out = ArrayList<Byte>(s.length)
    var i = 0
    while (i < s.length) {
        when (val c = s[i]) {
            '+' -> {
                out.add(0x20)
                i += 1
            }

            '%' -> {
                val b = if (i + 2 < s.length) s.substring(i + 1, i + 3).toIntOrNull(16) else null
                if (b != null) {
                    out.add(b.toByte())
                    i += 3
                } else {
                    out.add(c.code.toByte())
                    i += 1
                }
            }

            else -> {
                c.toString().encodeToByteArray().forEach { out.add(it) }
                i += 1
            }
        }
    }
    return out.toByteArray().decodeToString()
}
