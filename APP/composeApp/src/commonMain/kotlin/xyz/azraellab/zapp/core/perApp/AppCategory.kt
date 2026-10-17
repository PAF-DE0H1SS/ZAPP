package xyz.azraellab.zapp.core.perApp

import xyz.azraellab.zapp.core.Str

/**
 * Категория приложения для группировки в пикере.
 *
 * Порядок объявления -- порядок показа: [OTHER] замыкает список.
 * Основа -- категория платформы (ApplicationInfo.category): она
 * бесплатная, честная и не требует хранить списки пакетов. Androidу
 * не хватает только мессенджеров и банков -- их он относит к прочим,
 * и [refineCategory] добивает двумя короткими списками префиксов.
 */
enum class AppCategory(val str: Str) {
    /** Соцсети: платформа размечает их сама. */
    SOCIAL(Str.CAT_SOCIAL),

    /** Мессенджеры: уточнение по префиксам пакетов. */
    MESSENGER(Str.CAT_MESSENGER),

    /** Банки и платежи: уточнение по префиксам пакетов. */
    FINANCE(Str.CAT_FINANCE),

    /** Игры: CATEGORY_GAME у платформы. */
    GAME(Str.CAT_GAME),

    /** Музыка, видео, фото: три мелкие категории платформы в одну. */
    MEDIA(Str.CAT_MEDIA),

    /** Новости: CATEGORY_NEWS у платформы. */
    NEWS(Str.CAT_NEWS),

    /** Всё остальное, включая то, что платформа не разметила. */
    OTHER(Str.CAT_OTHER)
}

/**
 * Префиксы пакетов-мессенджеров.
 *
 * Список закрытый и маленький сознательно: это best-effort для того,
 * что платформа не умеет, а не попытка обойти karing-мэппинги на
 * тысячи пакетов. Ошибка отнесения ничего не ломает -- категория
 * управляет только заголовком в списке.
 */
private val MESSENGER_PREFIXES = listOf(
    "org.telegram",
    "com.whatsapp",
    "org.thoughtcrime",
    "com.viber",
    "com.discord",
    "jp.naver.line",
    "com.skype",
    "com.facebook.orca"
)

/** Префиксы пакетов-банков. Та же философия, что у [MESSENGER_PREFIXES]. */
private val FINANCE_PREFIXES = listOf(
    "ru.sberbank",
    "ru.tinkoff",
    "com.idamob.tinkoff",
    "ru.vtb",
    "com.vtb",
    "ru.alfabank",
    "com.alfabank",
    "com.privatbank",
    "ru.raiffeisen",
    "com.revolut",
    "com.paypal"
)

/**
 * Категория приложения: база от платформы плюс свои уточнения.
 *
 * Свои списки работают только когда платформа ничего не сказала
 * ([AppCategory.OTHER]): размеченному приложению мы не указываем.
 */
fun refineCategory(packageName: String, base: AppCategory): AppCategory {
    if (base != AppCategory.OTHER) return base
    val p = packageName.lowercase()
    return when {
        MESSENGER_PREFIXES.any { p.startsWith(it) } -> AppCategory.MESSENGER
        FINANCE_PREFIXES.any { p.startsWith(it) } -> AppCategory.FINANCE
        else -> AppCategory.OTHER
    }
}

/**
 * Каталог по категориям: только непустые группы, [OTHER] замыкает.
 *
 * Однородный список (все «прочие» -- десктоп с деревом процессов или
 * каталог, который платформа не разметила) отдаётся одной группой:
 * вызывающая сторона по размеру видит, что группировать нечего, и
 * оставляет плоский список.
 */
fun groupByCategory(apps: List<InstalledApp>): List<Pair<AppCategory, List<InstalledApp>>> {
    val groups = apps.groupBy { it.category }
    return AppCategory.entries.mapNotNull { cat -> groups[cat]?.let { cat to it } }
}

/**
 * Галка категории в списке выбора: все отмечены -- снимаем всех,
 * иначе добавляем недостающих.
 *
 * «Недостающие», а не «все»: серия быстрых тапов по разным
 * категориям не должна откатывать предыдущие.
 */
fun toggleCategory(packages: List<String>, group: List<String>): List<String> =
    if (group.isNotEmpty() && group.all { it in packages }) {
        packages - group.toSet()
    } else {
        packages + group.filter { it !in packages }
    }
