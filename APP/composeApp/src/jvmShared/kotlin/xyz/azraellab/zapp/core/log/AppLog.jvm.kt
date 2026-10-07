package xyz.azraellab.zapp.core.log

import java.io.File

// Общее для Android и Desktop: время одинаково, каталог данных -- у платформы.

internal actual fun platformNowMs(): Long = System.currentTimeMillis()

/** Каталог данных приложения (лог, временные файлы). */
internal expect fun appDataDir(): File

internal actual fun appendToFile(fileName: String, line: String) {
    runCatching {
        val dir = appDataDir()
        dir.mkdirs()
        File(dir, fileName).appendText(line + "\n")
    }
}

internal actual fun clearFile(fileName: String) {
    runCatching {
        val file = File(appDataDir(), fileName)
        if (file.exists()) file.writeText("")
    }
}
