package xyz.azraellab.zapp

import android.app.Application
import xyz.azraellab.zapp.core.AndroidCtx
import xyz.azraellab.zapp.core.BuildMode
import xyz.azraellab.zapp.core.LauncherIcon

/**
 * Точка старта процесса.
 *
 * Две обязанности, обе -- до любых движков: включить счётчик жизненного
 * цикла активити для переключения иконки подключения и подготовить
 * почву для запусков без окна. Плитки шторки уведомлений поднимают
 * процесс сами, минуя Activity, и к их первому тапу режим сборки и
 * контекст обязаны уже быть: из onCreate Application колбэки видят
 * и первую активити, и TileService.
 */
class ZappApp : Application() {

    override fun onCreate() {
        super.onCreate()
        // Флаг режима (root/noroot): root-билд имеет право на su, noroot --
        // не дёргает Magisk вовсе. Раньше строка жила в MainActivity, но
        // та не гарантирована при старте из шторки.
        BuildMode.rootAllowed = BuildConfig.ZAPP_ROOT
        BuildMode.buildMark = BuildConfig.ZAPP_BUILD_MARK
        AndroidCtx.attach(this)
        LauncherIcon.install(this)
    }
}
