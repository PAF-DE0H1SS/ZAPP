package xyz.azraellab.zapp.core

import java.io.File
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import xyz.azraellab.zapp.core.log.AppLog

/**
 * Реализация на десктопе.
 *
 * Движок работает на IO-корутине: каждый вызов -- внешняя команда
 * (reg/gsettings/networksetup), которая живёт сотни миллисекунд.
 * [SystemProxyEngine] -- чистая механика с подставными зависимостями
 * (команды, файл снимка, имя ОС, переменные окружения), поэтому вся
 * логика проверяется тестами без реальной системы.
 */
actual object SystemProxyCtl {

    private val engine by lazy {
        SystemProxyEngine(
            cmd = ProcessCmd,
            store = FileSnapshotStore(File(zappConfigRoot(), SNAPSHOT_NAME))
        )
    }

    actual suspend fun apply() {
        withContext(Dispatchers.IO) {
            runCatching { engine.applyNow() }
                .onSuccess { AppLog.log("sysproxy", if (it) "on" else "unsupported") }
                .onFailure { AppLog.log("sysproxy", "on failed: ${it.message ?: it::class.simpleName}") }
        }
    }

    actual suspend fun restore() {
        withContext(Dispatchers.IO) {
            runCatching { engine.restoreNow() }
                .onSuccess { restored -> if (restored) AppLog.log("sysproxy", "restored") }
                .onFailure { AppLog.log("sysproxy", "restore failed: ${it.message ?: it::class.simpleName}") }
        }
    }

    private const val SNAPSHOT_NAME = "systemproxy.json"
}

/** Исход одной внешней команды. */
internal data class CmdResult(val code: Int, val output: String)

/** Запуск внешней команды; подменяется тестами. */
internal fun interface CmdRunner {
    fun run(argv: List<String>): CmdResult
}

/** Реальный запуск через ProcessBuilder: argv без shell, поэтому `<`, `;`, `$` в данных безопасны. */
internal object ProcessCmd : CmdRunner {

    override fun run(argv: List<String>): CmdResult = runCatching {
        val process = ProcessBuilder(argv).redirectErrorStream(true).start()
        // stdin закрывается сразу: утилиты не должны ждать ввода.
        runCatching { process.outputStream.close() }
        val collected = StringBuilder()
        val reader = Thread {
            runCatching {
                process.inputStream.bufferedReader().forEachLine {
                    synchronized(collected) { collected.append(it).append('\n') }
                }
            }
        }.apply { isDaemon = true; start() }
        val finished = runCatching {
            process.waitFor(CMD_TIMEOUT_MS, TimeUnit.MILLISECONDS)
        }.getOrDefault(false)
        if (!finished) runCatching { process.destroyForcibly() }
        runCatching { reader.join(READER_JOIN_MS) }
        val output = synchronized(collected) { collected.toString() }
        CmdResult(if (finished) process.exitValue() else -1, output)
    }.getOrDefault(CmdResult(-1, ""))

    private const val CMD_TIMEOUT_MS = 5000L
    private const val READER_JOIN_MS = 300L
}

/**
 * Снимок прокси-состояния системы до вмешательства.
 *
 * Один плоский словарь строк на всю платформу: типы полей у трёх ОС
 * разные (режим, адрес, включён-ли, содержимое файла), а сериализуемый
 * словарь не требует ни полиморфизма, ни миграций.
 */
@Serializable
internal data class ProxySnapshot(
    val platform: String,
    val values: Map<String, String> = emptyMap()
)

/** Хранилище снимка; подменяется тестами. */
internal interface SnapshotStore {
    fun read(): ProxySnapshot?
    fun write(snapshot: ProxySnapshot)

    /** Удаляет снимок; ошибка удаления не должна ронять восстановление. */
    fun clear()
}

/** Снимок в файле рядом с config.json. */
internal class FileSnapshotStore(private val file: File) : SnapshotStore {

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    override fun read(): ProxySnapshot? = runCatching {
        if (file.isFile) json.decodeFromString(ProxySnapshot.serializer(), file.readText()) else null
    }.getOrNull()

    // write -- осознанно без runCatching: если снимок не записался,
    // применять прокси нельзя, иначе восстанавливать будет нечего.
    override fun write(snapshot: ProxySnapshot) {
        file.parentFile?.mkdirs()
        file.writeText(json.encodeToString(ProxySnapshot.serializer(), snapshot))
    }

    override fun clear() {
        runCatching { file.delete() }
    }
}

/** Один платформенный механизм прокси. */
internal interface ProxyOps {
    val platform: String

    /** Текущее состояние системы; null -- механизм здесь неприменим. */
    fun capture(): Map<String, String>?

    /** Включает свои входы, вернув true, если ядро настроено. */
    fun applyMark(host: String, socksPort: Int, httpPort: Int): Boolean

    /** Возвращает состояние из снимка. */
    fun restoreMark(values: Map<String, String>): Boolean
}

/**
 * Движок «зафиксировал -> изменил -> восстановил» (§72).
 *
 * Идемпотентность обязательна: пока снимок существует, повторный apply
 * -- no-op, иначе второй проход зафиксировал бы уже СВОЁ состояние и
 * прежний прокси пользователя потерялся бы навсегда. Упавшее применение
 * само откатывается по снимку.
 */
internal class SystemProxyEngine(
    private val cmd: CmdRunner,
    private val store: SnapshotStore,
    private val osName: String = System.getProperty("os.name").orEmpty(),
    private val env: (String) -> String? = System::getenv,
    private val userHome: String = System.getProperty("user.home").orEmpty()
) {

    fun ops(): ProxyOps? {
        val os = osName.lowercase()
        return when {
            os.contains("win") -> WindowsOps(cmd)
            os.contains("mac") -> MacOps(cmd)
            else -> linuxOps()
        }
    }

    private fun linuxOps(): ProxyOps? {
        val de = env("XDG_CURRENT_DESKTOP").orEmpty().lowercase()
        return when {
            LINUX_DESKTOPS.any { it in de } -> GnomeOps(cmd)
            "kde" in de -> KdeOps(cmd, envFile())
            else -> null
        }
    }

    fun applyNow(): Boolean {
        if (store.read() != null) return true
        val ops = ops() ?: return false
        val captured = ops.capture() ?: return false
        store.write(ProxySnapshot(ops.platform, captured))
        if (ops.applyMark(HOST, ProxyPorts.SOCKS, ProxyPorts.HTTP)) return true
        restoreNow()
        return false
    }

    fun restoreNow(): Boolean {
        val snapshot = store.read() ?: return false
        val ops = ops()
        if (ops == null || ops.platform != snapshot.platform) {
            store.clear()
            return false
        }
        val ok = runCatching { ops.restoreMark(snapshot.values) }.getOrDefault(false)
        if (ok) store.clear()
        return ok
    }

    /** Файл переменных окружения для KDE (systemd user environment.d). */
    private fun envFile(): File {
        val configHome = env("XDG_CONFIG_HOME")?.takeIf { it.isNotBlank() }
            ?: "$userHome/.config"
        return File(configHome, "environment.d/$ENV_FILE")
    }

    private companion object {
        const val HOST = "127.0.0.1"
        const val ENV_FILE = "zapp-proxy.conf"
        val LINUX_DESKTOPS = listOf("gnome", "unity", "cinnamon", "mate")
    }
}

/**
 * Windows: HKCU Internet Settings.
 *
 * ProxyServer пишется тройной схемой (http/https/socks), ProxyOverride
 * оставляет точечные имена локальных хостов без прокси, а уведомление
 * InternetSetOption (39 -- SETTINGS_CHANGED, 37 -- REFRESH) заставляет
 * уже запущенные программы перечитать реестр: без него изменения
 * видны только после перезапуска.
 */
internal class WindowsOps(private val cmd: CmdRunner) : ProxyOps {

    override val platform = "windows"

    override fun capture(): Map<String, String> {
        val values = mutableMapOf<String, String>()
        readValue("ProxyEnable")?.let { values["enable"] = it }
        readValue("ProxyServer")?.let { values["server"] = it }
        readValue("ProxyOverride")?.let { values["override"] = it }
        return values
    }

    override fun applyMark(host: String, socksPort: Int, httpPort: Int): Boolean {
        val server = "http=$host:$httpPort;https=$host:$httpPort;socks=$host:$socksPort"
        var ok = write("ProxyServer", "REG_SZ", server)
        ok = write("ProxyOverride", "REG_SZ", "<local>") && ok
        ok = write("ProxyEnable", "REG_DWORD", "1") && ok
        notifyUser()
        return ok
    }

    override fun restoreMark(values: Map<String, String>): Boolean {
        var ok = restoreOne("ProxyServer", "REG_SZ", values["server"])
        ok = restoreOne("ProxyOverride", "REG_SZ", values["override"]) && ok
        ok = restoreOne("ProxyEnable", "REG_DWORD", values["enable"]) && ok
        notifyUser()
        return ok
    }

    private fun readValue(name: String): String? {
        val result = cmd.run(listOf("reg", "query", KEY, "/v", name))
        if (result.code != 0) return null
        val line = result.output.lineSequence().firstOrNull {
            name in it && ("REG_SZ" in it || "REG_DWORD" in it)
        } ?: return null
        val marker = if ("REG_DWORD" in line) "REG_DWORD" else "REG_SZ"
        return line.substringAfter(marker).trim().ifEmpty { null }
    }

    private fun write(name: String, type: String, data: String): Boolean =
        cmd.run(listOf("reg", "add", KEY, "/v", name, "/t", type, "/d", data, "/f")).code == 0

    private fun restoreOne(name: String, type: String, previous: String?): Boolean {
        if (previous == null) {
            // Значения не было до нас -- своё удаляем. reg отвечает
            // ошибкой, если удалять уже нечего, и это не проблема.
            cmd.run(listOf("reg", "delete", KEY, "/v", name, "/f"))
            return true
        }
        return write(name, type, previous)
    }

    private fun notifyUser() {
        cmd.run(listOf(
            "powershell", "-NoProfile", "-Command",
            "Add-Type -Namespace ZappWin -Name Inet -MemberDefinition " +
                "'[System.Runtime.InteropServices.DllImport(\"wininet.dll\")] " +
                "public static extern bool InternetSetOption(System.IntPtr h, int o, byte[] b, int l);'; " +
                "[ZappWin.Inet]::InternetSetOption([System.IntPtr]::Zero, 39, ${'$'}null, 0) | Out-Null; " +
                "[ZappWin.Inet]::InternetSetOption([System.IntPtr]::Zero, 37, ${'$'}null, 0) | Out-Null"
        ))
    }

    private companion object {
        const val KEY = "HKCU\\Software\\Microsoft\\Windows\\CurrentVersion\\Internet Settings"
    }
}

/**
 * Linux/GNOME: схемы gsettings.
 *
 * Порядок применения -- сначала адреса, режим `manual` последним:
 * как только режим переключен, трафик уже должен находить рабочий
 * вход. Обратный порядок в восстановлении -- наоборот, сначала поля,
 * режим возвращается в конце, чтобы режим не включился раньше, чем
 * вернулись его адреса.
 */
internal class GnomeOps(private val cmd: CmdRunner) : ProxyOps {

    override val platform = "gnome"

    override fun capture(): Map<String, String>? {
        val mode = read(SCHEMA, "mode") ?: return null
        val values = mutableMapOf("mode" to mode)
        values["autoconfig-url"] = read(SCHEMA, "autoconfig-url").orEmpty()
        values["http.enabled"] = read(SCHEMA_HTTP, "enabled").orEmpty()
        values["http.host"] = read(SCHEMA_HTTP, "host").orEmpty()
        values["http.port"] = read(SCHEMA_HTTP, "port").orEmpty()
        values["socks.host"] = read(SCHEMA_SOCKS, "host").orEmpty()
        values["socks.port"] = read(SCHEMA_SOCKS, "port").orEmpty()
        return values
    }

    override fun applyMark(host: String, socksPort: Int, httpPort: Int): Boolean {
        var ok = write(SCHEMA_HTTP, "host", "'$host'")
        ok = write(SCHEMA_HTTP, "port", "$httpPort") && ok
        ok = write(SCHEMA_HTTP, "enabled", "true") && ok
        ok = write(SCHEMA_SOCKS, "host", "'$host'") && ok
        ok = write(SCHEMA_SOCKS, "port", "$socksPort") && ok
        ok = write(SCHEMA, "mode", "'manual'") && ok
        return ok
    }

    override fun restoreMark(values: Map<String, String>): Boolean {
        var ok = true
        // Поля -- режимом вниз: см. комментарий класса.
        listOfNotNull(
            values["http.enabled"]?.let { SCHEMA_HTTP to ("enabled" to it) },
            values["http.host"]?.let { SCHEMA_HTTP to ("host" to "'$it'") },
            values["http.port"]?.let { SCHEMA_HTTP to ("port" to it) },
            values["socks.host"]?.let { SCHEMA_SOCKS to ("host" to "'$it'") },
            values["socks.port"]?.let { SCHEMA_SOCKS to ("port" to it) },
            values["autoconfig-url"]?.let { SCHEMA to ("autoconfig-url" to "'$it'") }
        ).forEach { (schema, pair) ->
            ok = write(schema, pair.first, pair.second) && ok
        }
        values["mode"]?.let { ok = write(SCHEMA, "mode", "'$it'") && ok }
        return ok
    }

    /** Читает значение; вывод gsettings -- GVariant: строки в одинарных кавычках. */
    private fun read(schema: String, key: String): String? {
        val result = cmd.run(listOf("gsettings", "get", schema, key))
        if (result.code != 0) return null
        return result.output.trim().removeSurrounding("'")
    }

    private fun write(schema: String, key: String, gvariant: String): Boolean =
        cmd.run(listOf("gsettings", "set", schema, key, gvariant)).code == 0

    private companion object {
        const val SCHEMA = "org.gnome.system.proxy"
        const val SCHEMA_HTTP = "org.gnome.system.proxy.http"
        const val SCHEMA_SOCKS = "org.gnome.system.proxy.socks"
    }
}

/**
 * Linux/KDE: файл переменных окружения.
 *
 * KDE штатно читает прокси из kioslaverc, но обойтись без сессии KIO
 * проще общими переменными: их подхватывают консольные программы и
 * все, кто запущен через systemd user manager. Снимок -- прежнее
 * содержимое файла (или его отсутствие): восстановление либо пишет
 * старый текст обратно, либо удаляет свой файл.
 */
internal class KdeOps(private val cmd: CmdRunner, private val file: File) : ProxyOps {

    override val platform = "kde"

    override fun capture(): Map<String, String> = mapOf(
        "existed" to file.exists().toString(),
        "content" to if (file.exists()) runCatching { file.readText() }.getOrDefault("") else ""
    )

    override fun applyMark(host: String, socksPort: Int, httpPort: Int): Boolean {
        val http = "http://$host:$httpPort"
        val socks = "socks5://$host:$socksPort"
        val text = buildString {
            appendLine("HTTP_PROXY=$http")
            appendLine("HTTPS_PROXY=$http")
            appendLine("ALL_PROXY=$socks")
            appendLine("NO_PROXY=localhost,127.0.0.1")
            appendLine("http_proxy=$http")
            appendLine("https_proxy=$http")
            appendLine("all_proxy=$socks")
            append("no_proxy=localhost,127.0.0.1")
        }
        val written = runCatching {
            file.parentFile?.mkdirs()
            file.writeText(text)
        }.isSuccess
        publish(listOf(
            "HTTP_PROXY=$http", "HTTPS_PROXY=$http", "ALL_PROXY=$socks",
            "NO_PROXY=localhost,127.0.0.1",
            "http_proxy=$http", "https_proxy=$http", "all_proxy=$socks",
            "no_proxy=localhost,127.0.0.1"
        ))
        return written
    }

    override fun restoreMark(values: Map<String, String>): Boolean {
        val restored = runCatching {
            if (values["existed"] == "true") {
                file.parentFile?.mkdirs()
                file.writeText(values["content"].orEmpty())
            } else {
                file.delete()
                Unit
            }
        }.isSuccess
        unpublish()
        return restored
    }

    /** Доводит переменные до уже запущенного user manager; без сессии -- тихий промах. */
    private fun publish(pairs: List<String>) {
        cmd.run(listOf("systemctl", "--user", "set-environment") + pairs)
    }

    private fun unpublish() {
        cmd.run(listOf("systemctl", "--user", "unset-environment") + ENV_KEYS)
    }

    private companion object {
        val ENV_KEYS = listOf(
            "HTTP_PROXY", "HTTPS_PROXY", "ALL_PROXY", "NO_PROXY",
            "http_proxy", "https_proxy", "all_proxy", "no_proxy"
        )
    }
}

/**
 * macOS: networksetup на выбранном сервисе.
 *
 * Сервис определяется по интерфейсу маршрута по умолчанию (en0 ->
 * «Wi-Fi»): менять прокси «первого попавшегося» нельзя -- на Маках
 * с Ethernet и Wi-Fi это был бы не тот адаптер. Запасной путь --
 * первый включённый сервис из списка.
 */
internal class MacOps(private val cmd: CmdRunner) : ProxyOps {

    override val platform = "macos"

    override fun capture(): Map<String, String>? {
        val svc = service() ?: return null
        val values = mutableMapOf("service" to svc)
        for ((name, getter) in GETTERS) {
            val out = cmd.run(listOf("networksetup", getter, svc)).output
            values["$name.enabled"] =
                if (field(out, "Enabled").equals("Yes", ignoreCase = true)) "Yes" else "No"
            values["$name.server"] = field(out, "Server")
            values["$name.port"] = field(out, "Port")
        }
        return values
    }

    override fun applyMark(host: String, socksPort: Int, httpPort: Int): Boolean {
        val svc = service() ?: return false
        var ok = setProxy(SETTERS[0].second, svc, host, httpPort)
        ok = setProxy(SETTERS[1].second, svc, host, httpPort) && ok
        ok = setProxy(SETTERS[2].second, svc, host, socksPort) && ok
        return ok
    }

    override fun restoreMark(values: Map<String, String>): Boolean {
        val svc = values["service"] ?: return false
        var ok = true
        for ((name, setter) in SETTERS) {
            ok = restoreOne(name, setter, svc, values) && ok
        }
        return ok
    }

    private fun restoreOne(
        name: String,
        setter: Pair<String, String>,
        svc: String,
        values: Map<String, String>
    ): Boolean {
        if (values["$name.enabled"] != "Yes") {
            return cmd.run(listOf("networksetup", setter.second, svc, "off")).code == 0
        }
        val server = values["$name.server"].orEmpty()
        val port = values["$name.port"]?.toIntOrNull() ?: 0
        return setProxy(setter, svc, server, port)
    }

    private fun setProxy(setter: Pair<String, String>, svc: String, host: String, port: Int): Boolean {
        val set = cmd.run(listOf("networksetup", setter.first, svc, host, "$port")).code == 0
        val state = cmd.run(listOf("networksetup", setter.second, svc, "on")).code == 0
        return set && state
    }

    private fun service(): String? {
        val route = cmd.run(listOf("route", "-n", "get", "default"))
        if (route.code == 0) {
            val iface = route.output.lineSequence()
                .firstOrNull { it.trim().startsWith("interface:") }
                ?.substringAfter(":")?.trim()
            if (iface != null) {
                val ports = cmd.run(listOf("networksetup", "-listallhardwareports"))
                var hardware: String? = null
                for (line in ports.output.lines()) {
                    when {
                        line.startsWith("Hardware Port:") ->
                            hardware = line.substringAfter(":").trim()
                        line.startsWith("Device:") &&
                            line.substringAfter(":").trim() == iface &&
                            hardware != null ->
                            return hardware
                    }
                }
            }
        }
        val list = cmd.run(listOf("networksetup", "-listallnetworkservices"))
        return list.output.lines().drop(1)
            .firstOrNull { it.isNotBlank() && !it.startsWith("*") }
            ?.trim()
    }

    private fun field(out: String, name: String): String =
        out.lineSequence().firstOrNull { it.trim().startsWith("$name:") }
            ?.substringAfter(":")?.trim().orEmpty()

    private companion object {
        val GETTERS = listOf(
            "web" to "-getwebproxy",
            "secure" to "-getsecurewebproxy",
            "socks" to "-getsocksfirewallproxy"
        )
        val SETTERS = listOf(
            "web" to ("-setwebproxy" to "-setwebproxystate"),
            "secure" to ("-setsecurewebproxy" to "-setsecurewebproxystate"),
            "socks" to ("-setsocksfirewallproxy" to "-setsocksfirewallproxystate")
        )
    }
}
