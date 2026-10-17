package xyz.azraellab.zapp.core.perApp

import kotlinx.serialization.Serializable

/**
 * Как выбранные пакеты участвуют в маршрутизации.
 *
 * Два режима, которые реально различаются для трафика: [INCLUDE] -- через
 * туннель/очередь идут только выбранные (остальные мимо), [EXCLUDE] --
 * все, кроме выбранных. Третий случай «ничего не выбрано» -- это
 * выключенный тумблер [AppSelection.enabled], а не режим: пустой
 * include-список эквивалентен ему, пустой exclude тоже.
 */
@Serializable
enum class RouteMode {
    /** Только выбранные приложения идут через функцию. */
    INCLUDE,

    /** Все приложения, кроме выбранных. */
    EXCLUDE
}

/**
 * Выбор приложений для одной функции обхода.
 *
 * Хранятся именно пакеты (`:package:`), а не uid: пакет стабилен между
 * переустановками, на устройстве он разрешается в конкретный uid через
 * [installedApps]. Разрешение -- обязанность вызывающей стороны (AppState),
 * движки и DpiFirewall получают уже готовые списки.
 *
 * Пустой список при включённом выборе -- корректное состояние: «функция
 * активна, но приложений пока не выбрано». Что это означает для трафика,
 * решает потребитель: для VpnService пустой allowed-list эквивалентен
 * «выключено», но флаг [enabled] позволяет UI честно показать состояние.
 */
@Serializable
data class AppSelection(
    val enabled: Boolean = false,
    val mode: RouteMode = RouteMode.INCLUDE,
    val packages: List<String> = emptyList()
) {
    fun sanitized(): AppSelection = copy(packages = packages.distinct().filter { it.isNotBlank() })

    fun isEmpty(): Boolean = packages.isEmpty()

    /** Выбор активен и влияет на трафик. */
    fun active(): Boolean = enabled && packages.isNotEmpty()

    companion object {
        val NONE: AppSelection = AppSelection()
    }
}

/**
 * Выборы приложений по функции.
 *
 * VPN и Tor делят один туннель, поэтому выбор применяется по активной
 * сессии; Zapret и GoodbyeDPI -- отдельные демоны с общим NFQUEUE-правилом,
 * поэтому их выборы объединяются при построении цепочки.
 */
@Serializable
data class PerAppConfig(
    val vpn: AppSelection = AppSelection(),
    val tor: AppSelection = AppSelection(),
    val zapret: AppSelection = AppSelection(),
    val goodbyeDpi: AppSelection = AppSelection(),

    /** Показывать ли системные приложения в списке пикера. */
    val showSystemApps: Boolean = false,

    /**
     * Группировать список по категориям с галкой на всю группу.
     *
     * На десктопе игнорируется: там каталог -- дерево процессов,
     * категории у него нет, и группировка разорвала бы ветки.
     */
    val groupByCategory: Boolean = true
) {
    fun sanitized(): PerAppConfig = copy(
        vpn = vpn.sanitized(),
        tor = tor.sanitized(),
        zapret = zapret.sanitized(),
        goodbyeDpi = goodbyeDpi.sanitized()
    )
}

/**
 * Установленное приложение либо процесс для пикера.
 *
 * [uid] -- числовой идентификатор процесса в Android (и настоящий uid
 * владельца на Linux); для десктопа, где понятия «приложения» нет, может
 * быть null. [isSystem] -- флаг системного пакета, по нему пикер
 * фильтрует список галочкой «показывать системные».
 *
 * [depth]/[familyId] обслуживают десктопный режим «процессы»: там
 * вместо списка пакетов строится дерево процессов (родитель и его
 * потомки), [depth] -- уровень узла для отступа, а [familyId] -- id
 * ветки: выбор одного процесса тянет за собой всю ветку, а поиск
 * показывает семью целиком. На Android [depth] всегда 0, а [familyId]
 * равен [uid] (семья -- соседи по sharedUserId).
 */
data class InstalledApp(
    val packageName: String,
    val label: String,
    val uid: Int?,
    val isSystem: Boolean,
    val depth: Int = 0,
    val familyId: Int? = null,

    /** Категория для группировки в пикере; платформа не размечена -- OTHER. */
    val category: AppCategory = AppCategory.OTHER
)

/**
 * Список установленных приложений и разрешение пакета в uid.
 *
 * Единственный источник правды -- платформа: Android читает
 * PackageManager, десктоп честно возвращает пустой список (там выбирать
 * нечего). Пикер и AppState работают с этими двумя функциями и не знают,
 * как именно устроено перечисление.
 */
expect fun installedApps(): List<InstalledApp>

/** UID пакета либо null, если пакет не установлен или платформа не умеет. */
expect fun packageUid(packageName: String): Int?

/**
 * Пакет самого приложения, либо пустая строка, если платформа не знает.
 *
 * Нужен пикеру: свой трафик всегда идёт мимо туннеля (иначе сокеты
 * ядра зациклились бы), и показывать галку на нём -- враньё.
 */
expect fun ownPackageName(): String