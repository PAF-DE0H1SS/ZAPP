package xyz.azraellab.zapp.core.domains

import xyz.azraellab.zapp.core.GoodbyeDpiConfig
import xyz.azraellab.zapp.core.ZapretConfig
import xyz.azraellab.zapp.core.perApp.PerAppConfig

/**
 * Готова ли область действия обхода к запуску.
 *
 * Обход без выбранных приложений и без доменов -- пустая команда:
 * nfqws и goodbyedpi фильтруют трафик, и без целей им нечего фильтровать.
 * Поэтому запуск и автоподбор стратегии требуют хотя бы одного
 * основания: активный выбор приложений (режим не важен -- и «только
 * они», и «все, кроме них» задают область) либо введённые домены.
 *
 * Функции чистые: обе -- один и тот же факт для UI, движков и тестов.
 */
object DpiScope {

    /** Область zapret: выбор приложений zapret либо его список доменов. */
    fun zapret(perApp: PerAppConfig, cfg: ZapretConfig): Boolean =
        perApp.zapret.active() || cfg.domainList().isNotEmpty()

    /** Область goodbyedpi: выбор приложений goodbyedpi либо его список доменов. */
    fun gdpi(perApp: PerAppConfig, cfg: GoodbyeDpiConfig): Boolean =
        perApp.goodbyeDpi.active() || cfg.domainList().isNotEmpty()
}
