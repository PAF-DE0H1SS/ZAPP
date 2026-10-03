package xyz.azraellab.zapp.core.log

import java.io.File
import xyz.azraellab.zapp.core.AndroidCtx

// Android: файлы приложения в filesDir.

internal actual fun appDataDir(): File =
    AndroidCtx.current?.filesDir ?: File(System.getProperty("java.io.tmpdir"), "zapp")
