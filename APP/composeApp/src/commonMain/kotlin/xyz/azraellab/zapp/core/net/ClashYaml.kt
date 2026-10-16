package xyz.azraellab.zapp.core.net

/**
 * Мини-YAML-парсер в объёме, нужном Clash-конфигам.
 *
 * Полная библиотека здесь ни к чему: чужой конфиг -- это карты, блочные
 * списки, однострочные flow-значения и скаляры; якоря и многострочный
 * flow в clash-файлах не встречаются. Свой парсер, а не зависимость:
 * импорт -- не критичный путь, и незнакомая конструкция обязана давать
 * честный отказ, а не тянуть в приложение YAML-движок целиком.
 *
 * Скаляры остаются строками: числа и «да/нет» разбирает тот, кому они
 * нужны, -- так парсер не додумывает типы за конвертер.
 *
 * Пробелы кавычек и якоря не поддерживаются намеренно; табуляция в
 * отступе раскрывается в пробелы, потому что рукописные файлы ею грешат.
 */
object ClashYaml {

    /** Узел разобранного текста. */
    sealed interface Node {

        /** Карта `key: value`; порядок ключей сохраняется. */
        data class Obj(val fields: LinkedHashMap<String, Node>) : Node {
            operator fun get(key: String): Node? = fields[key]

            /** Скаляр ключа строкой; карты и списки -- null. */
            fun str(key: String): String? = (fields[key] as? Str)?.value

            fun int(key: String): Int? = str(key)?.trim()?.toIntOrNull()

            /** YAML-булево: `true`, `yes`, `on`, `1`. */
            fun bool(key: String): Boolean =
                str(key)?.trim()?.lowercase() in setOf("true", "yes", "on", "1")

            fun obj(key: String): Obj? = fields[key] as? Obj

            /** Элементы блочного списка ключа. */
            fun items(key: String): List<Node> = (fields[key] as? Arr)?.items ?: emptyList()

            fun objs(key: String): List<Obj> = items(key).filterIsInstance<Obj>()

            fun texts(key: String): List<String> = items(key).filterIsInstance<Str>().map { it.value }

            /** Список-или-одна-строка: alpn и allowed-ips встречаются оба. */
            fun textList(key: String): List<String> = when (val value = fields[key]) {
                is Str -> value.value.split(',').map { it.trim() }.filter { it.isNotEmpty() }
                is Arr -> value.items.filterIsInstance<Str>().map { it.value }
                else -> emptyList()
            }
        }

        /** Блочный список: элементы через `-`. */
        data class Arr(val items: List<Node>) : Node

        /** Скаляр: текст, с кавычками или без. */
        data class Str(val value: String) : Node
    }

    /** Текст как карта верхнего уровня; null -- не YAML или поломанная структура. */
    fun parse(text: String): Node.Obj? {
        val lines = scan(text)
        if (lines.isEmpty() || lines[0].seq) return null
        return Reader(lines).map(lines[0].indent)
    }

    /** Строка входа: отступ, текст без него и без комментариев. */
    private class Line(val indent: Int, val text: String) {
        val seq: Boolean get() = text == "-" || text.startsWith("- ")
    }

    /**
     * Проход по строкам строго по отступам.
     *
     * Любая структурная поломка (не ключ там, где ждут ключ) роняет весь
     * разбор в null: конфиг с неизвестной структурой честнее не импортировать,
     * чем импортировать наполовину.
     */
    private class Reader(private val lines: List<Line>) {
        private var i = 0

        /** Карта с отступом [indent]; [first] -- уже отрезанный текст первого ключа. */
        fun map(indent: Int, first: String? = null): Node.Obj? {
            val fields = LinkedHashMap<String, Node>()
            if (first != null && !entry(first, indent, fields)) return null
            while (i < lines.size && lines[i].indent == indent) {
                val line = lines[i]
                if (line.seq) return null
                // Сдвиг до разбора значения: блоки и списки ниже ключа
                // ждут указатель уже на следующей строке.
                i++
                if (!entry(line.text, indent, fields)) return null
            }
            return Node.Obj(fields)
        }

        /** Одна запись: значение -- скаляр, вложенный блок или список на своём отступе. */
        private fun entry(text: String, indent: Int, into: LinkedHashMap<String, Node>): Boolean {
            val colon = splitKey(text) ?: return false
            val key = text.substring(0, colon).trim().removeSurrounding("\"").removeSurrounding("'")
            val value = text.substring(colon + 1).trim()
            if (value.isNotEmpty()) {
                into[key] = if (value[0] == '|' || value[0] == '>') blockScalar(indent, value) else scalar(value)
                return true
            }
            when {
                i < lines.size && lines[i].indent > indent -> into[key] = block(lines[i].indent) ?: return false
                i < lines.size && lines[i].indent == indent && lines[i].seq -> into[key] = seq(indent) ?: return false
                else -> into[key] = Node.Str("")
            }
            return true
        }

        /** Последовательность с отступом [indent]; null -- вложенная карта поломана. */
        private fun seq(indent: Int): Node.Arr? {
            val items = ArrayList<Node>()
            while (i < lines.size && lines[i].indent == indent && lines[i].seq) {
                val text = lines[i].text
                val rest = text.trimStart('-', ' ')
                i++
                when {
                    rest.isEmpty() -> items += if (i < lines.size && lines[i].indent > indent) {
                        block(lines[i].indent) ?: return null
                    } else {
                        Node.Str("")
                    }
                    // Карта, чей первый ключ стоит прямо на строке даша:
                    // `proxies:` -> `- name: node` -> следующие ключи на +2.
                    splitKey(rest) != null -> items += map(indent + (text.length - rest.length), rest) ?: return null
                    else -> items += scalar(rest)
                }
            }
            return Node.Arr(items)
        }

        /** Вложенный блок: список или карта -- по первой строке. */
        private fun block(indent: Int): Node? =
            if (lines[i].seq) seq(indent) else map(indent)

        /** Блочное значение `|`/`>`: строки глубже ключа. */
        private fun blockScalar(indent: Int, indicator: String): Node.Str {
            val collected = ArrayList<String>()
            while (i < lines.size && lines[i].indent > indent) {
                collected += lines[i].text
                i++
            }
            return Node.Str(
                if (indicator[0] == '|') collected.joinToString("\n") else collected.joinToString(" ")
            )
        }
    }

    /** Строки входа: отступ (таб = 4 пробела), комментарии и служебные строки прочерка сняты. */
    private fun scan(text: String): List<Line> {
        val out = ArrayList<Line>()
        for (raw in text.removePrefix("\uFEFF").split('\n')) {
            val line = raw.removeSuffix("\r")
            var pos = 0
            var indent = 0
            while (pos < line.length && (line[pos] == ' ' || line[pos] == '\t')) {
                if (line[pos] == '\t') indent += 4 else indent++
                pos++
            }
            val body = stripComment(line.substring(pos)).trimEnd()
            // Начало/конец документа: файл у нас всегда один.
            if (body.isEmpty() || body == "---" || body == "...") continue
            out += Line(indent, body)
        }
        return out
    }

    /**
     * Текст без хвостового комментария.
     *
     * `#` начинает комментарий только после пробела (в URL он часть
     * фрагмента), а кавычки его прячут. Одинарная кавычка -- признак
     * строки только в начале значения, иначе апостроф в `it's` ломал бы
     * обычные имена.
     */
    private fun stripComment(text: String): String {
        var double = false
        var single = false
        var i = 0
        while (i < text.length) {
            val c = text[i]
            when {
                double && c == '\\' && i + 1 < text.length -> {
                    i += 2
                    continue
                }
                double -> if (c == '"') double = false
                single && c == '\'' && text.getOrNull(i + 1) == '\'' -> {
                    i += 2
                    continue
                }
                single -> if (c == '\'') single = false
                c == '"' -> double = true
                c == '\'' && (i == 0 || text[i - 1] in " \t:-[{,") -> single = true
                c == '#' && (i == 0 || text[i - 1].isWhitespace()) -> return text.substring(0, i)
                else -> Unit
            }
            i++
        }
        return text
    }

    /** Позиция `:` после ключа: за ним пробел или конец строки, кавычки не считаются. */
    private fun splitKey(text: String): Int? {
        var double = false
        var single = false
        var i = 0
        while (i < text.length) {
            val c = text[i]
            when {
                double && c == '\\' && i + 1 < text.length -> {
                    i += 2
                    continue
                }
                double -> if (c == '"') double = false
                single && c == '\'' && text.getOrNull(i + 1) == '\'' -> {
                    i += 2
                    continue
                }
                single -> if (c == '\'') single = false
                c == '"' -> double = true
                c == '\'' && (i == 0 || text[i - 1] in " \t:-[{,") -> single = true
                c == ':' && i > 0 && (i + 1 == text.length || text[i + 1] == ' ') -> return i
                else -> Unit
            }
            i++
        }
        return null
    }

    /** Скаляр: flow-значение, кавычки или голый текст. */
    private fun scalar(text: String): Node {
        val t = text.trim()
        if (t.startsWith("[")) flow(t)?.let { return it }
        if (t.startsWith("{")) flow(t)?.let { return it }
        if (t.length >= 2 && t.startsWith("\"") && t.endsWith("\"")) {
            return Node.Str(t.substring(1, t.length - 1))
        }
        if (t.length >= 2 && t.startsWith("'") && t.endsWith("'")) {
            return Node.Str(t.substring(1, t.length - 1).replace("''", "'"))
        }
        return Node.Str(t)
    }

    // --- однострочный flow: [a, "b"] и {k: v} ---

    private fun flow(text: String): Node? {
        val state = intArrayOf(0)
        val node = flowNode(text, state) ?: return null
        skipSpace(text, state)
        return node.takeIf { state[0] >= text.length }
    }

    private fun flowNode(text: String, state: IntArray): Node? {
        skipSpace(text, state)
        if (state[0] >= text.length) return null
        return when (text[state[0]]) {
            '[' -> {
                state[0]++
                flowList(text, state)
            }
            '{' -> {
                state[0]++
                flowMap(text, state)
            }
            '"', '\'' -> flowQuoted(text, state)?.let { Node.Str(it) }
            else -> flowBare(text, state)?.let { Node.Str(it) }
        }
    }

    private fun flowList(text: String, state: IntArray): Node? {
        val items = ArrayList<Node>()
        skipSpace(text, state)
        if (text.getOrNull(state[0]) == ']') {
            state[0]++
            return Node.Arr(items)
        }
        while (true) {
            items += flowNode(text, state) ?: return null
            skipSpace(text, state)
            when (text.getOrNull(state[0])) {
                ',' -> state[0]++
                ']' -> {
                    state[0]++
                    return Node.Arr(items)
                }
                else -> return null
            }
        }
    }

    private fun flowMap(text: String, state: IntArray): Node? {
        val fields = LinkedHashMap<String, Node>()
        skipSpace(text, state)
        if (text.getOrNull(state[0]) == '}') {
            state[0]++
            return Node.Obj(fields)
        }
        while (true) {
            skipSpace(text, state)
            val key = when (text.getOrNull(state[0])) {
                '"', '\'' -> flowQuoted(text, state)
                else -> flowBare(text, state, stopAtColon = true)
            } ?: return null
            skipSpace(text, state)
            if (text.getOrNull(state[0]) != ':') return null
            state[0]++
            fields[key] = flowNode(text, state) ?: return null
            skipSpace(text, state)
            when (text.getOrNull(state[0])) {
                ',' -> state[0]++
                '}' -> {
                    state[0]++
                    return Node.Obj(fields)
                }
                else -> return null
            }
        }
    }

    private fun flowQuoted(text: String, state: IntArray): String? {
        val quote = text[state[0]]
        state[0]++
        val out = StringBuilder()
        while (state[0] < text.length) {
            val c = text[state[0]]
            if (c == '\\' && quote == '"' && state[0] + 1 < text.length) {
                out.append(text[state[0] + 1])
                state[0] += 2
                continue
            }
            if (c == quote) {
                if (quote == '\'' && text.getOrNull(state[0] + 1) == '\'') {
                    out.append('\'')
                    state[0] += 2
                    continue
                }
                state[0]++
                return out.toString()
            }
            out.append(c)
            state[0]++
        }
        return null
    }

    /** Голый текст до разделителя; в значениях двоеточие -- часть адреса. */
    private fun flowBare(text: String, state: IntArray, stopAtColon: Boolean = false): String? {
        val start = state[0]
        while (state[0] < text.length) {
            val c = text[state[0]]
            if (c == ',' || c == ']' || c == '}' || (stopAtColon && c == ':')) break
            state[0]++
        }
        return text.substring(start, state[0]).trim().ifEmpty { null }
    }

    private fun skipSpace(text: String, state: IntArray) {
        while (state[0] < text.length && (text[state[0]] == ' ' || text[state[0]] == '\t')) state[0]++
    }
}
