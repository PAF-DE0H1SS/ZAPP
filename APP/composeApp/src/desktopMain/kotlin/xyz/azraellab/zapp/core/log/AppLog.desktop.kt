package xyz.azraellab.zapp.core.log

import java.io.File

// Desktop: каталог данных рядом с конфигом, как принято в Unix.

internal actual fun appDataDir(): File =
    File(File(System.getProperty("user.home"), ".config"), "zapp")
