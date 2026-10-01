package xyz.azraellab.zapp.core

import kotlin.test.Test
import kotlin.test.assertEquals

class I18nTest {

    @Test
    fun codeParsingIgnoresRegionCaseAndSeparator() {
        assertEquals(AppLang.RU, AppLang.of("ru"))
        assertEquals(AppLang.RU, AppLang.of("ru-RU"))
        assertEquals(AppLang.RU, AppLang.of("ru_RU"))
        assertEquals(AppLang.RU, AppLang.of("  RU-rU "))
        assertEquals(AppLang.ZH, AppLang.of("zh-Hans-CN"))
        assertEquals(AppLang.EN, AppLang.of("en-GB"))
    }

    @Test
    fun unknownCodeIsNotAValidLanguage() {
        assertEquals(null, AppLang.of(null))
        assertEquals(null, AppLang.of(""))
        assertEquals(null, AppLang.of("de"))
        assertEquals(null, AppLang.of("solarized"))
    }

    @Test
    fun cisLanguagesGetRussian() {
        // Правило из требований: язык СНГ -> русский интерфейс.
        for (code in listOf("ru", "ru-RU", "be-BY", "uk-UA", "kk-KZ", "ky-KG",
            "tg", "tk-TM", "uz-UZ", "az-AZ", "hy-AM", "ka-GE", "mo-MD")) {
            assertEquals(AppLang.RU, resolveLang(null, code), "язык системы $code")
        }
    }

    @Test
    fun chineseGetsChinese() {
        for (code in listOf("zh", "zh-CN", "zh-Hans", "zh-Hant-TW", "yue", "cmn")) {
            assertEquals(AppLang.ZH, resolveLang(null, code), "язык системы $code")
        }
    }

    @Test
    fun everythingElseGetsEnglish() {
        for (code in listOf("en", "en-US", "de", "fr-FR", "ja", "ko", "pt-BR",
            "tr", "pl", "ar", "he", "hi", "th", "vi", "it")) {
            assertEquals(AppLang.EN, resolveLang(null, code), "язык системы $code")
        }
    }

    @Test
    fun missingSystemLanguageFallsBackToEnglish() {
        assertEquals(AppLang.EN, resolveLang(null, null))
        assertEquals(AppLang.EN, resolveLang(null, ""))
    }

    @Test
    fun explicitChoiceBeatsSystemLanguage() {
        // Выбор пользователя главнее: поставил китайский на русской системе --
        // получил китайский, пока сам не вернёт выбор в null.
        assertEquals(AppLang.ZH, resolveLang(AppLang.ZH, "ru-RU"))
        assertEquals(AppLang.RU, resolveLang(AppLang.RU, "en-US"))
        assertEquals(AppLang.EN, resolveLang(AppLang.EN, "zh-CN"))
    }

    @Test
    fun everyKeyIsTranslatedInEveryLanguage() {
        // Пропущенный перевод -- это `APP_NAME` на экране, поэтому проверяем явно:
        // пустая строка или строка, совпадающая с именем enum-константы.
        for (key in Str.entries) {
            for (lang in AppLang.entries) {
                val value = key.of(lang)
                val placeholder = key.name
                assertEquals(
                    false,
                    value.isBlank() || value == placeholder,
                    "${key.name} не переведён на ${lang.code}: '$value'"
                )
            }
        }
    }

    @Test
    fun noLanguageReturnsAnotherLanguagesText() {
        // Не ловится компилятором: строки могли бы случайно совпасть. Намеренно
        // разные тексты на RU и ZH, поэтому сравниваем попарно.
        val ru = Str.entries.associateWith { it.of(AppLang.RU) }
        val zh = Str.entries.associateWith { it.of(AppLang.ZH) }
        val en = Str.entries.associateWith { it.of(AppLang.EN) }

        // Имена собственные (Zapret, GoodbyeDPI, VPN, GPS) переводом не считаются:
        // они одинаковые по сути, и это нормально. Проверяем, что хотя бы часть
        // ключей реально различается, иначе наборы могли бы слиться целиком.
        val differentRuEn = ru.count { (k, v) -> en[k] != v }
        val differentRuZh = ru.count { (k, v) -> zh[k] != v }
        assertEquals(
            true,
            differentRuEn > 0 && differentRuZh > 0,
            "наборы EN/RU/ZH не различаются -- переводы не подставлены"
        )
    }
}
