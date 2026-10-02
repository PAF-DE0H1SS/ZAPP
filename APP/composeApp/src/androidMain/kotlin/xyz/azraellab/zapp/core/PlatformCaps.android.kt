package xyz.azraellab.zapp.core

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
}
