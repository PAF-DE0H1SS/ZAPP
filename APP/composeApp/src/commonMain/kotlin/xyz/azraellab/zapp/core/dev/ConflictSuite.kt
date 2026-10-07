package xyz.azraellab.zapp.core.dev

import xyz.azraellab.zapp.core.AppConfig
import xyz.azraellab.zapp.core.PerfConfig
import xyz.azraellab.zapp.core.Str
import xyz.azraellab.zapp.core.UsageProfile
import xyz.azraellab.zapp.core.VpnConfig
import xyz.azraellab.zapp.core.consistency.SettingKeys
import xyz.azraellab.zapp.core.consistency.SettingLocks

/**
 * Консистентность настроек: что замораживается, а что свободно.
 *
 * Приложение не должно давать собрать противоречащие параметры («два
 * тумблера, которые убивают друг друга»). Здесь главное свойство --
 * полнота: на каждое конфликтное сочетание __есть__ блокировка, и её причина
 * -- внятная строка, а не заглушка.
 */
internal fun conflictTests(out: MutableList<DevTest>) {

    // --- Чистый конфиг: ничего не заблокировано ---

    out += DevTest("conflict/clean-config", DevCategory.CONFIG, "Чистый конфиг без блокировок") {
        val locks = SettingLocks.locked(AppConfig())
        checkTrue(locks.isEmpty(), "ничего не должно быть заблокировано: $locks")
    }

    // --- DNS-режимы взаимно исключаются ---

    out += DevTest("conflict/dns-via-proxy-blocks-fake-ip", DevCategory.CONFIG, "dnsViaProxy блокирует dnsFakeIp") {
        val locks = SettingLocks.locked(AppConfig(vpn = VpnConfig(dnsViaProxy = true)))
        checkTrue(locks.containsKey(SettingKeys.DNS_FAKE_IP), "dnsFakeIp должен быть заблокирован")
        checkEq(locks[SettingKeys.DNS_FAKE_IP]?.forcedValue, false, "принудительно выключен")
    }

    out += DevTest("conflict/dns-fake-ip-blocks-proxy", DevCategory.CONFIG, "dnsFakeIp блокирует dnsViaProxy") {
        val locks = SettingLocks.locked(AppConfig(vpn = VpnConfig(dnsFakeIp = true)))
        checkTrue(locks.containsKey(SettingKeys.DNS_VIA_PROXY), "dnsViaProxy должен быть заблокирован")
        checkEq(locks[SettingKeys.DNS_VIA_PROXY]?.forcedValue, false, "принудительно выключен")
    }

    out += DevTest("conflict/dns-reasons-are-typed", DevCategory.CONFIG, "DNS-конфликт имеет внятную причину") {
        val locks = SettingLocks.locked(AppConfig(vpn = VpnConfig(dnsViaProxy = true)))
        checkEq(locks[SettingKeys.DNS_FAKE_IP]?.reason, Str.LOCK_DNS_CONFLICT, "причина DNS-конфликта")
    }

    // --- Регион форсирует DNS ---

    out += DevTest("conflict/region-forces-proxy-overrides", DevCategory.CONFIG, "RU форсирует DNS через прокси даже при fake-ip") {
        val locks = SettingLocks.locked(AppConfig(region = "RU", vpn = VpnConfig(dnsFakeIp = true)))
        val proxy = locks[SettingKeys.DNS_VIA_PROXY]
        checkTrue(proxy != null, "dnsViaProxy заблокирован")
        checkEq(proxy?.forcedValue, true, "принудительно включён регионом")
        checkEq(locks[SettingKeys.DNS_FAKE_IP]?.forcedValue, false, "fake-ip принудительно выключен")
        checkEq(proxy?.reason, Str.LOCK_REGION_DNS, "причина -- регион")
    }

    out += DevTest("conflict/region-wins-over-dns-conflict", DevCategory.CONFIG, "Регион важнее взаимного исключения DNS") {
        // Если регион требует dnsViaProxy, блокировка региона (forced=true) побеждает
        // pазнонаправленную блокировку от обратного режима.
        val locks = SettingLocks.locked(AppConfig(region = "RU", vpn = VpnConfig(dnsViaProxy = true, dnsFakeIp = true)))
        checkEq(locks[SettingKeys.DNS_VIA_PROXY]?.forcedValue, true, "регионнутый DNS через прокси")
        checkEq(locks[SettingKeys.DNS_FAKE_IP]?.forcedValue, false, "fake-ip выключен")
    }

    out += DevTest("conflict/neutral-region-no-dns-lock", DevCategory.CONFIG, "Нейтральный регион не форсирует DNS") {
        listOf("", "US", "DE", "KZ").forEach { region ->
            val locks = SettingLocks.locked(AppConfig(region = region))
            checkFalse(locks.containsKey(SettingKeys.DNS_VIA_PROXY), "$region: DNS не заблокирован")
            checkFalse(locks.containsKey(SettingKeys.DNS_FAKE_IP), "$region: fake-ip не заблокирован")
        }
    }

    // --- Опросник ---

    out += DevTest("conflict/privacy-forces-killswitch", DevCategory.CONFIG, "Приватность форсирует kill switch") {
        val locks = SettingLocks.locked(
            AppConfig(usage = UsageProfile(purpose = UsageProfile.PURPOSE_PRIVACY, applied = true))
        )
        val lock = locks[SettingKeys.KILL_SWITCH]
        checkTrue(lock != null, "kill switch заблокирован")
        checkEq(lock?.forcedValue, true, "принудительно включён")
        checkEq(lock?.reason, Str.LOCK_KILLSWITCH_PRIVACY, "причина -- приватность")
    }

    out += DevTest("conflict/privacy-not-applied-no-lock", DevCategory.CONFIG, "Неприменённый опросник не блокирует") {
        val locks = SettingLocks.locked(
            AppConfig(usage = UsageProfile(purpose = UsageProfile.PURPOSE_PRIVACY, applied = false))
        )
        checkFalse(locks.containsKey(SettingKeys.KILL_SWITCH), "нет применения -- нет блокировки")
    }

    out += DevTest("conflict/other-purpose-no-lock", DevCategory.CONFIG, "Видео/игры не форсируют kill switch") {
        listOf(UsageProfile.PURPOSE_VIDEO, UsageProfile.PURPOSE_GAMES, UsageProfile.PURPOSE_CHAT).forEach { p ->
            val locks = SettingLocks.locked(
                AppConfig(usage = UsageProfile(purpose = p, applied = true))
            )
            checkFalse(locks.containsKey(SettingKeys.KILL_SWITCH), "$p: kill switch свободен")
        }
    }

    // --- Приоритет «батарея» ---

    out += DevTest("conflict/battery-blocks-bg-anim", DevCategory.CONFIG, "Батарея отключает анимацию фона") {
        val locks = SettingLocks.locked(
            AppConfig(usage = UsageProfile(priority = UsageProfile.PRIORITY_BATTERY, applied = true))
        )
        val lock = locks[SettingKeys.BG_ANIM]
        checkTrue(lock != null, "анимация заблокирована")
        checkEq(lock?.forcedValue, false, "принудительно выключена")
        checkEq(lock?.reason, Str.LOCK_PRIORITY_BATTERY, "причина -- батарея")
    }

    out += DevTest("conflict/battery-not-applied-free", DevCategory.CONFIG, "Неприменённая батарея -- свободная анимация") {
        val locks = SettingLocks.locked(
            AppConfig(usage = UsageProfile(priority = UsageProfile.PRIORITY_BATTERY, applied = false))
        )
        checkFalse(locks.containsKey(SettingKeys.BG_ANIM), "нет применения -- нет блокировки")
    }

    // --- Каскад: выключенный фон ---

    out += DevTest("conflict/bg-off-blocks-fps", DevCategory.CONFIG, "Выключенный фон выключает выбор частоты") {
        val locks = SettingLocks.locked(
            AppConfig(perf = PerfConfig(animatedBackground = false))
        )
        val lock = locks[SettingKeys.BG_FPS]
        checkTrue(lock != null, "частота заблокирована")
        checkEq(lock?.reason, Str.LOCK_PERF_CASCADE, "причина -- каскад")
        checkEq(lock?.forcedValue, null, "значение не принудительное")
    }

    out += DevTest("conflict/bg-on-fps-free", DevCategory.CONFIG, "Включённый фон не блокирует частоту") {
        val locks = SettingLocks.locked(
            AppConfig(perf = PerfConfig(animatedBackground = true, backgroundFps = 15))
        )
        checkFalse(locks.containsKey(SettingKeys.BG_FPS), "частота свободна")
    }

    // --- Вспомогательные предикаты ---

    out += DevTest("conflict/predicates", DevCategory.CONFIG, "isLocked/reason/forcedValue согласованы") {
        val locks = SettingLocks.locked(AppConfig(region = "RU", vpn = VpnConfig(dnsFakeIp = true)))
        checkTrue(SettingLocks.isLocked(locks, SettingKeys.DNS_VIA_PROXY), "DNS_VIA_PROXY заблокирован")
        checkTrue(!SettingLocks.isLocked(locks, SettingKeys.KILL_SWITCH), "kill switch свободен")
        checkEq(SettingLocks.reason(locks, SettingKeys.DNS_VIA_PROXY), Str.LOCK_REGION_DNS, "причина")
        checkEq(SettingLocks.forcedValue(locks, SettingKeys.DNS_VIA_PROXY), true, "значение")
        checkEq(SettingLocks.forcedValue(locks, SettingKeys.KILL_SWITCH), null, "свободный -- no value")
    }
}