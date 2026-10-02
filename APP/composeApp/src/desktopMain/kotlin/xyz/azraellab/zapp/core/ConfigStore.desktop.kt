package xyz.azraellab.zapp.core

import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Хранилище на десктопе.
 *
 * Всё лежит рядом в одном каталоге `~/.config/zapp`, как принято для нативных
 * программ в Unix. Файлы пишутся через временный с последующим переименованием:
 * если приложение упадёт посередине записи, останется прежний валидный файл,
 * а не обрезанный.
 */
actual fun createConfigStore(): ConfigStore = FileConfigStore()

private class FileConfigStore(
    private val root: File = defaultRoot()
) : ConfigStore {

    init {
        if (!root.exists()) root.mkdirs()
    }

    private val configFile: File get() = File(root, "config.json")
    private val presetsDir: File get() = File(root, "presets")

    override fun readConfig(): AppConfig? = runCatching {
        if (configFile.isFile) {
            ConfigCodec.decodeConfigOrNull(configFile.readText())
        } else {
            null
        }
    }.getOrNull()

    override fun writeConfig(config: AppConfig) {
        writeAtomically(configFile, ConfigCodec.encodeConfig(config))
    }

    override fun readPresets(): List<Preset> = runCatching {
        if (!presetsDir.isDirectory) return@runCatching emptyList()
        presetsDir.listFiles()
            ?.filter { it.isFile && it.extension == "json" }
            ?.mapNotNull { file -> ConfigCodec.decodePresetOrNull(file.readText()) }
            ?.sortedByDescending { it.createdAtMs }
            .orEmpty()
    }.getOrDefault(emptyList())

    override fun writePreset(preset: Preset) {
        if (!presetsDir.exists()) presetsDir.mkdirs()
        val file = File(presetsDir, "${safeName(preset.name)}.json")
        writeAtomically(file, ConfigCodec.encodePreset(preset))
    }

    override fun deletePreset(id: String) {
        val name = safeName(id)
        runCatching { File(presetsDir, "$name.json").delete() }
    }

    /**
     * Запись через временный файл.
     *
     * `renameTo` на том же каталоге атомарен в Unix и Windows, поэтому
     * читатель никогда не увидит наполовину записанный JSON.
     */
    private fun writeAtomically(target: File, content: String) {
        val temp = File(target.parentFile, "${target.name}.tmp")
        temp.writeText(content)
        if (!temp.renameTo(target)) {
            // На Windows rename может не сработать, если файл занят.
            target.delete()
            temp.renameTo(target)
        }
    }

    private fun safeName(raw: String): String {
        val cleaned = raw.trim().ifEmpty { "preset" }.replace(Regex("[^A-Za-z0-9._-]+"), "_")
        return cleaned.take(64)
    }

    private companion object {
        /** Уважаем XDG, иначе берём домашний каталог. */
        fun defaultRoot(): File {
            val configHome = System.getenv("XDG_CONFIG_HOME")
            val base = configHome?.takeIf { it.isNotBlank() }
                ?: (System.getProperty("user.home") ?: ".") + "/.config"
            return File(base, "zapp")
        }
    }
}

/**
 * Файловый обмен на десктопе.
 *
 * Диалог системный, а не нарисованный: `FileDialog` умеет всё, что умеет
 * родной диалог ОС, включая «Недавние файлы», и не требует UI-зависимостей.
 *
 * Диалог блокирующий, поэтому он поднимается в отдельном потоке и главный
 * поток ждёт результат. Если вызвать `isVisible = true` на EDT из потока
 * интерфейса, приложение замрёт насмерть -- это известная ловушка AWT.
 */
actual fun createPresetFileGateway(): PresetFileGateway = AwtPresetFileGateway()

private class AwtPresetFileGateway : PresetFileGateway {

    override val available: Boolean
        get() = !java.awt.GraphicsEnvironment.isHeadless()

    override suspend fun read(): String? {
        val file = choose(
            title = "Open preset",
            save = false,
            extension = "json"
        ) ?: return null
        return runCatching { file.readText() }.getOrNull()
    }

    override suspend fun write(content: String, suggestedName: String): Boolean {
        val file = choose(
            title = "Save preset",
            save = true,
            extension = "json",
            suggestedName = suggestedName
        ) ?: return false
        return runCatching {
            file.parentFile?.mkdirs()
            file.writeText(content)
            true
        }.getOrDefault(false)
    }

    /**
     * Показывает системный диалог и ждёт выбора.
     *
     * Диалог блокирующий, поэтому и сам выбор, и чтение файла уходят в
     * `Dispatchers.IO`: иначе вызов `isVisible = true` остановит поток
     * интерфейса, а это известная ловушка AWT.
     *
     * В headless-окружении (CI, сервер) диалога нет, и метод честно
     * возвращает null, вместо того чтобы падать с `HeadlessException`.
     */
    private suspend fun choose(
        title: String,
        save: Boolean,
        extension: String,
        suggestedName: String? = null
    ): File? = withContext(Dispatchers.IO) {
        if (java.awt.GraphicsEnvironment.isHeadless()) return@withContext null

        val chosen = java.util.concurrent.ArrayBlockingQueue<File?>(1)
        val worker = Thread {
            chosen.offer(runCatching {
                val owner = java.awt.Frame()
                val dialog = if (save) {
                    java.awt.FileDialog(owner, title, java.awt.FileDialog.SAVE)
                } else {
                    java.awt.FileDialog(owner, title, java.awt.FileDialog.LOAD)
                }
                if (suggestedName != null) dialog.file = suggestedName
                dialog.isVisible = true
                val name = dialog.file
                val directory = dialog.directory
                owner.dispose()
                if (name == null || directory == null) {
                    null
                } else {
                    val base = if (name.endsWith(".$extension")) name else "$name.$extension"
                    File(directory, base)
                }
            }.getOrNull())
        }
        worker.isDaemon = true
        worker.start()

        // Ограничение по времени, чтобы висящий диалог не держал вызывающий поток.
        chosen.poll(FILE_DIALOG_TIMEOUT_MS, java.util.concurrent.TimeUnit.MILLISECONDS)
    }

    private companion object {
        const val FILE_DIALOG_TIMEOUT_MS = 120_000L
    }
}
