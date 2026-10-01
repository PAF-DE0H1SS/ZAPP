package xyz.azraellab.zapp.core

/**
 * Язык интерфейса. Объявление здесь, реализации -- в androidMain и desktopMain.
 *
 * Лежит вплотную к [AppThemeStore] и работает так же: выбор пользователя переживает
 * перезапуск, значение не секрет, на Android -- SharedPreferences, на десктопе --
 * файл рядом с темой.
 *
 * Язык приложения по умолчанию берётся из языка системы, но не напрямую: система
 * может отдать `ru-RU`, `be-BY` или `zh-Hans-CN`, и каждому варианту соответствует
 * разный ответ. Разбор сводим в [resolveLang], а не разбрасываем по платформам.
 */
expect object AppLangStore {
    fun read(): String?
    fun write(code: String): Boolean

    /** Код языка системы: `xx` или `xx-YY`, регистр не важен. */
    fun systemCode(): String?
}

/** Языки интерфейса. */
enum class AppLang(val code: String) {
    EN("en"),
    RU("ru"),
    ZH("zh");

    companion object {
        /** Язык по коду вида `ru`, `ru-RU`, `zh_CN`. `null`, если такого языка нет. */
        fun of(code: String?): AppLang? {
            val primary = primaryCode(code) ?: return null
            return entries.firstOrNull { it.code == primary }
        }
    }
}

/**
 * Языки СНГ, на которых нужен русский интерфейс.
 *
 * Не «все языки, кроме китайского и английского»: украинский и белорусский
 * пользователь русский интерфейс понимает и оценит, а вот, скажем, немецкий или
 * турецкий -- нет, и по умолчанию должен быть английский.
 */
private val CIS_LANGS = setOf(
    "ru", // Россия, а также русский в других странах
    "be", // Беларусь
    "uk", // Украина
    "kk", // Казахстан
    "ky", // Кыргызстан
    "tg", // Таджикистан
    "tk", // Туркменистан
    "uz", // Узбекистан
    "az", // Азербайджан
    "hy", // Армения
    "ka", // Грузия
    "mo", // Молдова (молдавский -- румынский, но в интерфейсе СНГ нужен русский)
)

/** Стандартные китайские коды: zh, а также варианты script/region. */
private val CHINESE_LANGS = setOf("zh", "yue", "cmn")

/** Второй компонент кода вида `zh-Hans` / `zh_CN`. */
private fun primaryCode(code: String?): String? =
    code?.trim()?.lowercase()?.substringBefore('-')?.substringBefore('_')
        ?.takeIf { it.isNotBlank() }

/**
 * Какой язык показывать: явный выбор пользователя, иначе язык системы по правилам
 * выше, иначе английский.
 *
 * Отдельная функция, а не логика в композабле, -- иначе правило «СНГ -> русский»
 * пришлось бы проверять через UI.
 */
fun resolveLang(chosen: AppLang?, systemCode: String?): AppLang {
    if (chosen != null) return chosen
    val primary = primaryCode(systemCode) ?: return AppLang.EN
    return when {
        primary in CHINESE_LANGS -> AppLang.ZH
        primary in CIS_LANGS -> AppLang.RU
        else -> AppLang.EN
    }
}
