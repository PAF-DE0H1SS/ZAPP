package xyz.azraellab.zapp.core.dev

import xyz.azraellab.zapp.core.AppLang
import xyz.azraellab.zapp.core.Str

/**
 * Тест «одна строка -- три языка» на каждый ключ [Str].
 *
 * Один тест на ключ, а не один тест на весь enum: после провала в логе
 * видно сразу `i18n/vpn_archive_empty`, а не «i18n упал где-то». Проверки
 * -- то, что реально ломается при переводе: пустой текст, забытая
 * кириллица в английском, пробелы по краям, плейсхолдер, разъехавшийся
 * между языками.
 */
private val CYRILLIC = Regex("[\u0400-\u04FF]")

internal fun i18nTests(out: MutableList<DevTest>) {
    Str.entries.forEach { key ->
        out += DevTest(
            id = "i18n/${key.name.lowercase()}",
            category = DevCategory.I18N,
            name = "Строка ${key.name}: EN/RU/ZH заполнены и чисты"
        ) {
            val en = key.of(AppLang.EN)
            val ru = key.of(AppLang.RU)
            val zh = key.of(AppLang.ZH)

            for ((lang, text) in listOf("EN" to en, "RU" to ru, "ZH" to zh)) {
                checkTrue(text.isNotBlank(), "$lang: пустой перевод")
                checkEq(text, text.trim(), "$lang: пробелы по краям")
                checkNotContains(text, "\t", "$lang: табуляция в тексте")
                checkNotContains(text, "\r", "$lang: возврат каретки в тексте")
                checkNotContains(text, "TODO", "$lang: незакрытая заметка")
                checkNotContains(text, "FIXME", "$lang: незакрытая заметка")
                checkNotContains(text, key.name, "$lang: вместо перевода имя ключа")
            }

            checkFalse(CYRILLIC.containsMatchIn(en), "EN содержит кириллицу: $en")

            // Плейсхолдеры -- те же %d/%s на всех языках: форматирование
            // падает на устройстве, если переводчик добавил или потерял знак.
            val marks = listOf(en, ru, zh).map { text -> text.count { it == '%' } }
            checkEq(marks[0], marks[1], "EN vs RU: % в <$en> / <$ru>")
            checkEq(marks[1], marks[2], "RU vs ZH: % в <$ru> / <$zh>")
        }
    }
}
