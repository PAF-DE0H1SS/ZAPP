package xyz.azraellab.zapp.core.perApp

import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import xyz.azraellab.zapp.core.AndroidCtx

/**
 * Перечисление установленных приложений через PackageManager.
 *
 * Используется `getInstalledApplications(0)` (а не queryIntentActivities):
 * в запись попадают и приложения без лаунчерного activity, но с uid --
 * сервисы и библиотеки, которые чаще всего и выбирают для per-app.
 * Системность определяется флагом ApplicationInfo.FLAG_SYSTEM.
 *
 * Лаунчерные приложения идут первыми: в каталоге в сотни пакетов
 * искать глазами нужный мессенджер мука, а один запрос
 * queryIntentActivities даёт полный набор «иконок на рабочем столе».
 */
actual fun installedApps(): List<InstalledApp> {
    val context = AndroidCtx.current ?: return emptyList()
    val pm = context.packageManager
    return runCatching {
        val launcher = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val launchers = runCatching {
            pm.queryIntentActivities(launcher, 0).mapNotNull { it.activityInfo?.packageName }
        }.getOrDefault(emptyList()).toSet()
        pm.getInstalledApplications(0)
            .map { info -> info.toInstalledApp(pm) }
            .sortedWith(
                compareByDescending<InstalledApp> { it.packageName in launchers }
                    .thenBy(String.CASE_INSENSITIVE_ORDER) { it.label.lowercase() }
            )
    }.getOrDefault(emptyList())
}

/** UID пакета напрямую: быстрее, чем искать его в списке всех приложений. */
actual fun packageUid(packageName: String): Int? {
    val context = AndroidCtx.current ?: return null
    return runCatching {
        context.packageManager.getApplicationInfo(packageName, 0).uid
    }.getOrNull()
}

/** Пакет приложения из контекста; до готовности контекста -- пусто. */
actual fun ownPackageName(): String = AndroidCtx.current?.packageName ?: ""

private fun ApplicationInfo.toInstalledApp(pm: PackageManager): InstalledApp =
    InstalledApp(
        packageName = packageName,
        label = pm.getApplicationLabel(this)?.toString()?.takeIf { it.isNotBlank() }
            ?: packageName,
        uid = if (uid != 0) uid else null,
        isSystem = (flags and ApplicationInfo.FLAG_SYSTEM) != 0,
        category = refineCategory(packageName, platformCategory(category))
    )

/** Категория платформы в нашу шкалу; мелкие медиа-типы схлопнуты в MEDIA. */
private fun platformCategory(value: Int): AppCategory = when (value) {
    ApplicationInfo.CATEGORY_GAME -> AppCategory.GAME
    ApplicationInfo.CATEGORY_AUDIO,
    ApplicationInfo.CATEGORY_VIDEO,
    ApplicationInfo.CATEGORY_IMAGE -> AppCategory.MEDIA
    ApplicationInfo.CATEGORY_SOCIAL -> AppCategory.SOCIAL
    ApplicationInfo.CATEGORY_NEWS -> AppCategory.NEWS
    else -> AppCategory.OTHER
}