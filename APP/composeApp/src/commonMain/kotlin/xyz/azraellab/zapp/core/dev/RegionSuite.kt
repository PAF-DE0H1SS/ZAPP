package xyz.azraellab.zapp.core.dev

import xyz.azraellab.zapp.core.AppConfig
import xyz.azraellab.zapp.core.AppLang
import xyz.azraellab.zapp.core.RegionPolicies
import xyz.azraellab.zapp.core.RegionRegistry
import xyz.azraellab.zapp.core.RegionTuner
import xyz.azraellab.zapp.core.VpnProtocol

/**
 * Регионы: реестр стран, политики, применение политики к настройкам.
 *
 * Страна -- фактор автонастройки, поэтому здесь проверяется главное: что
 * политика не прячет лишнего, что неизвестная страна нейтральна и что
 * [RegionTuner] не ломает выбор пользователя, а только выключает/включает
 * то, чего требует политика.
 */
internal fun regionTests(out: MutableList<DevTest>) {

    // --- Реестр: полнота и уникальность кодов ---

    out += DevTest("region/registry-full", DevCategory.CONFIG, "Реестр стран полный (180+, ISO 3166-1)") {
        checkTrue(
            RegionRegistry.all.size >= 180,
            "в реестре должно быть большинство стран мира, а не горстка: ${RegionRegistry.all.size}"
        )
    }

    out += DevTest("region/registry-unique-codes", DevCategory.CONFIG, "Коды стран в реестре уникальны") {
        val codes = RegionRegistry.all.map { it.code }
        checkTrue(
            codes.size == codes.toSet().size,
            "дубликаты кодов: ${codes.groupingBy { it }.eachCount().filter { it.value > 1 }}"
        )
    }

    out += DevTest("region/registry-codes-uppercase", DevCategory.CONFIG, "Коды стран -- верхний регистр, 2 символа") {
        RegionRegistry.all.forEach { region ->
            checkTrue(
                region.code.length == 2 && region.code == region.code.uppercase(),
                "плохой код ${region.code}"
            )
        }
    }

    out += DevTest("region/registry-names-nonempty", DevCategory.CONFIG, "У каждой страны есть имя на всех языках") {
        RegionRegistry.all.forEach { region ->
            listOf(region.nameEn, region.nameRu, region.nameZh).forEach { name ->
                checkTrue(name.isNotBlank(), "пустое имя у ${region.code}")
            }
        }
    }

    out += DevTest("region/registry-sorted", DevCategory.CONFIG, "Реестр отсортирован по английскому имени") {
        val names = RegionRegistry.all.map { it.nameEn.lowercase() }
        checkEq(names.sorted(), names, "регионы должны быть отсортированы")
    }

    out += DevTest("region/registry-sorted-ru", DevCategory.CONFIG, "Русский список стран идёт по русским именам") {
        val names = RegionRegistry.allSorted(AppLang.RU).map { it.nameRu.lowercase() }
        checkEq(names.sorted(), names, "порядок должен совпадать с русским алфавитом")
        val zh = RegionRegistry.allSorted(AppLang.ZH).map { it.nameZh.lowercase() }
        checkEq(zh.sorted(), zh, "китайский -- по иероглифам")
        // Иначе функция могла бы существовать и просто возвращать
        // английский порядок, никем не замеченный.
        val byEnglish = RegionRegistry.allSorted(AppLang.RU).map { it.nameEn.lowercase() }
        checkFalse(names == byEnglish, "русский порядок не должен совпадать с английским")
    }

    out += DevTest("region/registry-bycode", DevCategory.CONFIG, "byCode находит по коду и не зависит от регистра") {
        checkEq(RegionRegistry.byCode("RU")?.code, "RU", "RU")
        checkEq(RegionRegistry.byCode("ru")?.code, "RU", "ru->RU")
        checkEq(RegionRegistry.byCode("us")?.code, "US", "us->US")
        checkEq(RegionRegistry.byCode("XX")?.code, null, "нет такого кода")
        checkEq(RegionRegistry.byCode(null)?.code, null, "null")
    }

    out += DevTest("region/registry-localized", DevCategory.CONFIG, "localized переводит имя на язык") {
        checkEq(RegionRegistry.localized("RU", AppLang.RU), "Россия", "русское имя")
        checkEq(RegionRegistry.localized("RU", AppLang.EN), "Russia", "английское имя")
        checkEq(RegionRegistry.localized("CN", AppLang.ZH), "中国", "китайское имя")
        checkEq(RegionRegistry.localized("XX", AppLang.EN), "", "неизвестный код -- пусто")
    }

    out += DevTest("region/registry-key-countries", DevCategory.CONFIG, "Ключевые страны присутствуют") {
        listOf("RU", "CN", "IR", "US", "DE", "GB", "FR", "BY", "UA", "KZ").forEach { code ->
            checkTrue(RegionRegistry.byCode(code) != null, "нет страны $code")
        }
    }

    // --- Политики ---

    out += DevTest("region/policy-neutral", DevCategory.CONFIG, "Неизвестная страна -- нейтральная политика") {
        listOf("", "XX", "US", "DE", "KZ").forEach { code ->
            val policy = RegionPolicies.of(code)
            checkTrue(policy.hiddenProtocols.isEmpty(), "$code: ничего не скрыто")
            checkTrue(policy.preferredProtocols.isEmpty(), "$code: без предпочтений")
            checkTrue(!policy.forceDnsViaProxy, "$code: DNS не форсируется")
            checkTrue(!policy.preferTcp, "$code: без TCP-предпочтения")
        }
    }

    out += DevTest("region/policy-ru-hides", DevCategory.CONFIG, "RU скрывает wg/awg/hysteria2/tuic, но не vless/trojan") {
        val policy = RegionPolicies.of("RU")
        listOf(VpnProtocol.WIREGUARD, VpnProtocol.AMNEZIA_WG, VpnProtocol.HYSTERIA2, VpnProtocol.TUIC)
            .forEach { p -> checkTrue(p.code in policy.hiddenProtocols, "RU должен скрывать ${p.code}") }
        listOf(VpnProtocol.VLESS, VpnProtocol.TROJAN, VpnProtocol.SHADOWSOCKS, VpnProtocol.OPENVPN)
            .forEach { p -> checkTrue(p.code !in policy.hiddenProtocols, "RU не должен скрывать ${p.code}") }
    }

    out += DevTest("region/policy-ru-dns", DevCategory.CONFIG, "RU форсирует DNS через прокси и TCP") {
        checkTrue(RegionPolicies.of("RU").forceDnsViaProxy, "RU: forceDnsViaProxy")
        checkTrue(RegionPolicies.of("RU").preferTcp, "RU: preferTcp")
        checkTrue(RegionPolicies.of("ru").forceDnsViaProxy, "ru (нижний регистр) тоже")
    }

    out += DevTest("region/policy-cn-hides-more", DevCategory.CONFIG, "CN скрывает ещё и ss/ovpn") {
        val policy = RegionPolicies.of("CN")
        listOf(VpnProtocol.WIREGUARD, VpnProtocol.AMNEZIA_WG, VpnProtocol.HYSTERIA2, VpnProtocol.TUIC,
            VpnProtocol.SHADOWSOCKS, VpnProtocol.OPENVPN)
            .forEach { p -> checkTrue(p.code in policy.hiddenProtocols, "CN должен скрывать ${p.code}") }
        listOf(VpnProtocol.VLESS, VpnProtocol.VMESS, VpnProtocol.TROJAN)
            .forEach { p -> checkTrue(p.code !in policy.hiddenProtocols, "CN не должен скрывать ${p.code}") }
    }

    out += DevTest("region/policy-iran", DevCategory.CONFIG, "IR как RU, без openvpn в приоритете") {
        val policy = RegionPolicies.of("IR")
        listOf(VpnProtocol.WIREGUARD.code, VpnProtocol.HYSTERIA2.code)
            .forEach { p -> checkTrue(p in policy.hiddenProtocols, "IR должен скрывать $p") }
        checkTrue(VpnProtocol.OPENVPN.code !in policy.preferredProtocols, "IR без openvpn в приоритете")
    }

    out += DevTest("region/policy-ishidden", DevCategory.CONFIG, "RegionPolicies.isHidden зависит от региона") {
        checkTrue(RegionPolicies.isHidden("RU", VpnProtocol.WIREGUARD.code), "RU скрывает wg")
        checkFalse(RegionPolicies.isHidden("US", VpnProtocol.WIREGUARD.code), "US не скрывает wg")
        checkFalse(RegionPolicies.isHidden("", VpnProtocol.WIREGUARD.code), "нет региона -- не скрывает")
    }

    // --- RegionTuner ---

    out += DevTest("region/tuner-neutral-keeps-config", DevCategory.CONFIG, "RegionTuner нейтрального региона не трогает конфиг") {
        val cfg = AppConfig(vpn = xyz.azraellab.zapp.core.VpnConfig(dnsViaProxy = false, dnsFakeIp = true))
        val applied = RegionTuner.apply(cfg, "US")
        checkEq(applied.region, "US", "регион записан")
        checkFalse(applied.vpn.dnsViaProxy, "dnsViaProxy не тронут")
        checkTrue(applied.vpn.dnsFakeIp, "dnsFakeIp не тронут")
    }

    out += DevTest("region/tuner-forces-dns", DevCategory.CONFIG, "RegionTuner RU включает dnsViaProxy") {
        val cfg = AppConfig(vpn = xyz.azraellab.zapp.core.VpnConfig(dnsViaProxy = false))
        val applied = RegionTuner.apply(cfg, "RU")
        checkEq(applied.region, "RU", "регион записан")
        checkTrue(applied.vpn.dnsViaProxy, "dnsViaProxy включён политикой")
    }

    out += DevTest("region/tuner-keeps-explicit-dns", DevCategory.CONFIG, "RegionTuner не выключает явный dnsViaProxy") {
        val cfg = AppConfig(vpn = xyz.azraellab.zapp.core.VpnConfig(dnsViaProxy = true))
        val applied = RegionTuner.apply(cfg, "DE")
        checkTrue(applied.vpn.dnsViaProxy, "пользовательский выбор сохранён")
    }

    out += DevTest("region/tuner-does-not-touch-other-fields", DevCategory.CONFIG, "RegionTuner не трогает zapret/strategy/gps") {
        val cfg = AppConfig(
            zapret = xyz.azraellab.zapp.core.ZapretConfig(enabled = true, strategy = "fake"),
            gps = xyz.azraellab.zapp.core.GpsConfig(enabled = true)
        )
        val applied = RegionTuner.apply(cfg, "RU")
        checkEq(applied.zapret, cfg.zapret, "zapret не тронут")
        checkEq(applied.gps, cfg.gps, "gps не тронут")
    }
}