package xyz.azraellab.zapp.core

/** Android: реестр живёт в noBackupFilesDir, ресивер -- в модуле :app. */
actual object BootSync {
    actual fun onConfigSaved(config: AppConfig) {
        val context = AndroidCtx.current ?: return
        BootRegistry.sync(context, BootPlan.of(config))
    }
}
