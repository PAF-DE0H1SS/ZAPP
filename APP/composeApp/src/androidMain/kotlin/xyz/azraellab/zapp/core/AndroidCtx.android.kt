package xyz.azraellab.zapp.core

import android.content.Context

/**
 * Контекст приложения. Compose Multiplatform на Android не даёт доступа к контексту
 * из common-кода, поэтому он прокидывается один раз из Activity при старте процесса.
 *
 * Отдельный файл, а не вместе с хранилищем режима темы: у контекста разные
 * потребители (язык, хранилище настроек, пресеты), и привязывать его к тому,
 * чего в приложении больше нет, -- значит при следующей правке снова потерять.
 */
object AndroidCtx {
    @Volatile
    private var app: Context? = null

    fun attach(context: Context) {
        if (app == null) app = context.applicationContext
    }

    /** Контекст, если процесс уже поднят; null до первого attach(). */
    val current: Context? get() = app

    fun require(): Context = app
        ?: error("AndroidCtx не инициализирован: MainActivity.attach() не вызван")
}
