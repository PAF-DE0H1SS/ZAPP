package xyz.azraellab.zapp.core

import android.content.Context
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager

/**
 * Android: вибро через системный Vibrator (API 31+ -- VibratorManager).
 *
 * `amplitudeScale` ограничен: системный режим вибрации пользователя
 * («лёгкая/средняя/сильная», отключение вибрации) -- часть настроек доступа,
 * обходить её приложением не следует. Если такт не состоялся (нет мотора,
 * режим «без вибрации»), это не ошибка -- молча выходим.
 */
actual object Haptics {
    actual fun pulse(event: HapticEvent) {
        val context = AndroidCtx.current ?: return
        // Миллисекунды такта: подключение -- мягче, ошибка -- двойной толчок.
        val (pattern, amplitude) = when (event) {
            HapticEvent.CONNECTED -> longArrayOf(18) to 90
            HapticEvent.DISCONNECTED -> longArrayOf(12) to 60
            HapticEvent.ERROR -> longArrayOf(0, 28, 60, 28) to 140
        }
        runCatching {
            val vibrator = vibratorOf(context) ?: return
            // VibrationEffect.createWaveform с нулевым стартом -- одиночные
            // такты без задержки; амплитуда 1..255.
            val effect = VibrationEffect.createWaveform(
                pattern,
                IntArray(pattern.size) { amplitude.coerceIn(1, 255) },
                -1
            )
            vibrator.vibrate(effect)
        }
    }

    /** Системный вибратор: сначала VibratorManager (API 31+), иначе старый сервис. */
    private fun vibratorOf(context: Context): Vibrator? = runCatching {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val manager = context.getSystemService(VibratorManager::class.java)
            manager?.defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            context.getSystemService(Vibrator::class.java)
        }
    }.getOrNull()
}
