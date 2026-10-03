package xyz.azraellab.zapp.core.net

import android.content.Intent
import android.net.Uri
import xyz.azraellab.zapp.core.AndroidCtx

/** Открывает ссылку системным браузером. */
actual fun openUrl(url: String): Boolean {
    val context = AndroidCtx.current ?: return false
    return runCatching {
        context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
        true
    }.getOrDefault(false)
}
