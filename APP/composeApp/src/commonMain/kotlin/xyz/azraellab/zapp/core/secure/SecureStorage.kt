package xyz.azraellab.zapp.core.secure

import kotlinx.serialization.Serializable

/**
 * Хранилище секретов: приватные ключи живут у платформы, а не в файле
 * настроек рядом с остальным конфигом.
 *
 * Механизм у каждой платформы свой -- DPAPI под учётной записью на
 * Windows, Secret Service на Linux, Keychain на macOS. Платформа без
 * механизма отвечает [available] == false: тогда настройки сохраняются
 * как раньше, с ключами в JSON, и ничего не ломается. На Android ключи
 * остаются в файле приложения -- хранилище закрыто песочницей, а
 * системный Keystore здесь не подключается.
 *
 * Слот -- имя секрета внутри хранилища, значение -- произвольный текст.
 */
interface SecureStorage {

    /** Есть ли сейчас механизм шифрования. */
    val available: Boolean

    /** Кладёт значение; false -- не сработало, значение не сохранено. */
    fun put(slot: String, value: String): Boolean

    /** Читает значение; null -- слота нет или прочитать не удалось. */
    fun get(slot: String): String?

    /** Удаляет секрет; отсутствие слота -- не ошибка. */
    fun remove(slot: String)
}

/** Платформенное хранилище секретов. */
expect fun platformSecureStorage(): SecureStorage

/**
 * Секреты одного профиля одним значением слота: три секретных поля плюс
 * текст wg-ini, потому что у импортированного конфига ключ дублируется
 * строкой `PrivateKey=` внутри [rawConfig].
 */
@Serializable
internal data class KeyBundle(
    val privateKey: String = "",
    val presharedKey: String = "",
    val certPassword: String = "",
    val rawConfig: String = ""
)
