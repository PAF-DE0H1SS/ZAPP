package xyz.azraellab.zapp.core.log

import java.io.File

// Общее для Android и Desktop: время одинаково, каталог данных -- у платформы.

internal actual fun platformNowMs(): Long = System.currentTimeMillis()

/** Каталог данных приложения (лог, временные файлы). */
internal expect fun appDataDir(): File

internal actual fun appendToFile(line: String) {
    runCatching {
        val dir = appDataDir()
        dir.mkdirs()
        File(dir, "zapp.log").appendText(line + "\n")
    }
}

internal actual fun clearFile() {
    runCatching {
        val file = File(appDataDir(), "zapp.log")
        if (file.exists()) file.writeText("")
    }
}
