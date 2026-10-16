package xyz.azraellab.zapp

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import xyz.azraellab.zapp.core.AppState
import xyz.azraellab.zapp.core.createConfigStore

/**
 * Старт из перезагрузки устройства.
 *
 * Тот же headless-путь, которым пользуются плитки шторки уведомлений:
 * процесс поднимается сам, [AppState.load] читает конфиг и запускает те
 * же автосервисы, что и обычное открытие приложения, -- туннель, демоны
 * обхода и подмену геолокации. Ничего нового не выдумывается: ресивер
 * лишь повторяет намерение, уже выраженное настройками, и молчит, если
 * его нет (мастер-тумблер `bootAutostart` выключен по умолчанию).
 *
 * Согласие VpnService здесь не проверяется: оно сохраняется между
 * перезагрузками, а если его успели отозвать, туннель честно упадёт в
 * ERROR с той же причиной, что и при обычном запуске.
 */
class BootAutostart : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return
        // Дешёвая проверка файла настроек до каких-либо движков: без
        // намерения автостарта процесс при загрузке поднимать нечего.
        // Конфиг здесь читается напрямую, без load(): тот уже запускает
        // фоновые циклы, и будить их зря нельзя.
        val config = runCatching { createConfigStore().readConfig() }.getOrNull() ?: return
        if (!config.autostartOnBoot()) return
        AppState.obtain().load()
    }
}
