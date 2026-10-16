package xyz.azraellab.zapp.core.secure

import java.io.File
import java.util.Base64
import java.util.concurrent.TimeUnit
import xyz.azraellab.zapp.core.CmdResult
import xyz.azraellab.zapp.core.CmdRunner
import xyz.azraellab.zapp.core.ProcessCmd
import xyz.azraellab.zapp.core.zappConfigRoot

actual fun platformSecureStorage(): SecureStorage =
    SecureStorageEngine(cmd = ProcessCmd, dir = { File(zappConfigRoot(), SECRETS_DIR) })

/**
 * Движок секретов с подставными зависимостями: команды ОС и каталог
 * слотов подменяются тестами, вся механика считается без реальной системы.
 *
 * Windows: DPAPI под текущим пользователем -- значения лежат отдельными
 * файлами рядом с настройками, но прочитать их вне учётной записи нельзя.
 * Linux: Secret Service через `secret-tool` (libsecret); если утилиты или
 * демона нет, хранилище недоступно. macOS: Keychain через `security`.
 */
internal class SecureStorageEngine(
    private val cmd: CmdRunner,
    private val dir: () -> File,
    private val osName: String = System.getProperty("os.name").orEmpty(),
    private val stdin: (List<String>, String) -> CmdResult = { argv, input -> runWithInput(argv, input) }
) : SecureStorage {

    private val windows get() = osName.contains("win", true)
    private val macos get() = osName.contains("mac", true)

    override val available: Boolean by lazy {
        when {
            windows || macos -> true
            else -> cmd.run(listOf(SECRET_TOOL, "--help")).ok
        }
    }

    override fun put(slot: String, value: String): Boolean = when {
        windows -> windowsPut(slot, value)
        macos -> cmd.run(
            listOf("security", "add-generic-password", "-s", SERVICE, "-a", slot, "-w", value, "-U")
        ).ok
        else -> available && stdin(listOf(SECRET_TOOL, "store", "--label=$SERVICE", "key", slot), value).ok
    }

    override fun get(slot: String): String? = when {
        windows -> windowsGet(slot)
        macos -> cmd.run(listOf("security", "find-generic-password", "-s", SERVICE, "-a", slot, "-w"))
            .takeIf { it.ok }?.output?.trimEnd('\n', '\r')
        else -> if (available) {
            cmd.run(listOf(SECRET_TOOL, "lookup", "key", slot))
                .takeIf { it.ok }?.output?.trimEnd('\n', '\r')
        } else null
    }

    override fun remove(slot: String) {
        when {
            windows -> runCatching { slotFile(slot).delete() }
            macos -> runCatching {
                cmd.run(listOf("security", "delete-generic-password", "-s", SERVICE, "-a", slot))
            }
            else -> if (available) runCatching {
                cmd.run(listOf(SECRET_TOOL, "clear", "key", slot))
            }
        }
    }

    /** Защита значения ключом пользователя; файл пишется только после успеха. */
    private fun windowsPut(slot: String, value: String): Boolean {
        val encoded = Base64.getEncoder().encodeToString(value.toByteArray(Charsets.UTF_8))
        val script = "Add-Type -AssemblyName System.Security; " +
            "\$b = [Convert]::FromBase64String('$encoded'); " +
            "\$e = [Security.Cryptography.ProtectedData]::Protect(" +
            "\$b, \$null, [Security.Cryptography.DataProtectionScope]::CurrentUser); " +
            "[Convert]::ToBase64String(\$e)"
        val encrypted = ps(script).let { if (it.ok) it.output.trim() else "" }
        if (encrypted.isEmpty()) return false
        // Пишем через временный файл: обрыв посередине не оставит
        // полузаписанное значение, которое не прочитается ни восстановление.
        return runCatching {
            val file = slotFile(slot)
            file.parentFile?.mkdirs()
            val tmp = File(file.parentFile, file.name + ".tmp")
            tmp.writeText(encrypted)
            if (file.exists()) file.delete()
            tmp.renameTo(file)
        }.getOrDefault(false)
    }

    /** Снятие защиты; чужая учётная запись и повреждённый файл дают null. */
    private fun windowsGet(slot: String): String? {
        val encoded = runCatching {
            val file = slotFile(slot)
            if (file.isFile) file.readText().trim() else ""
        }.getOrNull()?.takeIf { it.isNotEmpty() } ?: return null
        val script = "Add-Type -AssemblyName System.Security; " +
            "\$b = [Convert]::FromBase64String('$encoded'); " +
            "\$d = [Security.Cryptography.ProtectedData]::Unprotect(" +
            "\$b, \$null, [Security.Cryptography.DataProtectionScope]::CurrentUser); " +
            "[Text.Encoding]::UTF8.GetString(\$d)"
        val result = ps(script)
        return if (result.ok) result.output.trimEnd('\n', '\r') else null
    }

    private fun ps(script: String): CmdResult = cmd.run(
        listOf(
            POWERSHELL, "-NoProfile", "-Command",
            "\$ErrorActionPreference='Stop'; [Console]::OutputEncoding=[Text.Encoding]::UTF8; $script"
        )
    )

    /** Имя файла слота: слоты приходят из профилей, в пути -- только безопасные символы. */
    private fun slotFile(slot: String): File {
        val safe = buildString {
            for (c in slot.take(SLOT_MAX)) {
                val plain = c in 'a'..'z' || c in 'A'..'Z' || c in '0'..'9' || c == '.' || c == '-' || c == '_'
                append(if (plain) c else '_')
            }
        }
        return File(dir(), "$safe.dpapi")
    }
}

/** Команда со значением во входном потоке: secret-tool берёт секрет из stdin. */
private fun runWithInput(argv: List<String>, input: String): CmdResult = runCatching {
    val process = ProcessBuilder(argv).redirectErrorStream(true).start()
    val collected = StringBuilder()
    val reader = Thread {
        runCatching {
            process.inputStream.bufferedReader().forEachLine {
                synchronized(collected) { collected.append(it).append('\n') }
            }
        }
    }.apply { isDaemon = true; start() }
    runCatching {
        process.outputStream.buffered().use { it.write(input.toByteArray(Charsets.UTF_8)) }
    }
    val finished = runCatching { process.waitFor(INPUT_TIMEOUT_MS, TimeUnit.MILLISECONDS) }.getOrDefault(false)
    if (!finished) runCatching { process.destroyForcibly() }
    runCatching { reader.join(READER_JOIN_MS) }
    val output = synchronized(collected) { collected.toString() }
    CmdResult(if (finished) process.exitValue() else -1, output)
}.getOrDefault(CmdResult(-1, ""))

private const val POWERSHELL = "powershell"
private const val SECRET_TOOL = "secret-tool"
private const val SERVICE = "ZAPP"
private const val SECRETS_DIR = "secrets"
private const val SLOT_MAX = 64
private const val INPUT_TIMEOUT_MS = 5000L
private const val READER_JOIN_MS = 300L
