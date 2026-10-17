package xyz.azraellab.zapp.core.update

/**
 * Кеш APK обновлений на диске.
 *
 * Ключ кеша -- версия релиза: тот же файл повторно не качается, а с
 * выходом новой версии старый заменяется новым именем. Установка --
 * штатный установщик Android; на десктопе APK не нужен, и платформа
 * честно отвечает отказом.
 */

/** Путь к закешированному APK версии [version] или null, если файла нет. */
expect fun cachedUpdateApk(version: String): String?

/**
 * Кладёт APK версии [version] в кеш.
 *
 * true -- файл готов (скачан заново или уже лежал); false -- сеть
 * недоступна или файл оказался пустым.
 */
expect suspend fun downloadUpdateApk(
    version: String,
    url: String,
    onProgress: ((done: Long, total: Long) -> Unit)? = null
): Boolean

/** Открывает скачанный APK установщиком. false -- платформа не умеет. */
expect fun installUpdateApk(path: String): Boolean
