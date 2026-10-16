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
 * Провайдеры (proxy-providers и rule-providers) считаются, но не качаются:
 * импорт -- без сети, чужие файлы по ссылкам не тянем.
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
    val providers: Int = 0
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
                    (root.obj("rule-providers")?.fields?.size ?: 0)
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
