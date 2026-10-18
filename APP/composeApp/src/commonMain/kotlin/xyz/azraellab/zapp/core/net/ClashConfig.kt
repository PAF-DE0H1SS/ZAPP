package xyz.azraellab.zapp.core.net

/**
 * Разобранный Clash-конфиг.
 *
 * Разбор отделён от сборки sing-box-конфига: импортеру нужно только
 * узнать, что текст -- clash-конфиг и что в нём есть хоть один узел, а
 * конвертер в [SingboxConfig] работает с уже разобранной структурой.
 * Поля узла остаются сырыми картами: типов прокси больше, чем полей,
 * которые реально используются.
 *
 * Провайдеры: proxy-providers считаются, но не качаются (импорт -- без
 * сети). Rule-providers уходят дальше в [SingboxConfig] как remote
 * rule_set: их URL кладётся в конфиг, и ядро sing-box скачает файлы
 * само при старте туннеля.
 */
data class ClashConfig(
    /** Узлы: имя, тип, поля и адрес для проверки живости. */
    val proxies: List<ClashProxy>,
    /** Группы выбора: select, url-test, fallback... */
    val groups: List<ClashGroup>,
    /** Строки секции `rules`: `DOMAIN-SUFFIX,example.com,Proxy`. */
    val rules: List<String>,
    /** Имя файла из верхнеуровневого `name:`, если оно есть. */
    val name: String = "",
    /** Сколько провайдеров описано: конвертер их не использует, только заметка. */
    val providers: Int = 0,
    /** Провайдеры правил из секции `rule-providers`: имя -> URL/формат. */
    val ruleProviders: List<ClashRuleProvider> = emptyList()
) {

    companion object {
        /**
         * Ключи верхнего уровня, которых нет ни у одного другого формата.
         *
         * Маска обязана стоять раньше детекта OpenVPN: regex там матчит
         * `client-fingerprint:` из узлов этого конфига.
         */
        private val MASK = Regex("(?m)^(proxies|proxy-groups|proxy-providers)\\s*:")

        /** Текст -- clash-конфиг с узлами; null -- не тот формат или узлов нет. */
        fun parse(text: String): ClashConfig? {
            val trimmed = text.trim()
            // Скобка в начале -- формат JSON (sing-box/xray), не YAML.
            if (trimmed.startsWith("{")) return null
            if (!MASK.containsMatchIn(trimmed)) return null
            return from(ClashYaml.parse(trimmed))
        }

        /** Готовый разбор верхнего уровня; null -- структура чужая или узлов нет. */
        fun from(root: ClashYaml.Node.Obj?): ClashConfig? {
            root ?: return null
            val proxies = root.objs("proxies").mapNotNull { fields ->
                val name = fields.str("name")?.trim().orEmpty()
                val type = fields.str("type")?.trim().orEmpty()
                if (name.isEmpty() || type.isEmpty()) return@mapNotNull null
                ClashProxy(
                    name = name,
                    type = type,
                    fields = fields,
                    server = fields.str("server")?.trim().orEmpty(),
                    port = fields.int("port")
                        ?: fields.textList("ports").firstOrNull()?.toIntOrNull()
                        ?: 0
                )
            }
            if (proxies.isEmpty()) return null
            val groups = root.objs("proxy-groups").mapNotNull { fields ->
                val name = fields.str("name")?.trim().orEmpty()
                val type = fields.str("type")?.trim().orEmpty()
                if (name.isEmpty() || type.isEmpty()) {
                    null
                } else {
                    ClashGroup(name, type, fields.texts("proxies"), fields)
                }
            }
            return ClashConfig(
                proxies = proxies,
                groups = groups,
                rules = root.texts("rules"),
                name = root.str("name")?.trim().orEmpty(),
                providers = (root.obj("proxy-providers")?.fields?.size ?: 0) +
                    (root.obj("rule-providers")?.fields?.size ?: 0),
                ruleProviders = (root.obj("rule-providers")?.fields ?: LinkedHashMap())
                    .mapNotNull { (name, node) ->
                        val fields = node as? ClashYaml.Node.Obj ?: return@mapNotNull null
                        val clean = name.trim()
                        if (clean.isEmpty()) return@mapNotNull null
                        ClashRuleProvider(
                            name = clean,
                            url = fields.str("url")?.trim().orEmpty(),
                            format = fields.str("format")?.trim().orEmpty().lowercase()
                                .ifBlank { "yaml" },
                            interval = fields.str("interval")?.trim().orEmpty()
                        )
                    }
            )
        }
    }
}

/** Один узел clash: [server]/[port] вычислены сразу -- из них берётся шлюз проверки живости. */
data class ClashProxy(
    val name: String,
    val type: String,
    val fields: ClashYaml.Node.Obj,
    val server: String = "",
    val port: Int = 0
)

/** Группа выбора: участники -- имена узлов, групп и встроенные DIRECT/REJECT/PASS. */
data class ClashGroup(
    val name: String,
    val type: String,
    val members: List<String>,
    val fields: ClashYaml.Node.Obj
)

/**
 * Провайдер правил: источник файла для sing-box rule_set.
 *
 * behavior не выносится: yaml-источник sing-box разбирает сам, а формат
 * `mrs` (mihomo) поддержан ядром напрямую.
 */
data class ClashRuleProvider(
    val name: String,
    /** Пустой URL -- провайдер файловый, конвертер его пропустит с заметкой. */
    val url: String = "",
    /** yaml | mrs; прочие значения читаются как yaml. */
    val format: String = "yaml",
    /** Интервал обновления clash-вида (`1h`); пусто -- без автообновления. */
    val interval: String = ""
)
