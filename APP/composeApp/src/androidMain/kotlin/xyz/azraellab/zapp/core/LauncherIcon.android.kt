package xyz.azraellab.zapp.core

import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager

/**
 * Иконка приложения на Android: переключение activity-alias.
 *
 * В манифесте два alias'а над одной MainActivity -- `.LauncherLock`
 * (enabled по умолчанию) и `.LauncherUnlock`. Логика переключения
 * осознанно читает текущее состояние, а не ставит вслепую: каждый
 * старт приложения не должен рассылать лаунчеру change-событие, если
 * иконка уже правильная. Один из компонентов всегда включён, иначе
 * лаунчер потеряет точку входа и иконка пропадёт совсем.
 */
actual object LauncherIcon {

    private var cached: Boolean? = null

    actual fun setConnected(connected: Boolean) {
        if (cached == connected) return
        val context = AndroidCtx.current ?: return
        val pm = context.packageManager
        val pkg = context.packageName

        val lockWanted = !connected
        val unlockWanted = connected
        if (stateOf(pm, pkg, LOCK_SUFFIX, defaultEnabled = true) != lockWanted) {
            apply(pm, pkg, LOCK_SUFFIX, lockWanted)
        }
        if (stateOf(pm, pkg, UNLOCK_SUFFIX, defaultEnabled = false) != unlockWanted) {
            apply(pm, pkg, UNLOCK_SUFFIX, unlockWanted)
        }
        cached = connected
    }

    /**
     * Фактическое состояние компонента.
     *
     * DEFAULT означает «как в манифесте»: lock включён там, unlock -- нет,
     * и без этого чтения каждый запуск ставил бы компоненты заново.
     */
    private fun stateOf(
        pm: PackageManager,
        pkg: String,
        suffix: String,
        defaultEnabled: Boolean
    ): Boolean = when (pm.getComponentEnabledSetting(ComponentName(pkg, pkg + suffix))) {
        PackageManager.COMPONENT_ENABLED_STATE_ENABLED -> true
        PackageManager.COMPONENT_ENABLED_STATE_DISABLED -> false
        else -> defaultEnabled
    }

    private fun apply(pm: PackageManager, pkg: String, suffix: String, enable: Boolean) {
        runCatching {
            pm.setComponentEnabledSetting(
                ComponentName(pkg, pkg + suffix),
                if (enable) {
                    PackageManager.COMPONENT_ENABLED_STATE_ENABLED
                } else {
                    PackageManager.COMPONENT_ENABLED_STATE_DISABLED
                },
                // Без убийства процесса: смена иконки -- не повод ронять
                // идущее подключение.
                PackageManager.DONT_KILL_APP
            )
        }
    }

    private const val LOCK_SUFFIX = ".LauncherLock"
    private const val UNLOCK_SUFFIX = ".LauncherUnlock"
}
