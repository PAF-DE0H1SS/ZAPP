package xyz.azraellab.zapp.ui.pages

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.foundation.layout.fillMaxWidth
import xyz.azraellab.zapp.core.AppLang
import xyz.azraellab.zapp.core.AppState
import xyz.azraellab.zapp.core.GoodbyeDpiMode
import xyz.azraellab.zapp.core.Str
import xyz.azraellab.zapp.ui.AppLangState
import xyz.azraellab.zapp.ui.components.Choice
import xyz.azraellab.zapp.ui.components.ZappCard
import xyz.azraellab.zapp.ui.components.ZappChoiceRow
import xyz.azraellab.zapp.ui.components.ZappTextField
import xyz.azraellab.zapp.ui.tr

/**
 * Страница GoodbyeDPI.
 *
 * Набор ручек короче, чем у Zapret, и это не недоработка: GoodbyeDPI работает
 * с рукопожатием, а не с пакетами, поэтому у него физически нет TTL, портов и
 * desync. Показывать такие поля было бы враньём.
 *
 * Вид страницы повторяет Zapret: запуск, режим и область действия сверху,
 * тонкие настройки -- в Настройках > GoodbyeDPI.
 */
@Composable
fun GoodbyeDpiPage(state: AppState) {
    val config = state.config.goodbyeDpi
    val mode = GoodbyeDpiMode.of(config.mode)

    PageScaffold(title = tr(Str.TAB_GOODBYE_DPI)) {
        // --- Управление и состояние ---
        DaemonControlCard(
            engine = state.dpiDaemon,
            onStart = { state.startDpi() },
            onStop = { state.stopDpi() }
        )

        // --- Режим и автоподбор ---
        ZappCard {
            ZappChoiceRow(
                label = tr(Str.DPI_MODE),
                options = GoodbyeDpiMode.entries.map { Choice(it.code, it.label(lang())) },
                selected = config.mode,
                onSelect = { code ->
                    state.mutate { it.copy(goodbyeDpi = it.goodbyeDpi.copy(mode = code)) }
                }
            )
            // Fake SNI без домена не стартует: --fake-with-sni обязателен
            // для аргумента. Предупреждение здесь, а не в журнале после
            // неудачного запуска.
            if (mode == GoodbyeDpiMode.FAKE_SNI && config.fakeSni.isBlank()) {
                Text(
                    text = tr(Str.DAEMON_NO_SNI),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error
                )
            }
            StrategyProbeCard(state = state, target = "gdp")
        }

        // --- Область действия ---
        ZappCard {
            ZappTextField(
                label = tr(Str.LIST_DOMAINS),
                value = config.domains,
                onValueChange = { v ->
                    state.mutate { it.copy(goodbyeDpi = it.goodbyeDpi.copy(domains = v)) }
                },
                placeholder = "example.com, youtube.com",
                supportingText = tr(Str.LIST_DOMAINS) + ": ${config.domainList().size}"
            )
        }

        // --- Команда, которая получится ---
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

        // --- Журнал ---
        DaemonLogCard(engine = state.dpiDaemon)
    }
}

@Composable
private fun lang(): AppLang = AppLangState.current

@Composable
private fun GoodbyeDpiMode.label(lang: AppLang): String = if (lang == AppLang.RU) ru else en
