package xyz.azraellab.zapp.core

import android.content.Context
import android.net.Uri
import kotlin.coroutines.resume
import kotlinx.coroutines.suspendCancellableCoroutine

/**
 * Хранилище на Android.
 *
 * Настройки лежат во внутреннем хранилище приложения: доступ есть только у
 * самого приложения, и в отличие от внешнего каталога их не видно другим
 * программам и не нужно запрашивать разрешение.
 *
 * Файл настроек -- плоский JSON без шифрования. Ключи VPN относятся к
 * чувствительным данным, но шифрование означало бы потерю возможности
 * восстановить настройки из резервной копии после переустановки, а это тот
 * самый случай, ради которого пресеты вообще выгружаются в файл.
 */
actual fun createConfigStore(): ConfigStore = AndroidConfigStore()

private class AndroidConfigStore(
    private val context: Context = AppContextHolder.current
) : ConfigStore {

    private val configFile get() = java.io.File(context.filesDir, "config.json")
    private val presetsDir get() = java.io.File(context.filesDir, "presets")

    override fun readConfig(): AppConfig? = runCatching {
        if (configFile.isFile) ConfigCodec.decodeConfigOrNull(configFile.readText()) else null
    }.getOrNull()

    override fun writeConfig(config: AppConfig) {
        writeAtomically(configFile, ConfigCodec.encodeConfig(config))
    }

    override fun readPresets(): List<Preset> = runCatching {
        if (!presetsDir.isDirectory) return@runCatching emptyList()
        presetsDir.listFiles()
            ?.filter { it.isFile && it.extension == "json" }
            ?.mapNotNull { ConfigCodec.decodePresetOrNull(it.readText()) }
            ?.sortedByDescending { it.createdAtMs }
            .orEmpty()
    }.getOrDefault(emptyList())

    override fun writePreset(preset: Preset) {
        if (!presetsDir.exists()) presetsDir.mkdirs()
        val file = java.io.File(presetsDir, "${safeName(preset.name)}.json")
        writeAtomically(file, ConfigCodec.encodePreset(preset))
    }

    override fun deletePreset(id: String) {
        runCatching { java.io.File(presetsDir, "${safeName(id)}.json").delete() }
    }

    /**
     * Запись через временный файл.
     *
     * Переименование поверх существующего файла атомарно, поэтому прерывание
     * записи не оставит обрезанный JSON.
     */
    private fun writeAtomically(target: java.io.File, content: String) {
        val temp = java.io.File(target.parentFile, "${target.name}.tmp")
        temp.writeText(content)
        if (!temp.renameTo(target)) {
            target.delete()
            temp.renameTo(target)
        }
    }

    private fun safeName(raw: String): String =
        raw.trim().ifEmpty { "preset" }
            .replace(Regex("[^A-Za-z0-9._-]+"), "_")
            .take(64)
}

/**
 * Файловый обмен на Android через Storage Access Framework.
 *
 * Система сама показывает выбор файла и выдаёт доступ, поэтому никаких прав на
 * внешний накопитель не нужно -- это единственный способ, который работает на
 * Android 11 и новее.
 *
 * Мост живёт в [SafPresetBridge] и устанавливается из Activity: launchers
 * результата Activity нельзя создать из обычного класса, у него нет доступа к
 * жизненному циклу. Пока мост не установлен, [available] равен false, и
 * интерфейс прячет кнопки импорта и экспорта, а не показывает нерабочие.
 */
actual fun createPresetFileGateway(): PresetFileGateway = SafPresetBridge

private object SafPresetBridge : PresetFileGateway {
    private var context: Context? = null
    private var pickRead: (((Uri?) -> Unit) -> Unit)? = null
    private var pickWrite: ((String, (Uri?) -> Unit) -> Unit)? = null

    /** Вызывается из Activity один раз, до первого открытия настроек. */
    fun install(
        appContext: Context,
        onPickRead: (((Uri?) -> Unit) -> Unit),
        onPickWrite: ((String, (Uri?) -> Unit) -> Unit)
    ) {
        context = appContext.applicationContext
        pickRead = onPickRead
        pickWrite = onPickWrite
    }

    override val available: Boolean
        get() = context != null && pickRead != null && pickWrite != null

    override suspend fun read(): String? {
        val resolver = context ?: return null
        val launcher = pickRead ?: return null
        val uri = awaitUri(launcher) ?: return null
        return runCatching {
            resolver.contentResolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() }
        }.getOrNull()
    }

    override suspend fun write(content: String, suggestedName: String): Boolean {
        val resolver = context ?: return false
        val launcher = pickWrite ?: return false
        val uri = awaitUriNamed(launcher, suggestedName) ?: return false
        return runCatching {
            resolver.contentResolver.openOutputStream(uri, "wt")?.bufferedWriter()?.use {
                it.write(content)
            } != null
        }.getOrDefault(false)
    }

    /**
     * Ждёт URI от Activity.
     *
     * Проверка `isActive` нужна, если корутину отменили, пока диалог открыт:
     * колбэк всё равно придёт, и возобновлять уже никому не нужную корутину
     * нельзя. Отдельно ничего отменять не приходится -- отмена корутины сама
     * приводит к тому, что результат игнорируется.
     */
    private suspend fun awaitUri(launcher: ((Uri?) -> Unit) -> Unit): Uri? =
        suspendCancellableCoroutine { continuation ->
            launcher { uri -> if (continuation.isActive) continuation.resume(uri) }
        }

    /**
     * То же, но с именем файла: `CreateDocument` требует его до показа диалога,
     * и решение о названии принимает Activity, а не хранилище.
     */
    private suspend fun awaitUriNamed(
        launcher: (String, (Uri?) -> Unit) -> Unit,
        name: String
    ): Uri? = suspendCancellableCoroutine { continuation ->
        launcher(name) { uri -> if (continuation.isActive) continuation.resume(uri) }
    }
}

/**
 * Установка моста файлового обмена.
 *
 * Вызывается из Activity, потому что launchers результата Activity создаются
 * только там. Отдельная функция вместо доступа к объекту: `internal`
 * оставляет возможность сменить реализацию, не трогая вызывающий код.
 */
fun installPresetFileBridge(
    context: Context,
    onPickRead: (((Uri?) -> Unit) -> Unit),
    onPickWrite: ((String, (Uri?) -> Unit) -> Unit)
) {
    SafPresetBridge.install(context, onPickRead, onPickWrite)
    AppContextHolder.install(context)
}

/**
 * Контекст приложения.
 *
 * Нужен потому, что хранилище создаётся на уровне core без composable и без
 * передачи зависимостей сверху. [Context] в статическом поле живёт столько же,
 * сколько процесс, что для настроек безопасно: это не утечка Activity, потому
 * что хранится `applicationContext`, а не сама Activity.
 */
internal object AppContextHolder {
    @Volatile
    private var appContext: Context? = null

    val current: Context
        get() = appContext ?: error("AppContextHolder не инициализирован")

    fun install(context: Context) {
        appContext = context.applicationContext
    }
}
