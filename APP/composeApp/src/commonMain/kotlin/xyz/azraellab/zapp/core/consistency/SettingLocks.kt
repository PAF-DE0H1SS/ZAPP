package xyz.azraellab.zapp.core.consistency

import xyz.azraellab.zapp.core.AppConfig
import xyz.azraellab.zapp.core.RegionPolicies
import xyz.azraellab.zapp.core.Str
import xyz.azraellab.zapp.core.UsageProfile

/**
 * Ключи настроек, которые может блокировать консистентность.
 *
 * Плоские строки, а не enum: их используют UI (`ui.tr`, `ZappSettingRow`) и
 * чистая логика [SettingLocks.locked], которая не должна знать про Compose.
 */
object SettingKeys {
    const val DNS_VIA_PROXY = "vpn.dnsViaProxy"
    const val DNS_FAKE_IP = "vpn.dnsFakeIp"
    const val KILL_SWITCH = "vpn.killSwitch"
    const val BG_ANIM = "perf.animatedBackground"
    const val BG_FPS = "perf.backgroundFps"
    const val TRAFFIC_INTERVAL = "traffic.intervalMs"
}

/**
 * Результат блокировки одного параметра.
 *
 * Параметр изменять можно, только если карта [SettingLocks.locked] не
 * содержит его ключа. Если содержит -- UI рисует свитч выключенным (цвет
 * приглушён, тап не срабатывает) и показывает причину [reason] подпись под
 * строкой.
 */
data class SettingLock(
    /** Ключ параметра, см. [SettingKeys]. */
    val key: String,
    /** Текст причины (ключ локализации). */
    val reason: Str,
    /**
     * Значение, к которому параметр принудительно зафиксирован.
     *
     * true -- свитч выглядит включённым и не даёт себя выключить; false --
     * наоборот. null -- значение не важно (например, зависимый параметр
     * выключен вместе с родительским).
     */
    val forcedValue: Boolean?
)

/**
 * Движок консистентности настроек.
 *
 * Чистая функция: по конфигу возвращает карту «ключ -> причина блокировки».
 * Явно запрещённых сочетаний в разных разделах настроек несколько (DNS-режимы
 * убивают друг друга, region принудительно включает DNS через прокси, приоритет
 * «батарея» отключает анимацию фона), и UI не должен позволять их собрать:
 * запрещённое редактируется, а не «тихо не работает».
 *
 * Правило «два разных раздела»: блокировка считается от текущих значений
 * конфига, поэтому переключение одного тумблера мгновенно разблокирует или
 * блокирует другой, и UI не хранит состояние блокировок отдельно.
 */
object SettingLocks {

    /**
     * Все активные блокировки.
     *
     * @param region регион из конфига (ISO-код). Применение опросника берётся
     *     из самого [config] (`usage.applied`), чтобы функция оставалась
     *     чистой функцией от одного аргумента.
     */
    fun locked(config: AppConfig, region: String = config.region): Map<String, SettingLock> = buildMap {
        val vpn = config.vpn
        val policy = RegionPolicies.of(region)
        val usageApplied = config.usage.applied
        val priority = config.usage.priority
        val purpose = config.usage.purpose

        // DNS-режимы несовместимы: fake-ip и прокси-резолв противоречат.
        if (vpn.dnsViaProxy) {
            put(SettingKeys.DNS_FAKE_IP, SettingLock(SettingKeys.DNS_FAKE_IP, Str.LOCK_DNS_CONFLICT, false))
        }
        if (vpn.dnsFakeIp) {
            put(SettingKeys.DNS_VIA_PROXY, SettingLock(SettingKeys.DNS_VIA_PROXY, Str.LOCK_DNS_CONFLICT, false))
        }

        // Регион принудительно включает DNS через прокси (и выключает fake-ip).
        if (policy.forceDnsViaProxy) {
            put(SettingKeys.DNS_VIA_PROXY, SettingLock(SettingKeys.DNS_VIA_PROXY, Str.LOCK_REGION_DNS, true))
            put(SettingKeys.DNS_FAKE_IP, SettingLock(SettingKeys.DNS_FAKE_IP, Str.LOCK_REGION_DNS, false))
        }

        // Опросник: приватность требует kill switch.
        if (usageApplied && purpose == UsageProfile.PURPOSE_PRIVACY) {
            put(SettingKeys.KILL_SWITCH, SettingLock(SettingKeys.KILL_SWITCH, Str.LOCK_KILLSWITCH_PRIVACY, true))
        }

        // Опросник: приоритет «батарея» отключает анимацию фона.
        if (usageApplied && priority == UsageProfile.PRIORITY_BATTERY) {
            put(SettingKeys.BG_ANIM, SettingLock(SettingKeys.BG_ANIM, Str.LOCK_PRIORITY_BATTERY, false))
        }

        // Каскад: выключенный фон выключает и выбор частоты (независимо от причины).
        if (config.perf.animatedBackground == false) {
            put(SettingKeys.BG_FPS, SettingLock(SettingKeys.BG_FPS, Str.LOCK_PERF_CASCADE, null))
        }
    }

    /** Есть ли активная блокировка ключа. */
    fun isLocked(locks: Map<String, SettingLock>, key: String): Boolean = key in locks

    /** Причина блокировки ключа; null, если ключ свободен. */
    fun reason(locks: Map<String, SettingLock>, key: String): Str? =
        locks[key]?.reason

    /** Принудительное значение; null, если ключ не зафиксирован. */
    fun forcedValue(locks: Map<String, SettingLock>, key: String): Boolean? =
        locks[key]?.forcedValue

    private fun put(
        map: MutableMap<String, SettingLock>,
        lock: SettingLock
    ) {
        // Более сильная блокировка побеждает: принудительный регион важнее,
        // чем взаимное исключение DNS-режимов (обе дают один и тот же ключ).
        val existing = map[lock.key]
        if (existing == null || existing.forcedValue == null) map[lock.key] = lock
    }
}