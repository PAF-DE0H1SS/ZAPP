package xyz.azraellab.zapp.core

import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import android.util.AtomicFile
import xyz.azraellab.zapp.core.log.AppLog
import java.io.File

/**
 * Единый реестр автозапуска: [AtomicFile] в noBackupFilesDir.
 *
 * Две задачи разом: ресивер BOOT_COMPLETED читает план напрямую, не
 * поднимая `AppState`, а пустой план гасит ресивер через
 * `setComponentEnabledSetting` -- система перестаёт будить приложение,
 * когда автозапуск не нужен. Выключение -- состояние компонента, оно
 * переживает обновления и не сбрасывается переустановкой данных.
 *
 * Запись атомарна: крэш посреди сохранения оставляет старый план, а не
 * полусобранный JSON, который ресивер читал бы как «автозапуск не нужен».
 */
object BootRegistry {

    private const val FILE = "boot-registry.json"

    /** Класс ресивера строкой: он живёт в модуле :app, здесь его класс не виден. */
    private const val RECEIVER_CLASS = "xyz.azraellab.zapp.BootAutostart"

    /** Пишет план атомарно и приводит ресивер к его пустоте. */
    fun sync(context: Context, plan: BootPlan) {
        write(context, plan)
        toggleReceiver(context, enabled = !plan.isEmpty)
    }

    /**
     * План из реестра; null -- реестра ещё нет (установка со старой
     * версии, конфиг после обновления ещё не сохраняли).
     */
    fun read(context: Context): BootPlan? {
        val file = File(context.noBackupFilesDir, FILE)
        if (!file.exists()) return null
        return runCatching {
            file.bufferedReader().use { ConfigCodec.json.decodeFromString(BootPlan.serializer(), it.readText()) }
        }.onFailure {
            AppLog.log("boot", "registry read failed: ${it.message}")
        }.getOrNull()
    }

    private fun write(context: Context, plan: BootPlan) {
        val atomic = AtomicFile(File(context.noBackupFilesDir, FILE))
        var out: java.io.FileOutputStream? = null
        try {
            out = atomic.startWrite()
            out.write(ConfigCodec.json.encodeToString(BootPlan.serializer(), plan).toByteArray())
            atomic.finishWrite(out)
        } catch (e: Exception) {
            if (out != null) atomic.failWrite(out)
            AppLog.log("boot", "registry write failed: ${e.message}")
        }
    }

    private fun toggleReceiver(context: Context, enabled: Boolean) {
        val component = ComponentName(context.packageName, RECEIVER_CLASS)
        val target = if (enabled) {
            PackageManager.COMPONENT_ENABLED_STATE_ENABLED
        } else {
            PackageManager.COMPONENT_ENABLED_STATE_DISABLED
        }
        runCatching {
            context.packageManager.setComponentEnabledSetting(
                component, target, PackageManager.DONT_KILL_APP
            )
        }.onFailure {
            AppLog.log("boot", "receiver toggle failed: ${it.message}")
        }
    }
}
