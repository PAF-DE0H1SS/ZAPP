package xyz.azraellab.zapp.core.net

import kotlinx.serialization.Serializable

/**
 * Схема ссылки конфига.
 *
 * Разные приложения зовут одно и то же по-разному: `ss` и `shadowsocks`,
 * `hysteria2` и `hy2`. Перечислены оба написания, а распознаётся входящая
 * строка по списку, а не по одному каноническому имени.
 */
@Serializable
enum class ProxyScheme(val uri: String, val aliases: List<String>) {
    VLESS("vless", emptyList()),
    VMESS("vmess", emptyList()),
    TROJAN("trojan", emptyList()),
    SHADOWSOCKS("ss", listOf("shadowsocks")),
    HYSTERIA2("hysteria2", listOf("hy2")),
    TUIC("tuic", emptyList()),
    SOCKS("socks", listOf("socks5", "socks4")),
    HTTP("http", emptyList()),
    SSH("ssh", emptyList());

    fun matches(scheme: String): Boolean {
        val s = scheme.lowercase()
        return s == uri || s in aliases
    }

    companion object {
        fun from(scheme: String): ProxyScheme? = entries.firstOrNull { it.matches(scheme) }
    }
}

/**
 * Конфиг подключения, приведённый к одному виду.
 *
 * Ссылки пяти форматов описывают одно и то же: куда стучаться, чем
 * аутентифицироваться, поверх какого транспорта и под каким именем показывать
 * в списке. Отсюда одна модель вместо пяти: экран списка, проверка живости и
 * экспорт не должны знать, откуда конфиг пришёл.
 *
 * Всё, что не вынесено в отдельное поле, лежит в [params] -- так обратный
 * вывод [ProxyUri.write] не теряет чужие параметры, которых мы просто пока
 * не знаем.
 *
 * [raw] -- исходная ссылка без изменений. По ней считается идентификатор,
 * по ней же конфиг можно вернуть в исходный вид, даже если разбор был
 * неточным.
 */
@Serializable
data class ProxyConfig(
    val raw: String,
    val scheme: ProxyScheme,
    val name: String = "",
    val server: String = "",
    val port: Int = 0,

    /** Аутентификация: vless/vmess -- uuid, trojan/ss/hysteria2 -- пароль. */
    val auth: String = "",
    /** Метод шифрования Shadowsocks (`aes-256-gcm` и подобные). */
    val cipher: String = "",

    /** Транспорт: `tcp`, `ws`, `grpc`, `http` и далее по ссылке. */
    val transport: String = "tcp",
    /** Защита канала: пусто, `tls` или `reality`. */
    val security: String = "",
    val sni: String = "",
    val host: String = "",
    val path: String = "",
    val alpn: String = "",
    val fingerprint: String = "",
    val flow: String = "",

    /** Остальные параметры ссылки: не потеряны и уходят обратно в URL. */
    val params: Map<String, String> = emptyMap()
) {
    /**
     * Идентификатор для списков и выбора активного конфига.
     *
     * Считается из ссылки, а не присваивается: один и тот же конфиг,
     * добавленный дважды, должен быть одним элементом, а не двумя
     * с разными номерами. Спецификация `String.hashCode` гарантирует
     * одинаковый результат на всех платформах.
     */
    fun id(): String = "${scheme.uri}:${raw.hashCode().toUInt().toString(16)}"

    /** Имя для списка: фрагмент ссылки, а если его нет -- адрес. */
    fun displayName(): String = name.ifBlank { "$server:$port" }

    val isComplete: Boolean
        get() = server.isNotBlank() && port in 1..65535 && auth.isNotBlank()
}
