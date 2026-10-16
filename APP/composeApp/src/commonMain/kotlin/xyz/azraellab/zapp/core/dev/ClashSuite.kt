package xyz.azraellab.zapp.core.dev

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import xyz.azraellab.zapp.core.VpnConfig
import xyz.azraellab.zapp.core.VpnProfile
import xyz.azraellab.zapp.core.VpnProtocol
import xyz.azraellab.zapp.core.VpnRouting
import xyz.azraellab.zapp.core.net.ClashConfig
import xyz.azraellab.zapp.core.net.ClashYaml
import xyz.azraellab.zapp.core.net.ConfigImport
import xyz.azraellab.zapp.core.net.LinkTarget
import xyz.azraellab.zapp.core.net.ProbeKind
import xyz.azraellab.zapp.core.net.SingboxConfig
import xyz.azraellab.zapp.core.net.probeTarget
import xyz.azraellab.zapp.core.routing.ProxyCascade
import xyz.azraellab.zapp.core.routing.RouteSection

/**
 * Импорт Clash-конфига: YAML-разбор, узнавание формата и перевод в sing-box.
 *
 * Три слоя по отдельности: парсер не знает про sing-box, распознавание --
 * про конвертер, конвертер -- про импорт. Общий приём -- честность
 * пропусков: всё непереводимое обязано вернуться заметкой в журнал
 * туннеля, молчаливое исчезновение чужих правил ломает сеть незаметно.
 */
internal fun clashTests(out: MutableList<DevTest>) {

    val parser = Json { ignoreUnknownKeys = true }

    fun buildClash(yaml: String = CLASH_FIXTURE, vpn: VpnConfig = VpnConfig()): SingboxConfig.Built =
        SingboxConfig.build(
            VpnProfile(protocol = VpnProtocol.CLASH.code, rawConfig = yaml),
            vpn
        )

    fun rootOf(built: SingboxConfig.Built): JsonObject =
        parser.parseToJsonElement(built.json).jsonObject

    fun outboundList(root: JsonObject): List<JsonObject> =
        root["outbounds"]!!.jsonArray.map { it.jsonObject }

    fun endpointList(root: JsonObject): List<JsonObject> =
        root["endpoints"]!!.jsonArray.map { it.jsonObject }

    fun tagsOf(list: List<JsonObject>): List<String> =
        list.map { it["tag"]!!.jsonPrimitive.content }

    fun byTag(list: List<JsonObject>, tag: String): JsonObject =
        list.find { it["tag"]?.jsonPrimitive?.content == tag } ?: error("нет тега '$tag'")

    fun notesOf(built: SingboxConfig.Built): String = built.notes.joinToString("\n")

    fun ruleList(root: JsonObject): List<JsonObject> =
        root["route"]!!.jsonObject["rules"]!!.jsonArray.map { it.jsonObject }

    // --- YAML-парсер ---

    out += DevTest("clash/yaml-scalars", DevCategory.PARSERS, "clash yaml: скаляры, кавычки, комментарии") {
        val root = requireNotNull(ClashYaml.parse(
            """
            port: 443
            flag: yes
            name: "with # hash"
            url: http://x.example/y#frag
            empty:
            # комментарий целиком
            quoted: 'a: b'
            """.trimIndent()
        ))
        checkEq("443", root.str("port"), "строковое число")
        checkEq(443, root.int("port"), "целое")
        checkTrue(root.bool("flag"), "yes -- true")
        checkEq("with # hash", root.str("name"), "решетка в кавычках -- не комментарий")
        checkEq("http://x.example/y#frag", root.str("url"), "решетка без пробела -- часть значения")
        checkEq("", root.str("empty"), "пустое значение")
        checkEq("a: b", root.str("quoted"), "одинарные кавычки с двоеточием")
    }

    out += DevTest("clash/yaml-seq-root", DevCategory.PARSERS, "clash yaml: список на отступе ключа") {
        val root = requireNotNull(ClashYaml.parse(
            """
            rules:
            - one
            - two
            after: x
            """.trimIndent()
        ))
        checkEq(listOf("one", "two"), root.texts("rules"), "список без дополнительного отступа")
        checkEq("x", root.str("after"), "ключ после списка")
    }

    out += DevTest("clash/yaml-seq-maps", DevCategory.PARSERS, "clash yaml: список карт с вложенностью") {
        val root = requireNotNull(ClashYaml.parse(
            """
            proxies:
              - name: n1
                type: ss
                meta:
                  k: v
              - name: n2
            """.trimIndent()
        ))
        val items = root.objs("proxies")
        checkEq(2, items.size, "два узла")
        checkEq("n1", items[0].str("name"), "первый узел")
        checkEq("ss", items[0].str("type"), "второй ключ той же карты")
        checkEq("v", items[0].obj("meta")?.str("k"), "вложенная карта")
        checkEq("n2", items[1].str("name"), "второй узел после вложенности")
    }

    out += DevTest("clash/yaml-flow", DevCategory.PARSERS, "clash yaml: flow-списки и flow-карты") {
        val root = requireNotNull(ClashYaml.parse(
            """
            alpn: [h3, http/1.1]
            opts: {path: /ws, headers: {Host: h.example}}
            quoted: "a, b"
            """.trimIndent()
        ))
        checkEq(listOf("h3", "http/1.1"), root.textList("alpn"), "список в скобках")
        checkEq("/ws", root.obj("opts")?.str("path"), "карта в скобках")
        checkEq("h.example", root.obj("opts")?.obj("headers")?.str("Host"), "вложенная карта в скобках")
        checkEq("a, b", root.str("quoted"), "запятая в кавычках -- не разделитель")
    }

    out += DevTest("clash/yaml-block-scalar", DevCategory.PARSERS, "clash yaml: блочный скаляр |") {
        val root = requireNotNull(ClashYaml.parse(
            """
            note: |
              line one
              line two
            after: x
            """.trimIndent()
        ))
        checkEq("line one\nline two", root.str("note"), "строки блока через перевод строки")
        checkEq("x", root.str("after"), "ключ после блока")
    }

    out += DevTest("clash/yaml-broken", DevCategory.PARSERS, "clash yaml: мусор и корень-список -- null") {
        checkEq(null, ClashYaml.parse(""), "пустой текст")
        checkEq(null, ClashYaml.parse("просто строка без структуры"), "нет ни ключей, ни разделителей")
        checkEq(null, ClashYaml.parse("- a\n- b"), "корнем идёт список")
    }

    // --- Распознавание формата ---

    out += DevTest("clash/detect", DevCategory.PARSERS, "clash: импорт YAML -- Kind.CLASH, шлюз с первого узла") {
        val result = ConfigImport.import(CLASH_FIXTURE)
        // В тексте есть `client-fingerprint:` -- без порядка «clash раньше
        // OpenVPN» regex там поймал бы строку и вернул бы профиль OpenVPN.
        checkEq(ConfigImport.Kind.CLASH, result.kind, "kind")
        val profile = result.profiles.single()
        checkEq(VpnProtocol.CLASH.code, profile.protocol, "protocol")
        checkFalse(profile.protocol == VpnProtocol.OPENVPN.code, "не OpenVPN, хотя client-fingerprint в тексте")
        checkEq("test nodes", profile.name, "имя из верхнеуровневого name")
        checkEq("vless.example.com", profile.gateway, "gateway с первого узла")
        checkEq(443, profile.gatewayPort, "gatewayPort с первого узла")
        checkEq(CLASH_FIXTURE, profile.rawConfig, "rawConfig без изменений")
    }

    out += DevTest("clash/detect-json", DevCategory.PARSERS, "clash: JSON не считается YAML") {
        val result = ConfigImport.import("""{"outbounds":[{"type":"direct"}]}""")
        checkEq(ConfigImport.Kind.PROFILE, result.kind, "kind")
        checkEq(VpnProtocol.SINGBOX.code, result.profiles.single().protocol, "protocol")
    }

    out += DevTest("clash/detect-wg", DevCategory.PARSERS, "clash: wg-ini распознаётся раньше YAML") {
        val result = ConfigImport.import(
            """
            [Interface]
            PrivateKey = cHJp
            Address = 10.0.0.2/32
            [Peer]
            PublicKey = cHVj
            AllowedIPs = 0.0.0.0/0
            Endpoint = 1.2.3.4:51820
            """.trimIndent()
        )
        checkEq(ConfigImport.Kind.PROFILE, result.kind, "kind")
        checkEq(VpnProtocol.WIREGUARD.code, result.profiles.single().protocol, "protocol")
    }

    out += DevTest("clash/detect-links", DevCategory.PARSERS, "clash: список ссылок -- не YAML") {
        val result = ConfigImport.import(
            "vless://11111111-1111-4111-8111-111111111111@a.example:443#x\n" +
                "trojan://pw@b.example:443#y"
        )
        checkEq(ConfigImport.Kind.LIST, result.kind, "kind")
    }

    out += DevTest("clash/detect-none", DevCategory.PARSERS, "clash: обычный текст -- NONE") {
        val result = ConfigImport.import("обычный текст без формата конфига")
        checkEq(ConfigImport.Kind.NONE, result.kind, "kind")
    }

    out += DevTest("clash/detect-no-proxies", DevCategory.PARSERS, "clash: конфиг без узлов не импортируется") {
        val result = ConfigImport.import(
            """
            proxies: []
            proxy-groups:
              - name: only-providers
                type: select
                use:
                  - my-provider
            """.trimIndent()
        )
        checkEq(ConfigImport.Kind.NONE, result.kind, "провайдеров без узлов -- не профиль")
    }

    // --- Перевод в sing-box ---

    out += DevTest("clash/build-proxy-types", DevCategory.PARSERS, "clash: узлы становятся outbound'ами, wireguard -- endpoint") {
        val built = buildClash()
        val root = rootOf(built)
        val out = outboundList(root)
        val types = out.map { it["type"]!!.jsonPrimitive.content }
        checkEq(2, types.count { it == "vless" }, "два vless")
        listOf("vmess", "trojan", "shadowsocks", "hysteria2", "tuic", "socks").forEach { type ->
            checkIn(type, types, "тип")
        }
        val tags = tagsOf(out)
        checkFalse("snell-node" in tags, "snell пропущен")
        checkFalse("ss-plugin" in tags, "ss с плагином пропущен")
        checkEq(listOf("wg-node"), tagsOf(endpointList(root)), "wireguard ушёл в endpoints")
        checkContains(notesOf(built), "clash: translated 9 of 11 proxies", "счётчик переведённых")
    }

    out += DevTest("clash/build-reality-ws", DevCategory.PARSERS, "clash: reality-узел и ws-транспорт") {
        val out = outboundList(rootOf(buildClash()))
        val reality = byTag(out, "reality-node")["tls"]!!.jsonObject
        checkEq("AQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQE", reality["reality"]!!.jsonObject["public_key"]?.jsonPrimitive?.content, "pbk приведен к raw base64url")
        checkEq("abcd1234", reality["reality"]!!.jsonObject["short_id"]?.jsonPrimitive?.content, "short id")
        checkEq("reality.example.com", reality["server_name"]?.jsonPrimitive?.content, "server_name с сервера")
        val wsNode = byTag(out, "vless-node")
        val transport = wsNode["transport"]!!.jsonObject
        checkEq("ws", transport["type"]?.jsonPrimitive?.content, "transport")
        checkEq("/ws", transport["path"]?.jsonPrimitive?.content, "path из ws-opts")
        checkEq("cdn.example.com", transport["headers"]!!.jsonObject["Host"]?.jsonPrimitive?.content, "Host из headers")
        val wsTls = wsNode["tls"]!!.jsonObject
        checkEq("cdn.example.com", wsTls["server_name"]?.jsonPrimitive?.content, "servername")
        checkEq("chrome", wsTls["utls"]?.jsonObject?.get("fingerprint")?.jsonPrimitive?.content, "fingerprint")
    }

    out += DevTest("clash/build-reality-normalize", DevCategory.PARSERS, "clash: pbk со знаками +/ и паддингом приводится к raw base64url") {
        val yaml = """
            proxies:
              - name: r1
                type: vless
                server: r.example.com
                port: 443
                uuid: 11111111-1111-4111-8111-111111111115
                tls: true
                reality-opts:
                  public-key: NGC+MSAeaf7aoO7ouZl/XHwpmf2v5ZMlPNZUr0361xQ=
                  short-id: abcd1234
        """.trimIndent()
        val out = outboundList(rootOf(buildClash(yaml)))
        val reality = byTag(out, "r1")["tls"]!!.jsonObject["reality"]!!.jsonObject
        checkEq(
            "NGC-MSAeaf7aoO7ouZl_XHwpmf2v5ZMlPNZUr0361xQ",
            reality["public_key"]?.jsonPrimitive?.content,
            "стандартный алфавит и = заменены"
        )
    }

    out += DevTest("clash/build-hysteria2", DevCategory.PARSERS, "clash: hysteria2 -- ports, up/down, obfs") {
        val hy = byTag(outboundList(rootOf(buildClash())), "hy2-node")
        checkEq("443", hy["server_port"]?.jsonPrimitive?.content, "первый из ports")
        checkEq("50", hy["up_mbps"]?.jsonPrimitive?.content, "up из '50 Mbps'")
        checkEq("100", hy["down_mbps"]?.jsonPrimitive?.content, "down из '100 Mbps'")
        checkEq("salamander", hy["obfs"]!!.jsonObject["type"]?.jsonPrimitive?.content, "obfs")
        checkEq("obfs-pass", hy["obfs"]!!.jsonObject["password"]?.jsonPrimitive?.content, "obfs-password")
        checkEq("true", hy["tls"]!!.jsonObject["insecure"]?.jsonPrimitive?.content, "skip-cert-verify")
    }

    out += DevTest("clash/build-tuic", DevCategory.PARSERS, "clash: tuic -- token разбирается на uuid:пароль") {
        val tuic = byTag(outboundList(rootOf(buildClash())), "tuic-node")
        checkEq("11111111-1111-4111-8111-111111111114", tuic["uuid"]?.jsonPrimitive?.content, "uuid")
        checkEq("tuic-pass", tuic["password"]?.jsonPrimitive?.content, "password")
        checkEq("bbr", tuic["congestion_control"]?.jsonPrimitive?.content, "congestion_control")
    }

    out += DevTest("clash/build-skips", DevCategory.PARSERS, "clash: пропущенные узлы объяснены заметками") {
        val built = buildClash()
        val notes = notesOf(built)
        checkContains(notes, "clash: skipped ss (plugin)", "ss с плагином")
        checkContains(notes, "clash: skipped snell (unsupported type)", "snell")
        checkNotContains(built.json, "obfs-local", "плагин не дотянул до конфига")
    }

    out += DevTest("clash/build-wireguard-endpoint", DevCategory.PARSERS, "clash: wireguard-узел в endpoint sing-box") {
        val ep = byTag(endpointList(rootOf(buildClash())), "wg-node")
        checkEq("wireguard", ep["type"]?.jsonPrimitive?.content, "type")
        checkEq("YBnwt7W7hFXM4qNOM+Fsu6m9wZDEWVn0mN0aB0uBqHc=", ep["private_key"]?.jsonPrimitive?.content, "private_key")
        checkEq("172.16.0.2/32", ep["address"]!!.jsonArray[0].jsonPrimitive.content, "address")
        checkEq("1408", ep["mtu"]?.jsonPrimitive?.content, "mtu")
        val peer = ep["peers"]!!.jsonArray[0].jsonObject
        checkEq("wg.example.com", peer["address"]?.jsonPrimitive?.content, "адрес пира")
        checkEq("51820", peer["port"]?.jsonPrimitive?.content, "порт пира")
        checkEq("0.0.0.0/0", peer["allowed_ips"]!!.jsonArray[0].jsonPrimitive.content, "allowed_ips")
    }

    out += DevTest("clash/build-groups", DevCategory.PARSERS, "clash: группы -- selector/urltest, мусорные участники отфильтрованы") {
        val built = buildClash()
        val out = outboundList(rootOf(built))
        val main = byTag(out, "main")
        checkEq("selector", main["type"]?.jsonPrimitive?.content, "select -> selector")
        checkEq(
            listOf("direct", "auto-best", "trojan-node"),
            main["outbounds"]!!.jsonArray.map { it.jsonPrimitive.content },
            "DIRECT и живые участники, REJECT и призрак отброшены"
        )
        val auto = byTag(out, "auto-best")
        checkEq("urltest", auto["type"]?.jsonPrimitive?.content, "url-test -> urltest")
        checkEq("http://www.gstatic.com/generate_204", auto["url"]?.jsonPrimitive?.content, "url")
        checkEq("300s", auto["interval"]?.jsonPrimitive?.content, "interval секунды -> длительность")
        checkEq("50", auto["tolerance"]?.jsonPrimitive?.content, "tolerance мс")
        checkEq("true", auto["interrupt_exist_connections"]?.jsonPrimitive?.content, "разрыв при переключении")
        checkEq("urltest", byTag(out, "fallback-g")["type"]?.jsonPrimitive?.content, "fallback -> urltest")
        checkEq("selector", byTag(out, "lb-g")["type"]?.jsonPrimitive?.content, "load-balance -> selector")
        val tags = tagsOf(out)
        checkFalse("relay-g" in tags, "relay пропущена")
        checkFalse("empty-g" in tags, "группа без живых участников пропущена")
        val notes = notesOf(built)
        checkContains(notes, "clash: group 'main': REJECT member skipped", "REJECT-участник объяснён")
        checkContains(notes, "clash: group 'fallback-g': fallback -> urltest", "заметка fallback")
        checkContains(notes, "clash: group 'lb-g': load-balance -> selector", "заметка load-balance")
        checkContains(notes, "clash: skipped group 'relay-g' (relay)", "заметка relay")
        checkContains(notes, "clash: group 'empty-g' has no usable members", "заметка пустой группы")
        checkNotContains(notes, "ghost", "призрачный участник уходит молча -- это не заметка, а чистка")
    }

    out += DevTest("clash/build-rules", DevCategory.PARSERS, "clash: правила -- матчеры и цели sing-box") {
        val rules = ruleList(rootOf(buildClash()))
        fun ruleWith(fragment: String): JsonObject =
            rules.find { it.toString().contains(fragment) } ?: error("нет правила с '$fragment'")
        checkEq("main", ruleWith("google.com")["outbound"]?.jsonPrimitive?.content, "suffix -> группе")
        checkEq("reject", ruleWith("ads.example.com")["action"]?.jsonPrimitive?.content, "REJECT -> reject")
        checkEq("reject", ruleWith("tracker")["action"]?.jsonPrimitive?.content, "keyword")
        checkEq("auto-best", ruleWith("cdn")["outbound"]?.jsonPrimitive?.content, "regex")
        val noResolve = ruleWith("2001:db8")
        checkEq("direct", noResolve["outbound"]?.jsonPrimitive?.content, "CIDR6 -> direct")
        checkFalse("no_resolve" in noResolve.toString(), "no-resolve -- не поле sing-box")
        checkIn("process_name", rules.flatMap { it.keys }, "PROCESS-NAME")
        val portRule = rules.first { "port" in it }
        checkEq("443", portRule["port"]!!.jsonArray[0].jsonPrimitive.content, "одиночный порт числом")
        val rangeRule = rules.first { "port_range" in it }
        checkEq("8000:9000", rangeRule["port_range"]!!.jsonArray[0].jsonPrimitive.content, "диапазон через двоеточие")
        val networkRule = rules.first { "network" in it }
        checkEq("tcp", networkRule["network"]!!.jsonArray[0].jsonPrimitive.content, "network")
        checkEq("main", rootOf(buildClash())["route"]!!.jsonObject["final"]?.jsonPrimitive?.content, "final из MATCH")
    }

    out += DevTest("clash/build-rules-skipped", DevCategory.PARSERS, "clash: непереводимые правила уходят с заметками") {
        val built = buildClash()
        val notes = notesOf(built)
        checkContains(notes, "clash: skipped GEOIP rule (geo database removed in sing-box 1.12)", "GEOIP")
        checkContains(notes, "clash: skipped RULE-SET rule (providers are not converted)", "RULE-SET")
        checkContains(notes, "clash: skipped logical rule (AND)", "логическое правило")
        checkContains(notes, "clash: skipped unknown rule type (WEIRD-TYPE)", "неизвестный тип")
        checkContains(notes, "clash: skipped rule: target 'missing-group' not found", "несуществующая цель")
        checkNotContains(built.json, "\"geoip\"", "geoip не попал в конфиг")
        checkNotContains(built.json, "\"rule_set\"", "rule_set не попал в конфиг")
    }

    out += DevTest("clash/build-providers-note", DevCategory.PARSERS, "clash: провайдеры не качаются и объяснены") {
        checkContains(notesOf(buildClash()), "clash: 2 provider(s) ignored (never fetched)", "счётчик провайдеров")
    }

    out += DevTest("clash/build-no-auto", DevCategory.PARSERS, "clash: при своих группах встроенный auto не добавляется") {
        val root = rootOf(buildClash())
        checkFalse("auto" in tagsOf(outboundList(root)), "auto не нужен: группы свои")
    }

    out += DevTest("clash/build-no-groups", DevCategory.PARSERS, "clash: без групп -- auto по узлам и final на auto") {
        val yaml = """
            proxies:
              - name: n1
                type: ss
                server: s1.example.com
                port: 8388
                cipher: aes-256-gcm
                password: p1
              - name: n2
                type: ss
                server: s2.example.com
                port: 8388
                cipher: aes-256-gcm
                password: p2
        """.trimIndent()
        val built = buildClash(yaml)
        val root = rootOf(built)
        val out = outboundList(root)
        checkEq("urltest", byTag(out, "auto")["type"]?.jsonPrimitive?.content, "встроенный urltest")
        checkEq("auto", root["route"]!!.jsonObject["final"]?.jsonPrimitive?.content, "final -- auto")
        checkContains(notesOf(built), "clash: translated 2 of 2 proxies", "счётчик")
    }

    out += DevTest("clash/build-match-reject", DevCategory.PARSERS, "clash: MATCH,REJECT -- безусловный reject в конце") {
        val yaml = """
            proxies:
              - name: only
                type: ss
                server: s.example.com
                port: 8388
                cipher: aes-256-gcm
                password: p
            rules:
              - MATCH,REJECT
        """.trimIndent()
        val built = buildClash(yaml)
        val root = rootOf(built)
        checkEq("direct", root["route"]!!.jsonObject["final"]?.jsonPrimitive?.content, "final достижим и ведёт в direct")
        val rules = ruleList(root)
        checkEq("reject", rules.last()["action"]?.jsonPrimitive?.content, "последнее правило -- безусловный reject")
        checkContains(notesOf(built), "clash: MATCH,REJECT -- unmatched traffic rejected", "заметка")
    }

    out += DevTest("clash/build-endpoint-final", DevCategory.PARSERS, "clash: только wireguard -- final на endpoint тег") {
        val yaml = """
            proxies:
              - name: wg1
                type: wireguard
                server: wg.example.com
                port: 51820
                private-key: YBnwt7W7hFXM4qNOM+Fsu6m9wZDEWVn0mN0aB0uBqHc=
                public-key: bm90LWEtcmVhbC1rZXktMDAwMDAwMDAwMDAwMDAwMDAwMDAwMDA=
                ip: 10.0.0.2/32
        """.trimIndent()
        val root = rootOf(buildClash(yaml))
        checkEq("wg1", root["route"]!!.jsonObject["final"]?.jsonPrimitive?.content, "final -- тег endpoint")
        checkFalse("wg-ep" in tagsOf(outboundList(root)), "служебный тег WireGuard-профиля тут не причём")
        checkIn("wg1", tagsOf(endpointList(root)), "endpoint существует")
    }

    out += DevTest("clash/build-tag-collision", DevCategory.PARSERS, "clash: имена auto/direct переименовываются, ссылки следуют") {
        val yaml = """
            proxies:
              - name: auto
                type: ss
                server: a.example.com
                port: 8388
                cipher: aes-256-gcm
                password: p1
              - name: direct
                type: ss
                server: d.example.com
                port: 8388
                cipher: aes-256-gcm
                password: p2
            proxy-groups:
              - name: pick
                type: select
                proxies: [auto, direct]
            rules:
              - MATCH,pick
        """.trimIndent()
        val built = buildClash(yaml)
        val out = outboundList(rootOf(built))
        checkEq(listOf("auto (2)", "direct (2)", "pick", "direct"), tagsOf(out), "служебные теги не пересечены")
        checkEq(
            listOf("auto (2)", "direct (2)"),
            byTag(out, "pick")["outbounds"]!!.jsonArray.map { it.jsonPrimitive.content },
            "учстники взяты по новым тегам"
        )
        checkContains(notesOf(built), "clash: tag 'auto' -> 'auto (2)'", "заметка переименования auto")
        checkContains(notesOf(built), "clash: tag 'direct' -> 'direct (2)'", "заметка переименования direct")
    }

    out += DevTest("clash/build-rule-order", DevCategory.PARSERS, "clash: правила пользователя выше импортированных") {
        val vpn = VpnConfig(
            routing = VpnRouting(sections = listOf(RouteSection(label = "sec", domains = "sec.example")))
        )
        val rules = ruleList(rootOf(buildClash(vpn = vpn)))
        val sectionIndex = rules.indexOfFirst { it.toString().contains("sec.example") }
        val clashIndex = rules.indexOfFirst { it.toString().contains("google.com") }
        checkTrue(
            sectionIndex >= 0 && clashIndex >= 0 && sectionIndex < clashIndex,
            "секция ($sectionIndex) должна идти выше clash ($clashIndex)"
        )
    }

    out += DevTest("clash/build-cascade-ignored", DevCategory.PARSERS, "clash: каскад не перебивает финал конфига") {
        val vpn = VpnConfig(
            routing = VpnRouting(
                cascades = listOf(ProxyCascade(label = "c", hops = "a, b")),
                activeCascade = "c"
            )
        )
        checkContains(notesOf(buildClash(vpn = vpn)), "ignored: session has its own final", "каскад объяснён")
    }

    // --- Честные ошибки ---

    out += DevTest("clash/error-not-yaml", DevCategory.PARSERS, "clash: текст без структуры -- ошибка про YAML") {
        val error = runCatching { buildClash("просто текст без структуры") }.exceptionOrNull()
        checkTrue(error is IllegalArgumentException, "тип ошибки: $error")
        checkContains(error?.message.orEmpty(), "not valid YAML", "текст ошибки")
    }

    out += DevTest("clash/error-no-proxies", DevCategory.PARSERS, "clash: YAML без узлов -- ошибка про узлы") {
        val error = runCatching { buildClash("proxies: []") }.exceptionOrNull()
        checkTrue(error is IllegalArgumentException, "тип ошибки: $error")
        checkContains(error?.message.orEmpty(), "has no proxies", "текст ошибки")
    }

    out += DevTest("clash/error-empty", DevCategory.PARSERS, "clash: пустой профиль -- ошибка про конфиг") {
        val error = runCatching { buildClash("") }.exceptionOrNull()
        checkTrue(error != null, "ошибка обязательна")
        checkContains(error?.message.orEmpty(), "no config text", "текст ошибки")
    }

    // --- Проба живости ---

    out += DevTest("clash/probe-gateway", DevCategory.PARSERS, "clash: живость проверяется по шлюзу первого узла") {
        val withGateway = VpnProfile(
            protocol = VpnProtocol.CLASH.code,
            gateway = "gw.example.com",
            gatewayPort = 8443,
            rawConfig = CLASH_FIXTURE
        )
        checkEq(LinkTarget("gw.example.com", 8443, ProbeKind.TCP), probeTarget(withGateway), "шлюз")
        val noGateway = VpnProfile(protocol = VpnProtocol.CLASH.code, rawConfig = CLASH_FIXTURE)
        checkEq(null, probeTarget(noGateway), "без шлюза проверять нечего")
    }
}

/** Тестовый clash-конфиг: почти все типы узлов, групп и правил в одном файле. */
private val CLASH_FIXTURE = """
# тестовый clash-конфиг
mixed-port: 7890
mode: rule
name: "test nodes"
proxies:
  - name: vless-node
    type: vless
    server: vless.example.com
    port: 443
    uuid: 11111111-1111-4111-8111-111111111111
    network: ws
    tls: true
    servername: cdn.example.com
    client-fingerprint: chrome
    ws-opts:
      path: /ws
      headers:
        Host: cdn.example.com
  - name: reality-node
    type: vless
    server: reality.example.com
    port: 443
    uuid: 11111111-1111-4111-8111-111111111112
    network: tcp
    tls: true
    reality-opts:
      public-key: AQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQE=
      short-id: abcd1234
  - name: vmess-node
    type: vmess
    server: vmess.example.com
    port: 8443
    uuid: 11111111-1111-4111-8111-111111111113
    cipher: auto
    alterId: 0
    tls: true
    network: ws
    ws-opts:
      path: /vmess
  - name: trojan-node
    type: trojan
    server: trojan.example.com
    port: 443
    password: trojan-secret
    sni: trojan.example.com
    skip-cert-verify: true
  - name: ss-node
    type: ss
    server: ss.example.com
    port: 8388
    cipher: aes-256-gcm
    password: ss-pass
  - name: ss-plugin
    type: ss
    server: plugin.example.com
    port: 8388
    cipher: aes-256-gcm
    password: ss-pass
    plugin: obfs-local
  - name: hy2-node
    type: hysteria2
    server: hy.example.com
    ports: 443,8443
    password: hy-pass
    up: 50 Mbps
    down: 100 Mbps
    obfs: salamander
    obfs-password: obfs-pass
    skip-cert-verify: true
  - name: tuic-node
    type: tuic
    server: tuic.example.com
    port: 443
    token: 11111111-1111-4111-8111-111111111114:tuic-pass
    congestion_control: bbr
    tls: true
  - name: socks-node
    type: socks5
    server: socks.example.com
    port: 1080
    username: user
    password: sock-pass
  - name: snell-node
    type: snell
    server: snell.example.com
    port: 440
    psk: snell-psk
  - name: wg-node
    type: wireguard
    server: wg.example.com
    port: 51820
    private-key: YBnwt7W7hFXM4qNOM+Fsu6m9wZDEWVn0mN0aB0uBqHc=
    public-key: bm90LWEtcmVhbC1rZXktMDAwMDAwMDAwMDAwMDAwMDAwMDAwMDA=
    ip: 172.16.0.2/32
    allowed-ips: 0.0.0.0/0
    mtu: 1408
proxy-groups:
  - name: auto-best
    type: url-test
    proxies: [vless-node, vmess-node]
    url: http://www.gstatic.com/generate_204
    interval: 300
    tolerance: 50
  - name: main
    type: select
    proxies:
      - DIRECT
      - REJECT
      - auto-best
      - trojan-node
      - ghost-member
  - name: fallback-g
    type: fallback
    proxies: [ss-node, auto-best]
  - name: lb-g
    type: load-balance
    proxies: [tuic-node, socks-node]
  - name: relay-g
    type: relay
    proxies: [ss-node]
  - name: empty-g
    type: select
    proxies:
      - REJECT
rules:
  - DOMAIN-SUFFIX,google.com,main
  - DOMAIN,ads.example.com,REJECT
  - DOMAIN-KEYWORD,tracker,REJECT
  - DOMAIN-REGEX,^.*\.cdn\..*,auto-best
  - IP-CIDR,1.1.1.0/24,DIRECT
  - IP-CIDR6,2001:db8::/32,DIRECT,no-resolve
  - PROCESS-NAME,com.evil.app,REJECT
  - DST-PORT,443,auto-best
  - DST-PORT,8000-9000,auto-best
  - NETWORK,TCP,auto-best
  - GEOIP,CN,DIRECT
  - RULE-SET,apple,DIRECT
  - AND((DOMAIN,a.example),(IP-CIDR,1.2.3.4/32)),DIRECT
  - WEIRD-TYPE,value,main
  - DOMAIN,broken.example.com,missing-group
  - MATCH,main
rule-providers:
  apple:
    type: http
    behavior: classical
    url: https://example.com/apple.yaml
    path: ./apple.yaml
proxy-providers:
  my-provider:
    type: http
    url: https://example.com/provider.yaml
    path: ./provider.yaml
""".trimIndent()
