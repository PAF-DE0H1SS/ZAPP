package xyz.azraellab.zapp.ui.pages

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import xyz.azraellab.zapp.core.AppLang
import xyz.azraellab.zapp.core.AppState
import xyz.azraellab.zapp.core.Str
import xyz.azraellab.zapp.core.ZapretStrategy
import xyz.azraellab.zapp.core.domains.DpiScope
import xyz.azraellab.zapp.ui.AppLangState
import xyz.azraellab.zapp.ui.components.Choice
import xyz.azraellab.zapp.ui.components.ZappCard
import xyz.azraellab.zapp.ui.components.ZappChoiceRow
import xyz.azraellab.zapp.ui.components.ZappSettingRow
import xyz.azraellab.zapp.ui.components.ZappTextField
import xyz.azraellab.zapp.ui.tr

/** Подпись стратегии на языке интерфейса; в списке -- не код строки. */
private fun ZapretStrategy.strategyLabel(): String =
    if (AppLangState.current == AppLang.RU) ru else en

/**
 * Страница Zapret.
 *
 * Главный экран отвечает за запуск, выбор стратегии и область действия.
 * Тонкие ручки (фильтры портов, desync, пути) живут в Настройках > Zapret:
 * их меняют один раз при разборе конкретной сети, а не каждый запуск.
 */
@Composable
fun ZapretPage(state: AppState) {
    val config = state.config.zapret

    PageScaffold(title = tr(Str.TAB_ZAPRET)) {
        // --- Управление и состояние ---
        DaemonControlCard(
            engine = state.zapretDaemon,
            onStart = { state.startZapret() },
            onStop = { state.stopZapret() },
            path = "zapret"
        )

        // --- Стратегия: автоподбор или ручной выбор ---
        ZappCard {
            ZappSettingRow(
                title = tr(Str.ZAPRET_AUTO),
                subtitle = tr(Str.STRATEGY_PROBE),
                checked = config.autoStrategy,
                onCheckedChange = { on ->
                    state.mutate { it.copy(zapret = it.zapret.copy(autoStrategy = on)) }
                },
                path = "zapret.auto"
            )
            // Список стратегий показывается только при ручном выборе: при
            // автоподборе он всё равно будет перезаписан.
            if (!config.autoStrategy) {
                ZappChoiceRow(
                    label = tr(Str.ZAPRET_STRATEGY),
                    options = ZapretStrategy.selectable.map { Choice(it.code, it.strategyLabel()) },
                    selected = config.strategy,
                    onSelect = { code ->
                        // Пресет применяется целиком: одна строка strategy
                        // без её флагов -- «работает», но не меняет команду.
                        state.mutate {
                            it.copy(
                                zapret = xyz.azraellab.zapp.core.AutoStrategy.applyZapret(
                                    it.zapret,
                                    xyz.azraellab.zapp.core.AutoStrategy.Decision(code, "manual")
                                )
                            )
                        }
                    },
                    path = "zapret.strategy"
                )
            }
            StrategyProbeCard(state = state, target = "zapret")
        }

        // --- Область действия ---
        ZappCard {
            ZappTextField(
                label = tr(Str.LIST_DOMAINS),
                value = config.domains,
                onValueChange = { v -> state.mutate { it.copy(zapret = it.zapret.copy(domains = v)) } },
                placeholder = "example.com, youtube.com",
                supportingText = tr(Str.LIST_DOMAINS) + ": ${config.domainList().size}",
                path = "zapret.domains"
            )
            // Без выбора приложений и без доменов запуск блокируется --
            // причина видна до нажатия, а не только в журнале после.
            if (!DpiScope.zapret(state.config.perApp, config)) {
                Text(
                    text = tr(Str.DAEMON_NEED_SCOPE),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error
                )
            }
        }

        // --- Команда, которая получится: короткая сводка вместо стены флагов ---
        ZappCard {
            Text(
                text = tr(Str.ZAPRET_COMMAND),
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Text(
                text = config.toCommandLine(),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 3,
                modifier = Modifier.fillMaxWidth()
            )
        }

        // --- Журнал: причина запуска и смерти процесса ---
        DaemonLogCard(engine = state.zapretDaemon, path = "zapret.log.expand")
    }
}
