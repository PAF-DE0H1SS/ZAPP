package xyz.azraellab.zapp.core.native

import xyz.azraellab.zapp.core.daemon.Mutation
import java.io.File

/**
 * Мост к нативному ядру: C++ (JNI, разбор iptables) + Rust (сверка).
 *
 * Три языка, один путь ответственности: план сверки решает Rust,
 * строки из `iptables -S` режет C++, а Kotlin остаётся оркестратором
 * и фолбэком -- библиотека может не подняться (чужая архитектура,
 * сборка без тулчейнов), и тогда работает та же логика на Kotlin
 * (Reconcile.kt, строковый разбор в DpiFirewall). Обе ветки обязаны
 * отдавать одинаковый результат -- это держит паритетный тест.
 *
 * Загрузка одна на обе платформы: Android несёт мост в той же .so,
 * что и счётчик трафика (System.loadLibrary), десктоп -- в ресурсах
 * jar, откуда файл распаковывается во временную папку под System.load.
 */
internal object ZappNative {

    /** Библиотека поднялась; false -- вызовы вернут null, путь -- Kotlin. */
    val available: Boolean = runCatching {
        System.loadLibrary("zapp-traffic")
    }.recoverCatching { loadFromClasspath() }.isSuccess

    /**
     * Тела правил живой цепочки; null -- библиотека не поднялась
     * (вызывающий идёт в свой строковый разбор).
     */
    fun parseChain(output: String): List<String>? {
        if (!available) return null
        return runCatching { nativeParseChain(output)?.toList() }.getOrNull()
    }

    /**
     * План сверки из ядра; null -- ядро не поднялось или формат
     * не собрался (вызывающий идёт в reconcilePlan).
     */
    fun plan(desired: List<String>, actual: List<String>): List<Mutation>? {
        if (!available) return null
        return runCatching {
            nativePlanReconcile(desired.toTypedArray(), actual.toTypedArray())
                ?.mapNotNull { encoded ->
                    // Формат ядра: "+rule" -- добавить, "-rule" -- удалить.
                    if (encoded.length < 2) return@mapNotNull null
                    Mutation(rule = encoded.substring(1), add = encoded[0] == '+')
                }
        }.getOrNull()
    }

    /**
     * Sha256 байтов; null -- ядро не поднялось (вызывающий идёт
     * в MessageDigest).
     */
    fun sha256Hex(bytes: ByteArray): String? {
        if (!available) return null
        return runCatching { nativeSha256Hex(bytes) }.getOrNull()
    }

    /**
     * Sha256 файла; null -- ядро не поднялось или файл не читается
     * (вызывающий идёт в свой потоковый хеш).
     */
    fun sha256FileHex(path: String): String? {
        if (!available) return null
        return runCatching { nativeSha256File(path) }.getOrNull()
    }

    /**
     * Пользовательская аргументная строка на отдельные аргументы;
     * null -- ядро не поднялось (фолбэк -- токенизатор на Kotlin).
     */
    fun tokenizeArgs(input: String): List<String>? {
        if (!available) return null
        return runCatching { nativeTokenizeArgs(input)?.toList() }.getOrNull()
    }

    /**
     * Подстановка $HOSTS активным списком доменов; null -- ядро
     * не поднялось (фолбэк -- та же логика на Kotlin).
     */
    fun expandHosts(tokens: List<String>, hosts: List<String>): List<String>? {
        if (!available) return null
        return runCatching {
            nativeExpandHosts(tokens.toTypedArray(), hosts.toTypedArray())?.toList()
        }.getOrNull()
    }

    /** IPv4 в 32 бита; null -- нет ядра или неверный формат. */
    fun parseIpv4(text: String): UInt? {
        if (!available) return null
        return runCatching {
            nativeParseIpv4(text).takeIf { it >= 0 }?.toUInt()
        }.getOrNull()
    }

    /** IPv6, восемь групп по 16 бит; null -- нет ядра или неверный формат. */
    fun parseIpv6(text: String): List<Int>? {
        if (!available) return null
        return runCatching { nativeParseIpv6(text)?.toList() }.getOrNull()
    }

    /** Литерал IP, а не домен; null -- нет ядра. */
    fun isIpLiteral(text: String): Boolean? {
        if (!available) return null
        return runCatching { nativeIsIpLiteral(text) }.getOrNull()
    }

    /** Адрес в подсети CIDR; null -- нет ядра. */
    fun cidrContains(cidr: String, address: String): Boolean? {
        if (!available) return null
        return runCatching { nativeCidrContains(cidr, address) }.getOrNull()
    }

    /** Десктоп: .so лежит в ресурсах jar, во время -- во временный файл. */
    private fun loadFromClasspath() {
        val bytes = ZappNative::class.java.getResourceAsStream("/libzappjni.so")
            ?.use { it.readBytes() }
            ?: error("libzappjni.so нет в classpath")
        val file = File.createTempFile("zappjni", ".so")
        file.deleteOnExit()
        file.writeBytes(bytes)
        System.load(file.absolutePath)
    }

    // Контракт с zapp_jni.cpp: имена JNI -- по имени класса и метода.
    private external fun nativeParseChain(output: String): Array<String>?
    private external fun nativePlanReconcile(
        desired: Array<String>,
        actual: Array<String>
    ): Array<String>?
    private external fun nativeSha256Hex(bytes: ByteArray): String?
    private external fun nativeSha256File(path: String): String?
    private external fun nativeTokenizeArgs(input: String): Array<String>?
    private external fun nativeExpandHosts(
        tokens: Array<String>,
        hosts: Array<String>
    ): Array<String>?
    private external fun nativeParseIpv4(text: String): Long
    private external fun nativeParseIpv6(text: String): IntArray?
    private external fun nativeIsIpLiteral(text: String): Boolean
    private external fun nativeCidrContains(cidr: String, address: String): Boolean
}
