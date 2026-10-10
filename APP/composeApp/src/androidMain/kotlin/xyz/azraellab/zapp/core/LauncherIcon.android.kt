package xyz.azraellab.zapp.core

import android.app.Activity
import android.app.Application
import android.app.Application.ActivityLifecycleCallbacks
import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import android.os.Bundle

/**
 * Иконка приложения на Android: переключение activity-alias.
 *
 * В манифесте два alias'а над одной MainActivity -- `.LauncherLock`
 * (enabled по умолчанию) и `.LauncherUnlock`. Логика переключения
 * осознанно читает текущее состояние, а не ставит вслепую: каждый
 * старт приложения не должен рассылать лаунчеру change-событие, если
 * иконка уже правильная. Один из компонентов всегда включён, иначе
 * лаунчер потеряет точку входа и иконка пропадёт совсем.
 *
 * Disable запущенного компонента система финишит немедленно: окно
 * исчезнет, на экране останется лаунчер -- приложение «сворачивалось»
 * бы посреди подключения. Поэтому, пока активити на виду, пара
 * операций откладывается до onStop: остановленную активити система
 * уберёт незаметно для пользователя. Когда активити в процессе нет
 * (только служба), переключение применяется сразу.
 */
actual object LauncherIcon {

    private var cached: Boolean? = null

    /** Созданные активити: ненулевое значение до первого resume -- гонка холодного старта. */
    private var alive = 0

    /** Хоть один активити довели до resume: после этого «невидимость» значима. */
    private var everResumed = false

    /** Стартованные (видимые) активити: >0 -- disable сейчас уронит окно. */
    private var visible = 0

    /** Отложенное переключение: true -- ждём подключения, false -- ждём отключения. */
    private var pending: Boolean? = null

    private var watching = false

    @Synchronized
    actual fun setConnected(connected: Boolean) {
        if (cached == connected) return
        val context = AndroidCtx.current ?: return
        val pm = context.packageManager
        val pkg = context.packageName

        val lockWanted = !connected
        if (stateOf(pm, pkg, LOCK_SUFFIX, defaultEnabled = true) == lockWanted &&
            stateOf(pm, pkg, UNLOCK_SUFFIX, defaultEnabled = false) == connected
        ) {
            cached = connected
            pending = null
            return
        }

        watch(context.applicationContext)

        if (visible > 0 || (alive > 0 && !everResumed)) {
            pending = connected
            return
        }

        pending = null
        apply(pm, pkg, lockWanted, connected)
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

    private fun apply(
        pm: PackageManager,
        pkg: String,
        lockWanted: Boolean,
        connected: Boolean
    ) {
        if (stateOf(pm, pkg, LOCK_SUFFIX, defaultEnabled = true) != lockWanted) {
            set(pm, pkg, LOCK_SUFFIX, lockWanted)
        }
        if (stateOf(pm, pkg, UNLOCK_SUFFIX, defaultEnabled = false) != connected) {
            set(pm, pkg, UNLOCK_SUFFIX, connected)
        }
        cached = connected
    }

    private fun set(pm: PackageManager, pkg: String, suffix: String, enable: Boolean) {
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

    /**
     * Регистрирует счётчик жизненного цикла.
     *
     * Зовётся из Application.onCreate: только там регистрация гарантированно
     * опережает onCreate первой активити, иначе её старт проскакивает мимо
     * колбэков, счётчики остаются нулевыми -- и переключение решает, что
     * окна нет, хотя оно на виду.
     */
    fun install(context: Context) {
        watch(context.applicationContext)
    }

    /**
     * Счётчик жизненного цикла активити. Регистрируется один раз:
     * два alias'а ходят в одну и ту же MainActivity, переживают её
     * переиспользование и не появляются одновременно.
     */
    private fun watch(context: Context) {
        if (watching) return
        val app = context as? Application ?: return
        watching = true
        app.registerActivityLifecycleCallbacks(object : ActivityLifecycleCallbacks {

            override fun onActivityCreated(activity: Activity, state: Bundle?) {
                synchronized(this@LauncherIcon) { alive++ }
            }

            override fun onActivityStarted(activity: Activity) {
                synchronized(this@LauncherIcon) { visible++ }
            }

            override fun onActivityResumed(activity: Activity) {
                synchronized(this@LauncherIcon) { everResumed = true }
            }

            override fun onActivityPaused(activity: Activity) = Unit

            override fun onActivityStopped(activity: Activity) {
                synchronized(this@LauncherIcon) {
                    visible--
                    val wanted = pending
                    if (visible <= 0 && wanted != null && alive > 0) {
                        // Окно уже не на виду: disable уберёт активити без
                        // следа для пользователя.
                        pending = null
                        val context = AndroidCtx.current ?: return
                        val pm = context.packageManager
                        val pkg = context.packageName
                        apply(pm, pkg, lockWanted = !wanted, connected = wanted)
                    }
                }
            }

            override fun onActivitySaveInstanceState(activity: Activity, state: Bundle) = Unit

            override fun onActivityDestroyed(activity: Activity) {
                synchronized(this@LauncherIcon) {
                    alive--
                    if (alive > 0) return
                    everResumed = false
                    // Активити ушла, так и не дойдя до resume (гонка холодного
                    // старта): применяем вручную, иначе отложенное переключение
                    // провисит до следующего изменения состояния туннеля.
                    val wanted = pending
                    if (visible <= 0 && wanted != null) {
                        pending = null
                        val context = AndroidCtx.current ?: return
                        val pm = context.packageManager
                        val pkg = context.packageName
                        apply(pm, pkg, lockWanted = !wanted, connected = wanted)
                    }
                }
            }
        })
    }

    private const val LOCK_SUFFIX = ".LauncherLock"
    private const val UNLOCK_SUFFIX = ".LauncherUnlock"
}
