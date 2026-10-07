package xyz.azraellab.zapp.core.dev

// Desktop: «debug» -- запуск из каталога классов (gradle run, IDE), «релиз» --
// jar внутри дистрибутива jpackage. Плюс явный ключ -Dzapp.debug=true для
// тех случаев, когда нужно включить вкладку нестандартным запуском.

internal actual fun isDebugBuild(): Boolean {
    if (System.getProperty("zapp.debug") == "true") return true
    val location = runCatching {
        DevTestRegistry::class.java.protectionDomain?.codeSource?.location?.toString()
    }.getOrNull() ?: return false
    return !location.endsWith(".jar")
}
