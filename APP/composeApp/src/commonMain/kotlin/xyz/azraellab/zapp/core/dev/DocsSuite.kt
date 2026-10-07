package xyz.azraellab.zapp.core.dev

import xyz.azraellab.zapp.core.AppLang
import xyz.azraellab.zapp.core.Docs

/**
 * Справка: каждый раздел и каждый пункт на всех трёх языках.
 *
 * Тексты справки живут в [Docs], а не в [Str], поэтому общий i18n-тест их
 * не покрывает: здесь проверяется то же самое (заполнено, переведено,
 * без кириллицы в английском) -- отдельно для заголовков, отдельно для
 * тел пунктов.
 */
private val CYRILLIC = Regex("[\u0400-\u04FF]")

/** Заголовки: имена собственные (VPN, Zapret) не переводятся, но должны быть. */
private fun checkTitleLocalized(en: String, ru: String, zh: String, what: String) {
    for ((lang, text) in listOf("EN" to en, "RU" to ru, "ZH" to zh)) {
        checkTrue(text.isNotBlank(), "$what: $lang пуст")
        checkEq(text, text.trim(), "$what: $lang пробелы по краям")
        checkNotContains(text, "TODO", "$what: $lang незакрытая заметка")
    }
    checkFalse(CYRILLIC.containsMatchIn(en), "$what: EN содержит кириллицу: $en")
}

/**
 * Тела пунктов: это проза, она обязана быть переведённой -- англабзац без
 * кириллицы в RU и без иероглифов в ZH означает забытый перевод, а не имя
 * собственное.
 */
private fun checkBodyLocalized(en: String, ru: String, zh: String, what: String) {
    checkTitleLocalized(en, ru, zh, what)
    checkTrue(ru.any { it in 'а'..'я' || it in 'А'..'Я' }, "$what: RU без кириллицы: $ru")
    checkTrue(zh.any { it.code in 0x4E00..0x9FFF }, "$what: ZH без иероглифов: $zh")
}

internal fun docsTests(out: MutableList<DevTest>) {
    Docs.sections.forEachIndexed { si, section ->
        out += DevTest(
            id = "docs/section-$si-title",
            category = DevCategory.DOCS,
            name = "Раздел справки «${section.title.en}»: заголовок на всех языках"
        ) {
            checkTitleLocalized(section.title.en, section.title.ru, section.title.zh, "раздел $si")
        }

        section.items.forEachIndexed { ii, item ->
            out += DevTest(
                id = "docs/section-$si-item-$ii-title",
                category = DevCategory.DOCS,
                name = "Пункт «${item.title.en}»: заголовок на всех языках"
            ) {
                checkTitleLocalized(item.title.en, item.title.ru, item.title.zh, "пункт $si/$ii title")
            }
            out += DevTest(
                id = "docs/section-$si-item-$ii-body",
                category = DevCategory.DOCS,
                name = "Пункт «${item.title.en}»: текст на всех языках"
            ) {
                checkBodyLocalized(item.body.en, item.body.ru, item.body.zh, "пункт $si/$ii body")
            }
        }
    }

    // Выбор языка -- та же тройка, что и у строк интерфейса.
    out += DevTest(
        id = "docs/pick-lang",
        category = DevCategory.DOCS,
        name = "Docs.L.pick отдаёт язык по коду"
    ) {
        val l = Docs.L("en text", "ru текст", "zh 文本")
        checkEq("en text", l.pick(AppLang.EN))
        checkEq("ru текст", l.pick(AppLang.RU))
        checkEq("zh 文本", l.pick(AppLang.ZH))
    }

    out += DevTest(
        id = "docs/sections-nonempty",
        category = DevCategory.DOCS,
        name = "В справке есть разделы и пункты"
    ) {
        checkTrue(Docs.sections.isNotEmpty(), "справка пуста")
        Docs.sections.forEachIndexed { i, s ->
            checkTrue(s.items.isNotEmpty(), "раздел $i без пунктов")
            s.items.forEachIndexed { j, item ->
                checkTrue(item.title.en.isNotBlank(), "пункт $i/$j без заголовка")
                checkTrue(item.body.en.isNotBlank(), "пункт $i/$j без текста")
            }
        }
    }
}
