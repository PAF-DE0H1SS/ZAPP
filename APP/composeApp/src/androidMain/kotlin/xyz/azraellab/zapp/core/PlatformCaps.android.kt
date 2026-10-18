package xyz.azraellab.zapp.core

import android.content.pm.PackageManager
import android.os.Build

/**
 * Возможности Android.
 *
 * Подмена координат берётся у системы через API тест-провайдера
 * (`LocationManager`), и он есть на Android начиная с API 23. Ниже этого порога
 * раздел всё равно не откроется: приложение требует `minSdk = 33`.
 */
actual fun platformCaps(): PlatformCaps = object : PlatformCaps {
    override val gpsSpoofingSupported: Boolean = Build.VERSION.SDK_INT >= 23
    override val apkUpdatesSupported: Boolean = true
    override val systemProxySupported: Boolean = false
    override val autostartSupported: Boolean = false
    override val uwpLoopbackSupported: Boolean = false
    override val coreUpdateSupported: Boolean = false

    // Рантайм-детект: телефон и приставка -- один androidMain. Контекст
    // к этому моменту уже присоединён (ZappApp.onCreate раньше первого
    // AppState); без контекста -- не TV, а не падение.
    override val tvMode: Boolean = runCatching {
        AndroidCtx.current
            ?.packageManager
            ?.hasSystemFeature(PackageManager.FEATURE_LEANBACK) == true
    }.getOrDefault(false)
}
