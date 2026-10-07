package xyz.azraellab.zapp.core.dev

import java.io.File
import xyz.azraellab.zapp.core.log.appDataDir

// Общий для Android и Desktop: каталог данных у платформы один и тот же
// механизм, что и у zapp.log, поэтому лог тестов ложится рядом с ним.

internal actual fun writeDevTestLog(fileName: String, text: String): String? =
    runCatching {
        val dir = appDataDir()
        dir.mkdirs()
        val file = File(dir, fileName)
        file.writeText(text)
        file.absolutePath
    }.getOrNull()
