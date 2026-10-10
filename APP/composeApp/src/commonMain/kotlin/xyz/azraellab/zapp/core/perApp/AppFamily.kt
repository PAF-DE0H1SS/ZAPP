package xyz.azraellab.zapp.core.perApp

/**
 * Расширение выбора приложений до «семейств».
 *
 * Один uid в Android -- это не обязательно один пакет: приложения с общим
 * `sharedUserId` живут в одном процессе и одним ухом ловят трафик друг
 * друга. Выбрал пользователь телеграм -- вместе с ним в один uid попадают
 * сопутствующие пакеты (вестернизатор, сервисы), и без расширения обход
 * поймал бы только часть процессов «телеграма». [expand] добавляет к
 * выбору все пакеты, делящие uid хотя бы с одним выбранным, -- так
 * «внутри» приложения оказывается весь его процессный кластер, а не
 * один пакет.
 *
 * Чистые функции без состояния: тот же каталог и выбор дают тот же
 * результат, поэтому их можно гонять и в тестах, и в AppState, и в
 * пикере -- расхождений не будет.
 */
object AppFamily {

    /**
     * Все пакеты, которые должны попасть в обход: выбранные плюс их
     * родственники по семье. Семья -- [familyId] если задан, иначе
     * [InstalledApp.uid]: на Android это uid (sharedUserId-соседи), на
     * десктопе -- ветка дерева процессов. Пакет из выбора, которого нет
     * в каталоге, остаётся как есть (он может быть установлен позже).
     */
    fun expand(selected: Collection<String>, catalog: List<InstalledApp>): List<String> {
        val result = selected.distinct().toMutableList()
        val ids = buildSet {
            for (app in catalog) {
                if (app.packageName in result) familyOf(app)?.let { add(it) }
            }
        }
        for (app in catalog) {
            val family = familyOf(app)
            if (family != null && family in ids && app.packageName !in result) {
                result += app.packageName
            }
        }
        return result
    }

    /**
     * UID всех пакетов выбора по каталогу. Пакет без uid в каталоге
     * игнорируется: его в обход на платформе всё равно не завернуть.
     * Здесь именно uid (а не семья): firewall фильтрует пакеты по
     * владельцу-процессу, а ветка процессов -- понятие выбора, не ядра.
     */
    fun uids(selected: Collection<String>, catalog: List<InstalledApp>): List<Int> =
        selected.distinct().mapNotNull { pkg ->
            catalog.firstOrNull { it.packageName == pkg }?.uid
        }

    /** Семья каталога: явный familyId, иначе uid. */
    private fun familyOf(app: InstalledApp): Int? = app.familyId ?: app.uid
}