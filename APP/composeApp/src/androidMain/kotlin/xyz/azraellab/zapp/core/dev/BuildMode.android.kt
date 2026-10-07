package xyz.azraellab.zapp.core.dev

import android.content.pm.ApplicationInfo
import xyz.azraellab.zapp.core.AndroidCtx

// Android: debug -- это ровно тот APK, что собран с debuggable-манифестом.
// Контекст приложен в MainActivity при старте процесса; если его ещё нет
// (страница настроек физически не может открыться раньше), считаем релизом:
// вкладка с инструментами разработчика не должна показаться по недосмотру.

internal actual fun isDebugBuild(): Boolean {
    val context = AndroidCtx.current ?: return false
    val flags = context.applicationInfo.flags
    return (flags and ApplicationInfo.FLAG_DEBUGGABLE) != 0
}
