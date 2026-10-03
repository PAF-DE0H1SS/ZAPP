package xyz.azraellab.zapp.ui.pages

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import xyz.azraellab.zapp.core.AppLang
import xyz.azraellab.zapp.core.AppState
import xyz.azraellab.zapp.core.GoodbyeDpiMode
import xyz.azraellab.zapp.core.Str
import xyz.azraellab.zapp.ui.AppLangState
import xyz.azraellab.zapp.ui.components.Choice
import xyz.azraellab.zapp.ui.components.ZappCard
import xyz.azraellab.zapp.ui.components.ZappChoiceRow
import xyz.azraellab.zapp.ui.components.ZappNumberField
import xyz.azraellab.zapp.ui.components.ZappSettingRow
import xyz.azraellab.zapp.ui.components.ZappTextField
import xyz.azraellab.zapp.ui.tr

/**
 * Страница GoodbyeDPI.
 *
 * Набор ручек короче, чем у Zapret, и это не недоработка: GoodbyeDPI работает
 * с рукопожатием, а не с пакетами, поэтому у него физически нет TTL, портов и
 * desync. Показывать такие поля было бы враньём.
 *
 * Вид страницы повторяет Zapret: управление сверху, режим и его поля,
 * журнал внизу. Одинаковая форма двух демонов -- чтобы переключение между
 * ними не требовало заново учиться.
 */
@Composable
fun GoodbyeDpiPage(state: AppState) {
    val config = state.config.goodbyeDpi
    var advancedOpen by rememberSaveable { mutableStateOf(false) }
    val mode = GoodbyeDpiMode.of(config.mode)

    PageScaffold(title = tr(Str.TAB_GOODBYE_DPI)) {
        // --- Управление и состояние ---
        DaemonControlCard(
            engine = state.dpiDaemon,
            onStart = { state.startDpi() },
            onStop = { state.stopDpi() }
        )

        // --- Автостарт и автоподбор ---
        ZappCard {
            ZappSettingRow(
                title = tr(Str.DAEMON_AUTOSTART),
                checked = config.autoStart,
                onCheckedChange = { on ->
                    state.mutate { it.copy(goodbyeDpi = it.goodbyeDpi.copy(autoStart = on)) }
                }
            )
        }
        StrategyProbeCard(state = state, target = "gdp")

        // --- Область действия ---
        PageGroup(tr(Str.LIST_DOMAINS)) {
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
        }

        // --- Режим ---
        PageGroup(tr(Str.DPI_MODE)) {
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
            }
        }

        // --- Дополнительно ---
        ZappCard {
            ZappSettingRow(
                title = tr(Str.COMMON_ADVANCED),
                checked = advancedOpen,
                onCheckedChange = { advancedOpen = it }
            )
        }

        if (advancedOpen) {
            GoodbyeDpiAdvanced(state = state, config = config, mode = mode)
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
                color = MaterialTheme.colorScheme.onSurface
            )
        }

        // --- Журнал ---
        DaemonLogCard(engine = state.dpiDaemon)
    }
}

/**
 * Тонкие настройки GoodbyeDPI.
 *
 * Поля показываются только когда у выбранного режима есть что ими делать:
 * позиция -- у разреза, домен -- у Fake SNI. Выключенные режимы не
 * подставляют флаги, и такое поле в команду всё равно не попадёт.
 */
@Composable
private fun GoodbyeDpiAdvanced(
    state: AppState,
    config: xyz.azraellab.zapp.core.GoodbyeDpiConfig,
    mode: GoodbyeDpiMode
) {
    PageGroup(tr(Str.DPI_MODE)) {
        ZappCard {
            if (mode == GoodbyeDpiMode.SPLIT_POS) {
                ZappNumberField(
                    label = tr(Str.DPI_SPLIT_POS),
                    value = config.splitPos.toIntOrNull(),
                    onValueChange = { v ->
                        state.mutate {
                            it.copy(goodbyeDpi = it.goodbyeDpi.copy(splitPos = v?.toString() ?: "2"))
                        }
                    },
                    placeholder = "2"
                )
            }
            if (mode != GoodbyeDpiMode.FAKE_SSL) {
                ZappNumberField(
                    label = tr(Str.DPI_FAKE_SEQ),
                    value = config.fakeSeq,
                    onValueChange = { v ->
                        state.mutate { it.copy(goodbyeDpi = it.goodbyeDpi.copy(fakeSeq = v)) }
                    }
                )
            }
            if (mode != GoodbyeDpiMode.FAKE_PATCH) {
                ZappSettingRow(
                    title = tr(Str.DPI_FAKE_CSUM),
                    checked = config.fakeCsum,
                    onCheckedChange = { on ->
                        state.mutate { it.copy(goodbyeDpi = it.goodbyeDpi.copy(fakeCsum = on)) }
                    }
                )
            }
            if (mode == GoodbyeDpiMode.FAKE_SNI) {
                ZappTextField(
                    label = tr(Str.DPI_FAKE_SNI),
                    value = config.fakeSni,
                    onValueChange = { v ->
                        state.mutate { it.copy(goodbyeDpi = it.goodbyeDpi.copy(fakeSni = v)) }
                    },
                    placeholder = "example.com"
                )
            }
            // Название -- сам флаг: включён означает «пропустить без SNI»,
            // и такая строка совпадает с тем, что уйдёт в команду.
            ZappSettingRow(
                title = "--allow-no-sni",
                checked = !config.hostCheck,
                onCheckedChange = { on ->
                    state.mutate { it.copy(goodbyeDpi = it.goodbyeDpi.copy(hostCheck = !on)) }
                }
            )
        }
    }

    PageGroup(tr(Str.SETTINGS_SYSTEM)) {
        ZappCard {
            ZappTextField(
                label = tr(Str.DPI_DAEMON_PATH),
                value = config.daemonPath,
                onValueChange = { v ->
                    state.mutate { it.copy(goodbyeDpi = it.goodbyeDpi.copy(daemonPath = v)) }
                },
                placeholder = "goodbyedpi"
            )
        }
    }
}

@Composable
private fun lang(): AppLang = AppLangState.current

@Composable
private fun GoodbyeDpiMode.label(lang: AppLang): String = if (lang == AppLang.RU) ru else en
