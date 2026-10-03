package xyz.azraellab.zapp.core.net

import java.net.URI

/** Открывает ссылку в браузере по умолчанию. */
actual fun openUrl(url: String): Boolean = runCatching {
    java.awt.Desktop.getDesktop().browse(URI(url))
    true
}.getOrDefault(false)
