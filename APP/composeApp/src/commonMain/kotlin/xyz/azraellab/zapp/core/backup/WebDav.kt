package xyz.azraellab.zapp.core.backup

import kotlinx.serialization.Serializable
import kotlin.io.encoding.Base64

/** Данные входа на WebDAV-сервер. */
@Serializable
data class WebDavConfig(
    val url: String = "",
    val login: String = "",
    val password: String = ""
) {
    /** Заполнен ли адрес и логин; пароль может быть пустым. */
    val ready: Boolean get() = url.isNotBlank() && login.isNotBlank()

    /** Имя файла бэкапа на сервере. */
    val fileUrl: String get() = url.trimEnd('/') + "/zapp-backup.zip"
}

/** Basic-auth заголовок. */
fun webDavAuth(login: String, password: String): String =
    "Basic " + Base64.Default.encode("$login:$password".encodeToByteArray())

/**
 * Загрузка и выгрузка бэкапа на WebDAV-сервер.
 *
 * HTTP держится снаружи (put/get), чтобы клиент был тестируем без сети.
 * false/null при любом отказе -- сервер недоступен, адрес неверный,
 * нет прав; вызывающий сам решает, что показать.
 */
class WebDavClient(
    private val put: suspend (url: String, auth: String, bytes: ByteArray) -> Boolean,
    private val get: suspend (url: String, auth: String) -> ByteArray?
) {
    /** Кладёт архив на сервер. */
    suspend fun upload(config: WebDavConfig, bytes: ByteArray): Boolean {
        if (!config.ready) return false
        return runCatching {
            put(config.fileUrl, webDavAuth(config.login, config.password), bytes)
        }.getOrDefault(false)
    }

    /** Тянет архив с сервера; null -- нет файла или сервер недоступен. */
    suspend fun download(config: WebDavConfig): ByteArray? {
        if (!config.ready) return null
        return runCatching {
            get(config.fileUrl, webDavAuth(config.login, config.password))
        }.getOrNull()
    }
}

/** HTTP-шлюз для клиента; реализация -- в jvmShared. */
expect suspend fun webDavPut(url: String, auth: String, bytes: ByteArray): Boolean

/** HTTP-шлюз для клиента; реализация -- в jvmShared. */
expect suspend fun webDavGet(url: String, auth: String): ByteArray?

/** Клиент поверх платформенного HTTP. */
fun createWebDavClient(): WebDavClient = WebDavClient(::webDavPut, ::webDavGet)
