package xyz.azraellab.zapp.core

/**
 * Язык интерфейса. Объявление здесь, реализации -- в androidMain и desktopMain.
 *
 * Выбор пользователя переживает перезапуск, значение не секрет: на Android --
 * SharedPreferences, на десктопе -- файл в `~/.config/zapp/`.
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
)

/**
 * Страны СНГ по коду региона.
 *
 * Нужны отдельно от языков: в Молдове язык системы -- `ro` (румынский,
 * «молдавский» как отдельный код deprecated), и по одному языку её не увидеть.
 * Русский в этих странах ставится по региону, а не по `ro`.
 */
private val CIS_REGIONS = setOf(
    "RU", "BY", "UA", "KZ", "KG", "TJ", "TM", "UZ", "AZ", "AM", "GE", "MD",
)

/** Стандартные китайские коды: zh, а также варианты script/region. */
private val CHINESE_LANGS = setOf("zh", "yue", "cmn")

/** Второй компонент кода вида `zh-Hans` / `zh_CN`. */
private fun primaryCode(code: String?): String? =
    code?.trim()?.lowercase()?.substringBefore('-')?.substringBefore('_')
        ?.takeIf { it.isNotBlank() }

/**
 * Регион кода вида `ru-RU`, `zh-Hans-CN`, `ro_MD`.
 *
 * Второй компонент -- не всегда регион: в `zh-Hans-CN` это script. Поэтому берём
 * последний компонент и проверяем, что он похож на страну: две буквы, не script
 * (`Hans`, `Latn`, `Cyrl` -- по три и четыре).
 */
private fun regionCode(code: String?): String? {
    val parts = code?.trim()?.replace('_', '-')?.split('-')?.filter { it.isNotBlank() }
        ?: return null
    return parts.drop(1)
        .lastOrNull { it.length == 2 && it.all { c -> c.isLetter() } }
        ?.uppercase()
}

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
        // Регион проверяем после китайского: `zh-CN` -- это Китай, и он должен
        // остаться китайским, а не попасть под регионное правило.
        regionCode(systemCode) in CIS_REGIONS -> AppLang.RU
        primary in CIS_LANGS -> AppLang.RU
        else -> AppLang.EN
    }
}
