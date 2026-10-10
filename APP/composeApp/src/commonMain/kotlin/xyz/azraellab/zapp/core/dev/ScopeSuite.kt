package xyz.azraellab.zapp.core.dev

import xyz.azraellab.zapp.core.GoodbyeDpiConfig
import xyz.azraellab.zapp.core.ZapretConfig
import xyz.azraellab.zapp.core.domains.AppDomains
import xyz.azraellab.zapp.core.domains.DpiScope
import xyz.azraellab.zapp.core.perApp.AppSelection
import xyz.azraellab.zapp.core.perApp.PerAppConfig

/**
 * Область действия обхода: гейт запуска и поиск доменов приложений.
 *
 * Гейт закрывает пустой запуск (nfqws/goodbyedpi без целей фильтровать
 * нечего), поиск доменов закрывает ручной ввод списков -- обе оси обязаны
 * совпадать в UI, движке и тестах.
 */
internal fun scopeTests(out: MutableList<DevTest>) {

    // --- Гейт ---

    out += DevTest("scope/gate-zapret", DevCategory.DPI, "Запуск zapret требует области") {
        val none = PerAppConfig()
        checkFalse(DpiScope.zapret(none, ZapretConfig()), "совсем пусто")
        val emptyOn = PerAppConfig(zapret = AppSelection(enabled = true))
        checkFalse(DpiScope.zapret(emptyOn, ZapretConfig()), "включён, но пусто")
        val apps = PerAppConfig(zapret = AppSelection(enabled = true, packages = listOf("com.discord.app")))
        checkTrue(DpiScope.zapret(apps, ZapretConfig()), "выбор приложений")
        val off = PerAppConfig(zapret = AppSelection(enabled = false, packages = listOf("com.discord.app")))
        checkFalse(DpiScope.zapret(off, ZapretConfig()), "выбор выключен")
        checkTrue(DpiScope.zapret(none, ZapretConfig(domains = "discord.com")), "домены без приложений")
        checkTrue(
            DpiScope.zapret(apps, ZapretConfig(domains = "discord.com")),
            "обе оси вместе"
        )
    }

    out += DevTest("scope/gate-gdpi", DevCategory.DPI, "Запуск goodbyedpi требует области") {
        val none = PerAppConfig()
        checkFalse(DpiScope.gdpi(none, GoodbyeDpiConfig()), "совсем пусто")
        val apps = PerAppConfig(goodbyeDpi = AppSelection(enabled = true, packages = listOf("com.discord.app")))
        checkTrue(DpiScope.gdpi(apps, GoodbyeDpiConfig()), "выбор приложений")
        checkTrue(DpiScope.gdpi(none, GoodbyeDpiConfig(domains = "example.com")), "домены")
        // Гейты независимы: выбор под zapret не открывает goodbyedpi.
        val onlyZapret = PerAppConfig(zapret = AppSelection(enabled = true, packages = listOf("com.discord.app")))
        checkFalse(DpiScope.gdpi(onlyZapret, GoodbyeDpiConfig()), "чужой выбор")
    }

    // --- Зерна из пакетов ---

    out += DevTest("scope/seeds-known", DevCategory.DPI, "Зерна известных пакетов") {
        val discord = AppDomains.seedsFor("com.discord.app")
        checkTrue("discord.com" in discord, "discord: $discord")
        val youtube = AppDomains.seedsFor("com.google.android.youtube")
        checkTrue("youtube.com" in youtube, "youtube: $youtube")
        val telegram = AppDomains.seedsFor("org.telegram.m")
        checkTrue("t.me" in telegram || "telegram.org" in telegram, "telegram: $telegram")
        val unknown = AppDomains.seedsFor("com.acme.pizza")
        checkEq(listOf("acme.com"), unknown, "кандидат для неизвестного")
    }

    out += DevTest("scope/seeds-quiet", DevCategory.DPI, "Пакеты-пустышки не дают доменов") {
        checkEq(emptyList<String>(), AppDomains.seedsFor("com.android"), "только служебные метки")
        checkEq(emptyList<String>(), AppDomains.seedsFor(""), "пустой пакет")
    }

    // --- Рекурсивное расширение ---

    out += DevTest("scope/expand-recursion", DevCategory.DPI, "Расширение: поддомены и связи") {
        val list = AppDomains.expand(listOf("discord.com"))
        checkTrue("discord.com" in list, "зерно на месте")
        checkTrue("www.discord.com" in list, "частый поддомен зоны")
        checkTrue("discord.gg" in list, "связанная зона")
        checkTrue("discordapp.com" in list, "связанная зона через цепочку")
        checkEq(list.size, list.toSet().size, "без повторов")
    }

    out += DevTest("scope/expand-parent", DevCategory.DPI, "Поддомен тянет родительскую зону") {
        val list = AppDomains.expand(listOf("cdn.discord.com"))
        checkTrue("cdn.discord.com" in list, "исходный")
        checkTrue("discord.com" in list, "родитель")
        // Поддомены достраиваются только у зоны без родителя --
        // иначе cdn.discord.com породил бы cdn.cdn.discord.com.
        checkFalse("www.cdn.discord.com" in list, "нет дубля под корнем")
    }

    out += DevTest("scope/expand-cap", DevCategory.DPI, "Расширение упирается в потолок") {
        val many = (1..400).map { "d$it.example.com" }
        val list = AppDomains.expand(many)
        checkTrue(list.size <= AppDomains.MAX_DOMAINS, "размер ${list.size} <= ${AppDomains.MAX_DOMAINS}")
        checkTrue(list.isNotEmpty(), "не пусто")
    }

    // --- Слияние ---

    out += DevTest("scope/merge", DevCategory.DPI, "Найденное дописывается к введённому") {
        checkEq("a.com, b.com", AppDomains.mergeInto("a.com", listOf("b.com")), "дописывание")
        checkEq("a.com", AppDomains.mergeInto("a.com,", listOf("a.com")), "без повторов")
        checkEq("b.com", AppDomains.mergeInto("", listOf("b.com")), "пусто")
        checkEq("a.com, b.com", AppDomains.mergeInto("a.com, b.com", emptyList()), "чужое не трогается")
    }

    // --- Сквозной путь ---

    out += DevTest("scope/discover-packages", DevCategory.DPI, "Выбор приложений даёт домены") {
        val found = AppDomains.discover(listOf("com.discord.app"))
        checkTrue("discord.com" in found, "discord в выдаче: ${found.take(5)}")
        checkTrue(found.size <= AppDomains.MAX_DOMAINS, "потолок выдачи")
        checkEq(emptyList<String>(), AppDomains.discover(emptyList()), "пустой выбор")
    }
}
