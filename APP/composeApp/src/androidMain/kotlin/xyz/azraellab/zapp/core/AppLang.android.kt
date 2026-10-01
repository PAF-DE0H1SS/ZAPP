package xyz.azraellab.zapp.core

import android.content.Context
import android.os.Build
import java.util.Locale

actual object AppLangStore {
    private const val PREFS = "zapp_lang"
    private const val KEY = "lang"

    actual fun read(): String? = runCatching {
        val prefs = AndroidCtx.require().getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        prefs.getString(KEY, null)?.trim()?.takeIf { it.isNotBlank() }
    }.getOrNull()

    actual fun write(code: String): Boolean = runCatching {
        AndroidCtx.require()
            .getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY, code)
            .commit()
    }.getOrDefault(false)

    actual fun systemCode(): String? {
        val locale = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            AndroidCtx.require().resources.configuration.locales[0]
        } else {
            @Suppress("DEPRECATION")
            AndroidCtx.require().resources.configuration.locale
        } ?: return null
        // Именно toLanguageTag(), а не getLanguage(): регион нужен для разбора.
        // В Молдове язык системы -- `ro`, а русский интерфейс там всё равно нужен,
        // и без `ro-MD` правило «СНГ -> русский» не срабатывает.
        // На старых прошивках locale может оказаться пустым, и toLanguageTag()
        // тогда отдаёт "und", а не исключение.
        return locale.toLanguageTag().takeIf { it.isNotBlank() && it != "und" }
    }
}

/** Текущий язык процесса. Смена языка на Android всегда упирается в пересоздание activity. */
fun applySystemLocale(lang: AppLang): Locale = Locale.forLanguageTag(lang.code)
