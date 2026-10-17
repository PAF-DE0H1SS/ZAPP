package xyz.azraellab.zapp.core

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Реализация на десктопе: настоящая только на Windows.
 *
 * Все вызовы -- внешние команды (reg, CheckNetIsolation), поэтому на
 * IO-корутине. Каждый обёрнут в runCatching: утилита может отсутствовать
 * или отказать без прав -- это «не вышло», а не падение секции.
 */
actual object UwpLoopbackCtl {

    private val engine by lazy {
        UwpLoopbackEngine(cmd = ProcessCmd)
    }

    actual suspend fun families(): List<String> = withContext(Dispatchers.IO) {
        runCatching { engine.families() }.getOrDefault(emptyList())
    }

    actual suspend fun exempt(): Set<String> = withContext(Dispatchers.IO) {
        runCatching { engine.exempt() }.getOrDefault(emptySet())
    }

    actual suspend fun set(packageFamily: String, on: Boolean): Boolean =
        withContext(Dispatchers.IO) {
            runCatching { engine.set(packageFamily, on) }.getOrDefault(false)
        }
}

/**
 * Механика loopback-исключений с подставными командами.
 *
 * Список семейств берётся из реестра HKCU (семьи установленных AppX --
 * там же, где их ищут ручные инструкции по exemption), состояние --
 * у `CheckNetIsolation LoopbackExempt -s`, включение и снятие -- той же
 * утилитой с `-a`/`-d -p=`. На не-Windows команды не вызываются
 * вовсе: на Linux/macOS нет ни реестра, ни песочницы UWP.
 *
 * Парсеры (parseFamilies/parseExempt) -- чистые: вывод утилит
 * локализуется, поэтому они опираются на структуру (отступы reg,
 * без пробелов и с подчёркиванием у имен пакетов), а не на слова.
 */
internal class UwpLoopbackEngine(
    private val cmd: CmdRunner,
    private val osName: String = System.getProperty("os.name").orEmpty()
) {

    private val windows get() = osName.contains("win", true)

    /** Семейства установленных UWP-пакетов; ошибка команды -- пусто. */
    fun families(): List<String> {
        if (!windows) return emptyList()
        val result = cmd.run(listOf("reg", "query", FAMILIES_KEY))
        return if (result.ok) parseFamilies(result.output) else emptyList()
    }

    /** Семейства с разрешённым loopback. */
    fun exempt(): Set<String> {
        if (!windows) return emptySet()
        val result = cmd.run(listOf("CheckNetIsolation", "LoopbackExempt", "-s"))
        return if (result.ok) parseExempt(result.output) else emptySet()
    }

    /** Поставить или снять исключение для одного семейства. */
    fun set(packageFamily: String, on: Boolean): Boolean {
        if (!windows || packageFamily.isBlank()) return false
        val flag = if (on) "-a" else "-d"
        return cmd.run(listOf("CheckNetIsolation", "LoopbackExempt", flag, "-p=$packageFamily")).ok
    }
}

/**
 * Под первого уровня из вывода `reg query`: отступ ровно четыре
 * пробела. Вложенные значения (отступ больше) и сами ключи HKEY в
 * список не идут.
 */
internal fun parseFamilies(output: String): List<String> = buildList {
    for (raw in output.lineSequence()) {
        val line = raw.trimEnd()
        if (line.isBlank()) continue
        if (!line.startsWith("    ")) continue
        if (line.startsWith("     ")) continue
        val name = line.trim()
        if (name.startsWith("HKEY") || "REG_" in name) continue
        add(name)
    }
}.distinct()

/**
 * Пакетные имена из вывода `-s`.
 *
 * Имя -- только что-то без пробелов и с подчёркиванием (формат
 * «имя_хеш»): так отсекаются и заголовок, и любая локализация, где
 * «Имя:» по-своему. Имя берётся после последнего двоеточия, если оно
 * есть («Name: pkg»), и целиком, если строки уже готовое.
 */
internal fun parseExempt(output: String): Set<String> = buildSet {
    for (raw in output.lineSequence()) {
        val line = raw.trim()
        if (line.isEmpty()) continue
        val value = line.substringAfterLast(':').trim()
        if (value.isEmpty() || ' ' in value || '_' !in value) continue
        add(value)
    }
}

/** Семьи AppX в HKCU: путь из ручных инструкций по loopback-exemption. */
private const val FAMILIES_KEY =
    "HKCU\\Software\\Classes\\Local Settings\\Software\\Microsoft\\Windows\\CurrentVersion\\AppModel\\Repository\\Families"
