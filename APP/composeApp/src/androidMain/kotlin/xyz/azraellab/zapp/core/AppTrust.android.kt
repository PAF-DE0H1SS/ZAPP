package xyz.azraellab.zapp.core

import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import xyz.azraellab.zapp.core.secure.sha256Hex

/**
 * SHA-256 нашего релизного сертификата: signing/zapp.keystore, alias zapp.
 *
 * Сверяется с подписанной копией APK; при смене ключа в релизе строка
 * обновляется вместе с keystore.
 */
private val RELEASE_CERT_SHA256 =
    "89b18de7a726cd55f25a753097a3b15f4702659042f8211b22de0715d96e7812"

actual fun isOfficialBuild(): Boolean = runCatching {
    val context = AppContextHolder.current
    // Отладочные сборки подписываются debug-ключом разработчика: это наши
    // же сборки, сверять их с релизным фингерпринтом нельзя.
    val debuggable =
        (context.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) != 0
    if (debuggable) return true
    val info = context.packageManager.getPackageInfo(
        context.packageName,
        PackageManager.GET_SIGNING_CERTIFICATES
    )
    val signers = info.signingInfo?.apkContentsSigners ?: return false
    signers.any { sha256Hex(it.toByteArray()) == RELEASE_CERT_SHA256 }
}.getOrDefault(false)
