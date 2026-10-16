package xyz.azraellab.zapp.core.secure

/**
 * Android: отдельное хранилище секретов не подключено.
 *
 * Файл настроек лежит во внутреннем хранилище приложения (песочница,
 * дисковое шифрование устройства), а системный Keystore потребовал бы
 * отдельного слоя шифрования -- он здесь не входит в задачу. Хранилище
 * честно отвечает `false`, и ключи остаются в JSON, как и раньше.
 */
actual fun platformSecureStorage(): SecureStorage = object : SecureStorage {
    override val available: Boolean get() = false
    override fun put(slot: String, value: String): Boolean = false
    override fun get(slot: String): String? = null
    override fun remove(slot: String) = Unit
}
