package xyz.azraellab.zapp.core.dev

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import xyz.azraellab.zapp.core.VpnConfig
import xyz.azraellab.zapp.core.net.FlexibleBase64
import xyz.azraellab.zapp.core.net.SingboxConfig
import xyz.azraellab.zapp.core.routing.ProxyCascade
import xyz.azraellab.zapp.core.routing.RouteSection
import xyz.azraellab.zapp.core.routing.SectionAction
import xyz.azraellab.zapp.core.routing.SectionPresets
import xyz.azraellab.zapp.core.routing.UrlTestFilter
import xyz.azraellab.zapp.core.routing.countryFromFlag
import xyz.azraellab.zapp.core.routing.durationSeconds
import xyz.azraellab.zapp.core.routing.durationString
import xyz.azraellab.zapp.core.routing.filterUrltestTags
import xyz.azraellab.zapp.core.routing.parseSectionDomains
import xyz.azraellab.zapp.core.routing.parseSectionPorts
import xyz.azraellab.zapp.core.routing.parseWords

/**
 * Детальная маршрутизация: секции, каскады, DNS-типы и фильтр автоподбора.
 *
 * Чистые парсеры живут без конфига, эмиссия -- через SingboxConfig:
 * обе оси проверяются здесь, чтобы правило в UI означало ровно то, что
 * попадает в JSON.
 */
internal fun routingTests(out: MutableList<DevTest>) {

    val json = Json { ignoreUnknownKeys = true }

    fun ssLink(name: String, host: String): String =
        "ss://" + FlexibleBase64.encodeNoPadding("aes-256-gcm:cGFzcw") + "@$host:8388#$name"

    val link1 = ssLink("one", "1.2.3.4")
    val link2 = ssLink("two", "5.6.7.8")

    fun build(vpn: VpnConfig): SingboxConfig.Built =
        SingboxConfig.buildGroup(vpn, listOf(link1, link2))

    fun root(built: SingboxConfig.Built): JsonObject =
        json.parseToJsonElement(built.json).jsonObject

    fun routeRules(built: SingboxConfig.Built): List<JsonObject> =
        root(built)["route"]!!.jsonObject["rules"]!!.jsonArray.map { it.jsonObject }

    fun dnsSection(built: SingboxConfig.Built): JsonObject =
        root(built)["dns"]!!.jsonObject

    // --- Парсеры доменов ---

    out += DevTest("routing/domains-parsing", DevCategory.VPN, "Домены секции: суффикс и префиксы") {
        val parsed = parseSectionDomains("ads.example.com, full:exact.host, keyword:tracker, regex:^ru\\.")
        checkEq(listOf("ads.example.com"), parsed.suffix, "суффикс по умолчанию")
        checkEq(listOf("exact.host"), parsed.exact, "full:")
        checkEq(listOf("tracker"), parsed.keyword, "keyword:")
        checkEq(listOf("^ru\\."), parsed.regex, "regex:")
        // Повторный префикс срезается, пустые куски пропускаются,
        // неизвестный префикс -- мусор, а не суффикс с двоеточием.
        val nested = parseSectionDomains("full:full:again, , weird:nope,")
        checkEq(listOf("again"), nested.exact, "повторный full:")
        checkTrue(nested.suffix.isEmpty(), "неизвестный префикс отброшен")
        checkTrue(parseSectionDomains("").isEmpty(), "пустая строка")
        checkTrue(parseSectionDomains("  , \n , ").isEmpty(), "одни разделители")
    }

    out += DevTest("routing/ports-parsing", DevCategory.VPN, "Порты секции: одиночные и диапазоны") {
        val parsed = parseSectionPorts("443, 80-90, 1000-1000, 70000, abc, 5-2")
        checkEq(listOf(443, 1000), parsed.ports, "одиночные и вырожденный диапазон")
        checkEq(listOf("80:90"), parsed.ranges, "диапазон в формате sing-box")
        checkTrue(70000 !in parsed.ports, "порт за пределами 1..65535")
        checkTrue(parsed.isEmpty().not(), "валидные порты найдены")
        checkTrue(parseSectionPorts("0, 65536, x-y").isEmpty(), "только мусор")
    }

    out += DevTest("routing/words-and-duration", DevCategory.VPN, "Списки слов и duration-строки") {
        checkEq(listOf("a", "b", "c"), parseWords(" a, b ,\nc "), "разделители запятая и перенос")
        checkEq(60, durationSeconds("1m"), "минута")
        checkEq(30, durationSeconds("30s"), "секунды")
        checkEq(7200, durationSeconds("2h"), "часы")
        checkEq(86400, durationSeconds("1d"), "сутки")
        checkEq(45, durationSeconds("45"), "голое число -- секунды")
        checkEq(null, durationSeconds("abc"), "мусор")
        checkEq(null, durationSeconds("1x"), "неизвестная единица")
        checkEq("120s", durationString(120), "обратная операция")
    }

    // --- Флаги стран и фильтр urltest ---

    out += DevTest("routing/country-from-flag", DevCategory.VPN, "Код страны из флагового эмодзи") {
        checkEq("DE", countryFromFlag("🇩🇪 Node DE"), "флаг в начале")
        checkEq("NL", countryFromFlag("Node 🇳🇱 premium"), "флаг в середине")
        checkEq("", countryFromFlag("plain node"), "без флага")
        checkEq("", countryFromFlag("🇩"), "одиночный индикатор")
        checkEq("", countryFromFlag("🇩 x"), "пара разорвана текстом")
    }

    out += DevTest("routing/urltest-filter-modes", DevCategory.VPN, "Фильтр узлов: все режимы") {
        val tags = listOf("one", "two", "🇩🇪 three", "four")
        val all = UrlTestFilter()
        checkEq(tags, filterUrltestTags(tags, all), "disabled -- без изменений")

        val include = UrlTestFilter(
            mode = UrlTestFilter.MODE_INCLUDE, includeNames = "one", includeCountries = "DE"
        )
        checkEq(listOf("one", "🇩🇪 three"), filterUrltestTags(tags, include), "имена плюс страна")

        val exclude = UrlTestFilter(
            mode = UrlTestFilter.MODE_EXCLUDE, excludeNames = "two, four"
        )
        checkEq(listOf("one", "🇩🇪 three"), filterUrltestTags(tags, exclude), "исключение имён")

        val regex = UrlTestFilter(mode = UrlTestFilter.MODE_EXCLUDE, excludeRegex = "^f.*")
        checkEq(listOf("one", "two", "🇩🇪 three"), filterUrltestTags(tags, regex), "исключение по шаблону")

        val mixed = UrlTestFilter(
            mode = UrlTestFilter.MODE_MIXED, includeNames = "one, two, four", excludeNames = "two"
        )
        checkEq(listOf("one", "four"), filterUrltestTags(tags, mixed), "включение минус исключение")
    }

    out += DevTest("routing/urltest-filter-empty-fallback", DevCategory.VPN, "Пустой результат фильтра -- все узлы") {
        val tags = listOf("one", "two")
        val filter = UrlTestFilter(mode = UrlTestFilter.MODE_INCLUDE, includeNames = "ghost")
        checkEq(tags, filterUrltestTags(tags, filter), "фильтр не нашёл никого -- группа выживает")
    }

    // --- Сериализация конфига ---

    out += DevTest("routing/config-roundtrip", DevCategory.VPN, "Новые поля VPN переживают JSON") {
        val original = VpnConfig(
            dnsType = "tls",
            dnsBootstrap = "9.9.9.9",
            dnsStrategy = "prefer_ipv4",
            dnsRewriteTtl = 120,
            urlTest = UrlTestFilter(
                mode = UrlTestFilter.MODE_MIXED,
                includeCountries = "DE",
                excludeNames = "bad",
                interval = "3m",
                toleranceMs = 25,
                idleTimeout = "30m",
                testingUrl = "https://example.com/204"
            ),
            routing = xyz.azraellab.zapp.core.VpnRouting(
                sections = listOf(
                    RouteSection(
                        label = "ads",
                        action = SectionAction.BLOCK,
                        domains = "ads.example.com",
                        ports = "443",
                        network = "tcp"
                    ),
                    RouteSection(label = "own-dns", action = SectionAction.DNS, dnsServer = "10.0.0.1")
                ),
                cascades = listOf(ProxyCascade(label = "chain", hops = "one, two")),
                activeCascade = "chain"
            )
        )
        val encoded = json.encodeToString(VpnConfig.serializer(), original)
        val decoded = json.decodeFromString(VpnConfig.serializer(), encoded)
        checkEq(original, decoded, "roundtrip")
        // Старый конфиг без новых полей читается с дефолтами.
        val old = """{"enabled":true}"""
        val fromOld = json.decodeFromString(VpnConfig.serializer(), old)
        checkEq("udp", fromOld.dnsType, "дефолт dnsType")
        checkEq(UrlTestFilter.MODE_DISABLED, fromOld.urlTest.mode, "дефолт фильтра")
        checkTrue(fromOld.routing.sections.isEmpty(), "дефолт секций")
    }

    // --- Секции в route ---

    out += DevTest("routing/section-rule-actions", DevCategory.VPN, "Секции дают правила с действиями") {
        val vpn = VpnConfig(
            routing = xyz.azraellab.zapp.core.VpnRouting(
                sections = listOf(
                    RouteSection(label = "tunnel", action = SectionAction.CONNECTION, domains = "go.example.com"),
                    RouteSection(label = "direct", action = SectionAction.DIRECT, domains = "ru.example.com"),
                    RouteSection(label = "block", action = SectionAction.BLOCK, domains = "ads.example.com"),
                    RouteSection(label = "resolver", action = SectionAction.DNS, domains = "dns.example.com", dnsServer = "10.0.0.1")
                )
            )
        )
        val rules = routeRules(build(vpn))
        val tunnel = rules.firstOrNull { it["domain_suffix"]?.jsonArray?.any { d -> d.jsonPrimitive.content == "go.example.com" } == true }
            ?: error("правило connection-секции не найдено")
        checkEq("auto", tunnel["outbound"]?.jsonPrimitive?.content, "connection идёт в финал (группа auto)")
        val direct = rules.firstOrNull { it["domain_suffix"]?.jsonArray?.any { d -> d.jsonPrimitive.content == "ru.example.com" } == true }
            ?: error("правило direct-секции не найдено")
        checkEq("direct", direct["outbound"]?.jsonPrimitive?.content, "direct уходит напрямую")
        val block = rules.firstOrNull { it["domain_suffix"]?.jsonArray?.any { d -> d.jsonPrimitive.content == "ads.example.com" } == true }
            ?: error("правило block-секции не найдено")
        checkEq("reject", block["action"]?.jsonPrimitive?.content, "block отклоняет")
        val dnsRules = dnsSection(build(vpn))["rules"]?.jsonArray.orEmpty()
        val dnsRule = dnsRules.firstOrNull { it.jsonObject["server"]?.jsonPrimitive?.content == "sec-dns-0" }
            ?: error("dns-правило секции не найдено")
        checkTrue(
            dnsRule.jsonObject["domain_suffix"]?.jsonArray?.any { d -> d.jsonPrimitive.content == "dns.example.com" } == true,
            "dns-правило несёт домены секции"
        )
        val dnsServers = dnsSection(build(vpn))["servers"]!!.jsonArray.map { it.jsonObject }
        val secServer = dnsServers.firstOrNull { it["tag"]?.jsonPrimitive?.content == "sec-dns-0" }
            ?: error("сервер dns-секции не найден")
        checkEq("10.0.0.1", secServer["server"]?.jsonPrimitive?.content, "адрес сервера секции")
    }

    out += DevTest("routing/section-matchers", DevCategory.VPN, "Все условия секции попадают в правило") {
        val vpn = VpnConfig(
            routing = xyz.azraellab.zapp.core.VpnRouting(
                sections = listOf(
                    RouteSection(
                        label = "all",
                        action = SectionAction.BLOCK,
                        domains = "full:full:bad.host, keyword:track, sub.example.com, weird:nope",
                        ipCidrs = "10.0.0.0/8",
                        ports = "53, 8000-9000",
                        network = "udp"
                    )
                )
            )
        )
        val rules = routeRules(build(vpn))
        val rule = rules.firstOrNull { it["action"]?.jsonPrimitive?.content == "reject" && it["domain_suffix"] != null }
            ?: error("правило с матчерами не найдено")
        checkEq(listOf("bad.host"), rule["domain"]!!.jsonArray.map { it.jsonPrimitive.content }, "full: -> domain")
        checkEq(listOf("track"), rule["domain_keyword"]!!.jsonArray.map { it.jsonPrimitive.content }, "keyword: -> domain_keyword")
        checkEq(listOf("sub.example.com"), rule["domain_suffix"]!!.jsonArray.map { it.jsonPrimitive.content }, "суффикс")
        checkEq(listOf("10.0.0.0/8"), rule["ip_cidr"]!!.jsonArray.map { it.jsonPrimitive.content }, "подсети")
        checkEq(listOf(53), rule["port"]!!.jsonArray.map { it.jsonPrimitive.int }, "одиночный порт")
        checkEq(listOf("8000:9000"), rule["port_range"]!!.jsonArray.map { it.jsonPrimitive.content }, "диапазон портов")
        checkEq("udp", rule["network"]?.jsonPrimitive?.content, "транспорт")
    }

    out += DevTest("routing/section-resolve-before-route", DevCategory.VPN, "resolve-правило идёт перед маршрутом") {
        val vpn = VpnConfig(
            routing = xyz.azraellab.zapp.core.VpnRouting(
                sections = listOf(
                    RouteSection(
                        label = "pin",
                        action = SectionAction.CONNECTION,
                        domains = "pinned.example.com",
                        resolveRealIp = true
                    )
                )
            )
        )
        val rules = routeRules(build(vpn))
        val resolveIndex = rules.indexOfFirst { it["action"]?.jsonPrimitive?.content == "resolve" }
        val routeIndex = rules.indexOfFirst { it["outbound"]?.jsonPrimitive?.content == "auto" && it["domain_suffix"] != null }
        checkTrue(resolveIndex >= 0, "resolve-правило есть")
        checkTrue(routeIndex >= 0, "правило секции есть")
        checkTrue(resolveIndex < routeIndex, "resolve строго раньше своего маршрута")
        checkEq("dns-0", rules[resolveIndex]["server"]?.jsonPrimitive?.content, "resolve берёт адреса у dns-0")
        // Без resolveRealIp resolve-правила нет.
        val plain = VpnConfig(
            routing = xyz.azraellab.zapp.core.VpnRouting(
                sections = listOf(RouteSection(label = "pin", action = SectionAction.CONNECTION, domains = "x.example.com"))
            )
        )
        checkTrue(
            routeRules(build(plain)).none { it["action"]?.jsonPrimitive?.content == "resolve" },
            "resolve по умолчанию не включён"
        )
    }

    out += DevTest("routing/section-disabled-skipped", DevCategory.VPN, "Выключенные и безымянные секции молчат") {
        val vpn = VpnConfig(
            routing = xyz.azraellab.zapp.core.VpnRouting(
                sections = listOf(
                    RouteSection(label = "off", enabled = false, action = SectionAction.BLOCK, domains = "a.com"),
                    RouteSection(label = "", action = SectionAction.BLOCK, domains = "b.com"),
                    RouteSection(label = "on", action = SectionAction.BLOCK, domains = "c.com")
                )
            )
        )
        val blockRules = routeRules(build(vpn)).filter { it["action"]?.jsonPrimitive?.content == "reject" }
            .filter { it["domain_suffix"] != null }
        checkEq(1, blockRules.size, "дошла только включённая и названная")
        checkEq(
            "c.com",
            blockRules.first()["domain_suffix"]!!.jsonArray.first().jsonPrimitive.content,
            "правильная секция"
        )
    }

    // --- DNS-детали ---

    out += DevTest("routing/dns-type-and-path", DevCategory.VPN, "Тип DNS-сервера и путь DoH") {
        val tls = VpnConfig(dnsType = "tls", dnsServers = "1.1.1.1")
        val tlsServer = dnsSection(SingboxConfig.buildGroup(tls, listOf(link1)))["servers"]!!.jsonArray
            .map { it.jsonObject }.first { it["tag"]?.jsonPrimitive?.content == "dns-0" }
        checkEq("tls", tlsServer["type"]?.jsonPrimitive?.content, "DoT -> type tls")
        checkEq(853, tlsServer["server_port"]?.jsonPrimitive?.int, "порт DoT")

        val doh = VpnConfig(dnsType = "https", dnsServers = "https://dns.example/dns-query")
        val dohServer = dnsSection(SingboxConfig.buildGroup(doh, listOf(link1)))["servers"]!!.jsonArray
            .map { it.jsonObject }.first { it["tag"]?.jsonPrimitive?.content == "dns-0" }
        checkEq("https", dohServer["type"]?.jsonPrimitive?.content, "DoH -> type https")
        checkEq(443, dohServer["server_port"]?.jsonPrimitive?.int, "порт DoH")
        checkEq("/dns-query", dohServer["path"]?.jsonPrimitive?.content, "путь DoH")
        checkEq("dns.example", dohServer["server"]?.jsonPrimitive?.content, "схема срезана")
    }

    out += DevTest("routing/dns-bootstrap", DevCategory.VPN, "Bootstrap для имён DNS-серверов") {
        val vpn = VpnConfig(dnsServers = "dns.example.org", dnsBootstrap = "9.9.9.9")
        val built = SingboxConfig.buildGroup(vpn, listOf(link1))
        val servers = dnsSection(built)["servers"]!!.jsonArray.map { it.jsonObject }
        val bootstrap = servers.firstOrNull { it["tag"]?.jsonPrimitive?.content == "bootstrap" }
            ?: error("bootstrap-сервер не добавлен")
        checkEq("9.9.9.9", bootstrap["server"]?.jsonPrimitive?.content, "адрес bootstrap")
        val main = servers.first { it["tag"]?.jsonPrimitive?.content == "dns-0" }
        checkEq("bootstrap", main["domain_resolver"]?.jsonPrimitive?.content, "имя сервера через bootstrap")
        val resolver = root(built)["route"]!!.jsonObject["default_domain_resolver"]?.jsonPrimitive?.content
        checkEq("bootstrap", resolver, "outbounds тоже через bootstrap")

        val plain = VpnConfig(dnsServers = "1.1.1.1")
        val plainBuilt = SingboxConfig.buildGroup(plain, listOf(link1))
        checkTrue(
            dnsSection(plainBuilt)["servers"]!!.jsonArray.none {
                it.jsonObject["tag"]?.jsonPrimitive?.content == "bootstrap"
            },
            "без настройки bootstrap не появляется"
        )
        checkEq(
            "dns-0",
            root(plainBuilt)["route"]!!.jsonObject["default_domain_resolver"]?.jsonPrimitive?.content,
            "обычный путь через dns-0"
        )
    }

    out += DevTest("routing/dns-strategy-and-ttl", DevCategory.VPN, "Стратегия адресов и TTL правил") {
        val vpn = VpnConfig(
            dnsStrategy = "prefer_ipv4",
            dnsRewriteTtl = 120,
            routing = xyz.azraellab.zapp.core.VpnRouting(
                sections = listOf(RouteSection(label = "d", action = SectionAction.DNS, domains = "a.com", dnsServer = "10.0.0.1"))
            )
        )
        val built = SingboxConfig.buildGroup(vpn, listOf(link1))
        checkEq("prefer_ipv4", dnsSection(built)["strategy"]?.jsonPrimitive?.content, "стратегия в dns-секции")
        val rule = dnsSection(built)["rules"]!!.jsonArray.map { it.jsonObject }
            .first { it["server"]?.jsonPrimitive?.content == "sec-dns-0" }
        checkEq(120, rule["rewrite_ttl"]?.jsonPrimitive?.int, "TTL из настройки")

        val off = VpnConfig(dnsStrategy = "off", dnsRewriteTtl = 0, routing = vpn.routing)
        val offBuilt = SingboxConfig.buildGroup(off, listOf(link1))
        checkTrue(dnsSection(offBuilt)["strategy"] == null, "off -- поля нет")
        checkTrue(
            dnsSection(offBuilt)["rules"]!!.jsonArray.none { it.jsonObject.containsKey("rewrite_ttl") },
            "TTL 0 -- поля нет"
        )
    }

    // --- Фильтр в собранном конфиге ---

    out += DevTest("routing/urltest-filter-in-build", DevCategory.VPN, "Фильтр ограничивает группу автоподбора") {
        val vpn = VpnConfig(
            urlTest = UrlTestFilter(mode = UrlTestFilter.MODE_INCLUDE, includeNames = "one")
        )
        val urltest = root(build(vpn))["outbounds"]!!.jsonArray.map { it.jsonObject }
            .first { it["type"]?.jsonPrimitive?.content == "urltest" }
        checkEq(listOf("one"), urltest["outbounds"]!!.jsonArray.map { it.jsonPrimitive.content }, "остался один узел")

        val custom = VpnConfig(
            urlTest = UrlTestFilter(
                interval = "3m", toleranceMs = 25, idleTimeout = "30m",
                testingUrl = "https://example.com/204"
            )
        )
        val customUrltest = root(build(custom))["outbounds"]!!.jsonArray.map { it.jsonObject }
            .first { it["type"]?.jsonPrimitive?.content == "urltest" }
        checkEq("3m", customUrltest["interval"]?.jsonPrimitive?.content, "период из настройки")
        checkEq(25, customUrltest["tolerance"]?.jsonPrimitive?.int, "допуск из настройки")
        checkEq("30m", customUrltest["idle_timeout"]?.jsonPrimitive?.content, "простой из настройки")
        checkEq(
            "https://example.com/204",
            customUrltest["url"]?.jsonPrimitive?.content,
            "URL проверки из настройки"
        )
    }

    // --- Каскады ---

    out += DevTest("routing/cascade-chain", DevCategory.VPN, "Каскад: вход -- финал, выход -- напрямую") {
        val vpn = VpnConfig(
            routing = xyz.azraellab.zapp.core.VpnRouting(
                cascades = listOf(ProxyCascade(label = "chain", hops = "two, one")),
                activeCascade = "chain"
            )
        )
        val built = build(vpn)
        val outbounds = root(built)["outbounds"]!!.jsonArray.map { it.jsonObject }
        val entry = outbounds.first { it["tag"]?.jsonPrimitive?.content == "two" }
        val exit = outbounds.first { it["tag"]?.jsonPrimitive?.content == "one" }
        checkEq("one", entry["detour"]?.jsonPrimitive?.content, "вход набирает следующий узел")
        checkTrue(exit["detour"] == null, "выход дозванивается до сети напрямую")
        checkEq("two", root(built)["route"]!!.jsonObject["final"]?.jsonPrimitive?.content, "финал -- входной узел")
        checkTrue(built.notes.any { it.startsWith("cascade 'chain'") }, "запись в заметках")
    }

    out += DevTest("routing/cascade-off-keeps-group", DevCategory.VPN, "Выключенный каскад не трогает группу") {
        val vpn = VpnConfig(
            routing = xyz.azraellab.zapp.core.VpnRouting(
                cascades = listOf(ProxyCascade(label = "chain", hops = "two, one"))
            )
        )
        val built = build(vpn)
        val outbounds = root(built)["outbounds"]!!.jsonArray.map { it.jsonObject }
        checkTrue(outbounds.none { it.containsKey("detour") }, "detour нет")
        checkEq("auto", root(built)["route"]!!.jsonObject["final"]?.jsonPrimitive?.content, "финал -- группа")
    }

    out += DevTest("routing/cascade-invalid-throws", DevCategory.VPN, "Каскад с ошибками -- честная ошибка") {
        val missing = VpnConfig(
            routing = xyz.azraellab.zapp.core.VpnRouting(
                cascades = listOf(ProxyCascade(label = "bad", hops = "one, ghost")),
                activeCascade = "bad"
            )
        )
        val missingError = runCatching { build(missing) }.exceptionOrNull()
        checkTrue(missingError is IllegalArgumentException, "несуществующий hop: $missingError")
        checkContains(missingError?.message ?: "", "ghost")

        val unknown = VpnConfig(
            routing = xyz.azraellab.zapp.core.VpnRouting(activeCascade = "void")
        )
        val unknownError = runCatching { build(unknown) }.exceptionOrNull()
        checkTrue(unknownError is IllegalArgumentException, "нет каскада с меткой: $unknownError")

        val single = VpnConfig(
            routing = xyz.azraellab.zapp.core.VpnRouting(
                cascades = listOf(ProxyCascade(label = "tiny", hops = "one")),
                activeCascade = "tiny"
            )
        )
        val singleError = runCatching { build(single) }.exceptionOrNull()
        checkTrue(singleError is IllegalArgumentException, "один hop: $singleError")
    }

    out += DevTest("routing/cascade-ignored-in-single-session", DevCategory.VPN, "Одиночная сессия -- каскад тихо вежлив") {
        val vpn = VpnConfig(
            routing = xyz.azraellab.zapp.core.VpnRouting(
                cascades = listOf(ProxyCascade(label = "chain", hops = "one, ghost")),
                activeCascade = "chain"
            )
        )
        // Один узел: несуществующий hop -- «не в этой сессии», а не падение.
        val built = SingboxConfig.buildGroup(vpn, listOf(link1))
        checkTrue(built.notes.any { it.contains("ignored") }, "заметка про пропуск: ${built.notes}")
        checkTrue(
            root(built)["outbounds"]!!.jsonArray.none { it.jsonObject.containsKey("detour") },
            "цепочка не накинута"
        )
    }

    // --- Пресеты секций ---

    out += DevTest("routing/preset-shapes", DevCategory.VPN, "Пресеты: действие и непустые условия") {
        val ads = SectionPresets.ads("Ads (block)")
        checkEq(SectionAction.BLOCK, ads.action, "реклама блокируется")
        checkTrue(ads.enabled, "свежая секция включена")
        checkTrue(ads.domains.isNotBlank(), "домены рекламы есть")

        val ru = SectionPresets.russia("Russia (direct)")
        checkEq(SectionAction.DIRECT, ru.action, "свои сервисы -- напрямую")
        checkTrue(ru.domains.contains("yandex.ru"), "в списке есть своё")

        val local = SectionPresets.local("Local networks")
        checkEq(SectionAction.DIRECT, local.action, "локальные сети -- напрямую")
        checkTrue(local.ipCidrs.contains("192.168.0.0/16"), "home-подсети на месте")
        checkTrue(local.ipCidrs.contains("fc00::/7"), "IPv6 ULA на месте")
    }

    out += DevTest("routing/preset-domains-parse", DevCategory.VPN, "Домены пресетов проходят обычный парсер") {
        val ads = parseSectionDomains(SectionPresets.ads("Ads").domains)
        checkTrue(ads.suffix.contains("doubleclick.net"), "рекламная зона разобрана")
        checkTrue(ads.suffix.size >= 10, "рекламных зон достаточно: ${ads.suffix.size}")
        checkTrue(ads.exact.isEmpty() && ads.keyword.isEmpty() && ads.regex.isEmpty(), "пресет чистый, без префиксов")

        val ru = parseSectionDomains(SectionPresets.russia("Russia").domains)
        checkTrue(ru.suffix.containsAll(listOf("yandex.ru", "mail.ru", "vk.com")), "свои сервисы разобраны")
    }

    out += DevTest("routing/preset-add-dedupe", DevCategory.VPN, "Повторный пресет получает номер, а не тихо теряется") {
        val first = SectionPresets.add(emptyList(), SectionPresets.ads("Ads"))
        checkEq(1, first.size, "первый пресет лёг")
        checkEq("Ads", first[0].label, "метка из кнопки")

        val dup = SectionPresets.add(first, SectionPresets.ads("Ads"))
        checkEq(2, dup.size, "повтор добавлен, а не пропущен")
        checkEq("Ads (2)", dup[1].label, "суффикс метки")

        val third = SectionPresets.add(dup, SectionPresets.ads("Ads"))
        checkEq("Ads (3)", third[2].label, "номер растёт")

        // Рукописная секция с той же меткой занятый номер не забирает.
        val withForeign = first + RouteSection(label = "Ads (2)")
        val afterForeign = SectionPresets.add(withForeign, SectionPresets.ads("Ads"))
        checkEq("Ads (3)", afterForeign[2].label, "занятое имя пропущено")

        // Новая метка живёт без суффиксов.
        val mixed = SectionPresets.add(first, SectionPresets.local("Local"))
        checkEq("Local", mixed[1].label, "новое имя без суффикса")
    }

    out += DevTest("routing/preset-emission", DevCategory.VPN, "Пресет попадает в route.rules обычным правилом") {
        val vpn = VpnConfig(routing = xyz.azraellab.zapp.core.VpnRouting(sections = listOf(SectionPresets.ads("Ads"))))
        val rules = routeRules(build(vpn))
        val reject = rules.filter { it["action"]?.jsonPrimitive?.content == "reject" }
        checkTrue(reject.isNotEmpty(), "правило reject собрано")
        val domains = reject.flatMap { it["domain_suffix"]?.jsonArray.orEmpty().map { d -> d.jsonPrimitive.content } }
        checkTrue(domains.contains("doubleclick.net"), "домен пресета в rules: $domains")

        val localVpn = VpnConfig(routing = xyz.azraellab.zapp.core.VpnRouting(sections = listOf(SectionPresets.local("Local"))))
        val localRules = routeRules(build(localVpn)).filter { it["action"]?.jsonPrimitive?.content == "direct" || it["outbound"] != null }
        val cidrs = localRules.flatMap { it["ip_cidr"]?.jsonArray.orEmpty().map { c -> c.jsonPrimitive.content } }
        checkTrue(cidrs.contains("192.168.0.0/16"), "подсети в rules: $cidrs")
    }
}
