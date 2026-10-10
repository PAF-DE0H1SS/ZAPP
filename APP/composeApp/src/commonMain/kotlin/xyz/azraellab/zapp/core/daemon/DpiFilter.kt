package xyz.azraellab.zapp.core.daemon

import xyz.azraellab.zapp.core.perApp.AppFamily
import xyz.azraellab.zapp.core.perApp.AppSelection
import xyz.azraellab.zapp.core.perApp.InstalledApp
import xyz.azraellab.zapp.core.perApp.RouteMode

/**
 * UID-ы, между которыми делится трафик обхода DPI.
 *
 * [owners] -- приложения, чей пакет уходит в NFQUEUE (include-режим),
 * [excludes] -- приложения, чьи пакеты возвращаются из цепочки мимо
 * очереди (exclude-режим). Ровно один из списков непуст, либо оба
 * пусты -- «фильтра нет, в очередь идёт весь трафик».
 */
data class DpiAppFilter(
    val owners: List<Int> = emptyList(),
    val excludes: List<Int> = emptyList()
) {
    val unfiltered: Boolean get() = owners.isEmpty() && excludes.isEmpty()
}

/**
 * Сводит выборы живых демонов к паре списков uid для iptables.
 *
 * Один NFQUEUE обслуживает оба демона, поэтому фильтр обязан быть общим:
 * разные режимы у zapret и goodbyedpi одной очередью не выражаются, и в
 * таком случае фильтр снимается целиком (в очередь идёт весь трафик на
 * портах) -- это безопасная деградация, а не ошибка. Выбор без enabled
 * или с пустым списком снимает фильтр: в старой семантике «включён
 * фильтр, выбрано ноль приложений» означало бы пустую очередь.
 *
 * Пакеты расширяются до uid-семей: соседи по sharedUserId пришли бы
 * вместе с приложением, которого выбор касается.
 */
fun dpiAppFilter(selections: List<AppSelection>, catalog: List<InstalledApp>): DpiAppFilter {
    val active = selections.filter { it.enabled && it.packages.isNotEmpty() }
    if (active.isEmpty() || active.size != selections.size) return DpiAppFilter()
    if (active.any { it.mode != active.first().mode }) return DpiAppFilter()

    val packages = active.flatMap { it.packages }.distinct()
    val uids = AppFamily.uids(AppFamily.expand(packages, catalog), catalog)
        .distinct().sorted()
    return when (active.first().mode) {
        RouteMode.INCLUDE -> DpiAppFilter(owners = uids)
        RouteMode.EXCLUDE -> DpiAppFilter(excludes = uids)
    }
}
