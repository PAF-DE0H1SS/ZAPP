package xyz.azraellab.zapp.ui.consistency

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import xyz.azraellab.zapp.core.AppLang
import xyz.azraellab.zapp.core.AppState
import xyz.azraellab.zapp.core.Str
import xyz.azraellab.zapp.core.consistency.SettingLock
import xyz.azraellab.zapp.core.consistency.SettingLocks
import xyz.azraellab.zapp.ui.tr

/**
 * Текущие блокировки настроек, пересчитанные под текущий конфиг.
 *
 * Вызывается в начале композабла настроек, дальше используются
 * [lockReason] и [isLocked].
 */
@Composable
fun settingLocks(state: AppState): Map<String, SettingLock> =
    remember(state.config) { SettingLocks.locked(state.config) }

/** Заморожен ли параметр (показывается серым и не даёт себя менять). */
fun isLocked(locks: Map<String, SettingLock>, key: String): Boolean = key in locks

/** Принудительное значение блокировки; null, если параметр свободен. */
fun forcedValue(locks: Map<String, SettingLock>, key: String): Boolean? =
    locks[key]?.forcedValue

/** Причина блокировки для подписи под параметром; null, если параметр свободен. */
@Composable
fun lockReason(locks: Map<String, SettingLock>, key: String): String? =
    locks[key]?.reason?.let { tr(it) }

/** Причина блокировки из готовой карты не-композаблом (для non-composable кода). */
fun lockReasonText(
    locks: Map<String, SettingLock>,
    key: String,
    lang: AppLang,
    text: (Str, AppLang) -> String
): String? = locks[key]?.reason?.let { text(it, lang) }