package xyz.azraellab.zapp.core

import android.content.Context

/**
 * Контекст приложения. Compose Multiplatform на Android не даёт доступа к контексту
 * из common-кода, поэтому он прокидывается один раз из Activity при старте процесса.
 */
object AndroidCtx {
    @Volatile
    private var app: Context? = null

    fun attach(context: Context) {
        if (app == null) app = context.applicationContext
    }

    fun require(): Context = app
        ?: error("AndroidCtx не инициализирован: MainActivity.attach() не вызван")
}

actual object AppThemeStore {
    private const val PREFS = "zapp_theme"
    private const val KEY = "mode"

    actual fun read(): String? = runCatching {
        val prefs = AndroidCtx.require().getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        prefs.getString(KEY, null)?.trim()?.takeIf { it.isNotBlank() }
    }.getOrNull()

    actual fun write(mode: String): Boolean = runCatching {
        AndroidCtx.require()
            .getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY, mode)
            .commit()
    }.getOrDefault(false)
}