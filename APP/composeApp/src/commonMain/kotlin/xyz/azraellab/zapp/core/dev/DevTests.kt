package xyz.azraellab.zapp.core.dev

import xyz.azraellab.zapp.core.Str

/**
 * Категории тестов.
 *
 * Отдельные наборы, а не один список на 700 строк: экран группирует запуск
 * по категориям, и после провала видно, в какой области приложения сломалось
 * -- «i18n» и «Zapret» требуют совершенно разных действий для отладки.
 *
 * Заголовок -- готовый ключ [Str], поэтому категории переводятся тем же
 * механизмом, что и весь остальной интерфейс.
 */
enum class DevCategory(val title: Str) {
    /** Строки интерфейса: каждый ключ на каждом языке. */
    I18N(Str.SETTINGS_LANGUAGE),

    /** Палитра, контраст, цвета компонентов. */
    THEME(Str.DEV_CAT_THEME),

    /** Конфигурация: кодек, пресеты, дефолты, санитайзеры. */
    CONFIG(Str.DEV_CAT_CONFIG),

    /** Разбор и сборка форматов: ссылки, ini, base64, маршруты. */
    PARSERS(Str.DEV_CAT_PARSERS),

    /** VPN: профили, протоколы, список коннектов, выбор моста. */
    VPN(Str.TAB_VPN),

    /** Zapret: стратегии, режимы, командная строка nfqws. */
    ZAPRET(Str.TAB_ZAPRET),

    /** GoodbyeDPI: режимы и командная строка. */
    DPI(Str.TAB_GOODBYE_DPI),

    /** Трафик: форматирование, метры, история. */
    TRAFFIC(Str.TAB_TRAFFIC),

    /** GPS: режимы, маршрут, точка назначения. */
    GPS(Str.TAB_GPS),

    /** Обновления: сравнение версий. */
    UPDATE(Str.SETTINGS_UPDATES),

    /** Справка: заголовки и тексты на всех языках. */
    DOCS(Str.SETTINGS_DOCS),

    /** Интерфейс: вкладки, разрешения, язык, подсказки. */
    UI(Str.DEV_CAT_UI)
}

/**
 * Один тест: идентификатор, категория и тело.
 *
 * Тело -- замыкание, которое бросает [AssertionError] при провале. Никаких
 * аннотаций и рефлексии: один и тот же список гоняется внутри приложения
 * (экран «Тесты») и в CI (обёртка JUnit в `desktopTest`), и в обоих местах
 * результат -- один и тот же код.
 *
 * [id] стабильный (`i18n/tab_tor`) -- по нему тест находится в логе, он же
 * имя кейса в отчёте JUnit. [name] -- человекочитаемое описание для UI.
 */
data class DevTest(
    val id: String,
    val category: DevCategory,
    val name: String,
    val body: () -> Unit
) {
    override fun toString(): String = id
}

/**
 * Реестр всех тестов.
 *
 * Единственный источник правды: и экран, и JUnit-обёртка читают [all],
 * поэтому «зелёные на телефоне» и «зелёные в CI» означают одно и то же.
 */
object DevTestRegistry {

    private val registered: List<DevTest> by lazy {
        buildList {
            i18nTests(this)
            docsTests(this)
            themeTests(this)
            skyTests(this)
            configTests(this)
            regionTests(this)
            conflictTests(this)
            parserTests(this)
            vpnTests(this)
            zapretTests(this)
            dpiTests(this)
            trafficTests(this)
            gpsTests(this)
            updateTests(this)
            uiTests(this)
            errorTextTests(this)
        }
    }

    val all: List<DevTest> get() = registered

    private val byCategory: Map<DevCategory, List<DevTest>> by lazy {
        registered.groupBy { it.category }
    }

    /** Тесты выбранных категорий; все категории -- весь список без фильтрации. */
    fun inCategories(categories: Set<DevCategory>): List<DevTest> =
        if (categories.size == DevCategory.entries.size) registered
        else registered.filter { it.category in categories }

    fun countIn(category: DevCategory): Int = byCategory[category]?.size ?: 0

    fun counts(): Map<DevCategory, Int> = DevCategory.entries.associateWith { countIn(it) }
}

// --- Утверждения ---

/** Провал -- [AssertionError] с текстом: её ловит и раннер, и JUnit. */
fun checkTrue(condition: Boolean, message: String = "expected true") {
    if (!condition) throw AssertionError(message)
}

fun checkFalse(condition: Boolean, message: String = "expected false") {
    if (condition) throw AssertionError(message)
}

/** Равенство с указанием, что именно сравнивалось. */
fun checkEq(expected: Any?, actual: Any?, what: String = "") {
    if (expected != actual) {
        val prefix = if (what.isEmpty()) "" else "$what: "
        throw AssertionError("$prefix expected <$expected>, actual <$actual>")
    }
}

/** Подстрока в тексте; в логе видно, какая часть не сошлась. */
fun checkContains(haystack: String, needle: String, what: String = "") {
    if (!haystack.contains(needle)) {
        val prefix = if (what.isEmpty()) "" else "$what: "
        throw AssertionError("$prefix <$needle> not found in <$haystack>")
    }
}

fun checkNotContains(haystack: String, needle: String, what: String = "") {
    if (haystack.contains(needle)) {
        val prefix = if (what.isEmpty()) "" else "$what: "
        throw AssertionError("$prefix unexpected <$needle> in <$haystack>")
    }
}

/** Список содержит элемент. */
fun checkIn(element: Any?, collection: Collection<*>, what: String = "") {
    if (element !in collection) {
        val prefix = if (what.isEmpty()) "" else "$what: "
        throw AssertionError("$prefix <$element> not in $collection")
    }
}
