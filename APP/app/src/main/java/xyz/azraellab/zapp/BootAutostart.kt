package xyz.azraellab.zapp

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import xyz.azraellab.zapp.core.AppState
import xyz.azraellab.zapp.core.BootRegistry
import xyz.azraellab.zapp.core.createConfigStore

/**
 * Старт из перезагрузки устройства.
 *
 * Тот же headless-путь, которым пользуются плитки шторки уведомлений:
 * процесс поднимается сам, [AppState.load] читает конфиг и запускает те
 * же автосервисы, что и обычное открытие приложения, -- туннель, демоны
 * обхода и подмену геолокации. Ничего нового не выдумывается: ресивер
 * лишь повторяет намерение, уже выраженное настройками.
 *
 * Врата -- реестр ([BootRegistry]): дешёвый снимок намерений в
 * noBackupFilesDir, который пишется при каждом сохранении конфига и
 * гасит этот ресивер, когда автозапуск не нужен -- система не будит
 * приложение зря. Установка со старой версии (реестра ещё нет) идёт
 * по прежнему пути -- чтение живого конфига.
 *
 * Согласие VpnService здесь не проверяется: оно сохраняется между
 * перезагрузками, а если его успели отозвать, туннель честно упадёт в
 * ERROR с той же причиной, что и при обычном запуске.
 */
class BootAutostart : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return
        val plan = BootRegistry.read(context)
        if (plan != null) {
            if (plan.isEmpty) return
        } else {
            // Старая установка без реестра: намерение по живому конфигу.
            // Конфиг здесь читается напрямую, без load(): тот уже
            // запускает фоновые циклы, будить их зря нельзя.
            val config = runCatching { createConfigStore().readConfig() }.getOrNull() ?: return
            if (!config.autostartOnBoot()) return
        }
        AppState.obtain().load()
    }
}
