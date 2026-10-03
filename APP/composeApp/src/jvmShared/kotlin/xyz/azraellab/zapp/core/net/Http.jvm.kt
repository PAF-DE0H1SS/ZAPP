package xyz.azraellab.zapp.core.net

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Общая реализация GET-запроса для обеих JVM-платформ.
 *
 * Всё тело уже написано в [httpGet]; здесь только перенос в IO-диспетчер:
 * сетевой вызов из UI-корутины заблокировал бы кадр.
 */
actual suspend fun httpGetText(url: String): String? = withContext(Dispatchers.IO) {
    httpGet(url)
}
