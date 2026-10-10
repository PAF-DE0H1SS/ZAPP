package xyz.azraellab.zapp.core.update

import android.content.Intent
import androidx.core.content.FileProvider
import java.io.File
import xyz.azraellab.zapp.core.AndroidCtx

/**
 * Установка скачанного APK.
 *
 * Файл отдаётся через FileProvider -- прямая ссылка file:// на Android 7+
 * роняет установку с FileUriExposedException. Сам установщик открывается
 * с флагом чтения URI; дальнейший диалог «разрешить установку из
 * неизвестных источников» -- уже работа системы.
 */
actual fun installUpdateApk(path: String): Boolean {
    val context = AndroidCtx.current ?: return false
    val file = File(path)
    if (!file.exists()) return false
    return runCatching {
        val uri = FileProvider.getUriForFile(
            context,
            context.packageName + ".fileprovider",
            file
        )
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "application/vnd.android.package-archive")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(intent)
        true
    }.getOrDefault(false)
}
