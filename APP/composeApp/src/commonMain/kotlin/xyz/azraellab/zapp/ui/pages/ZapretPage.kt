package xyz.azraellab.zapp.ui.pages

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import xyz.azraellab.zapp.core.AppLang
import xyz.azraellab.zapp.core.AppState
import xyz.azraellab.zapp.core.Str
import xyz.azraellab.zapp.core.ZapretConfig
import xyz.azraellab.zapp.core.ZapretFamily
import xyz.azraellab.zapp.core.ZapretMode
import xyz.azraellab.zapp.core.ZapretStrategy
import xyz.azraellab.zapp.ui.AppLangState
import xyz.azraellab.zapp.ui.components.Choice
import xyz.azraellab.zapp.ui.components.ZappCard
import xyz.azraellab.zapp.ui.components.ZappChoiceRow
import xyz.azraellab.zapp.ui.components.ZappNumberField
import xyz.azraellab.zapp.ui.components.ZappSettingRow
import xyz.azraellab.zapp.ui.components.ZappTextField
import xyz.azraellab.zapp.ui.tr

/**
 * Страница Zapret.
 *
 * Тонкие ручки спрятаны под переключатель «Дополнительно»: их много, и
 * постоянно видимая стена полей превращает страницу в форму, которой
 * пользуются один раз. Те, кому нужно, открывают одним нажатием.
 */
@Composable
fun ZapretPage(state: AppState) {
    val config = state.config.zapret
    var advancedOpen by rememberSaveable { mutableStateOf(false) }

    PageScaffold(title = tr(Str.TAB_ZAPRET)) {
        // --- Управление и состояние ---
        DaemonControlCard(
            engine = state.zapretDaemon,
            onStart = { state.startZapret() },
            onStop = { state.stopZapret() }
        )

        ZappCard {
            ZappSettingRow(
                title = tr(Str.ZAPRET_ALL_TRAFFIC),
                checked = config.allTraffic,
                onCheckedChange = { on -> state.mutate { it.copy(zapret = it.zapret.copy(allTraffic = on)) } }
            )
            ZappSettingRow(
                title = tr(Str.ZAPRET_IPV4_ONLY),
                checked = config.ipv4Only,
                onCheckedChange = { on -> state.mutate { it.copy(zapret = it.zapret.copy(ipv4Only = on)) } }
            )
        }

        // --- Область действия ---
        PageGroup(tr(Str.LIST_DOMAINS)) {
            ZappCard {
                ZappTextField(
                    label = tr(Str.LIST_DOMAINS),
                    value = config.domains,
                    onValueChange = { v -> state.mutate { it.copy(zapret = it.zapret.copy(domains = v)) } },
                    placeholder = "example.com, youtube.com",
                    supportingText = tr(Str.LIST_DOMAINS) + ": ${config.domainList().size}"
                )
                ZappTextField(
                    label = tr(Str.LIST_EXCLUSIONS),
                    value = config.exclusions,
                    onValueChange = { v -> state.mutate { it.copy(zapret = it.zapret.copy(exclusions = v)) } },
                    placeholder = "cdn.example.org"
                )
            }
        }

        // --- Стратегия ---
        PageGroup(tr(Str.ZAPRET_STRATEGY)) {
            ZappCard {
                ZappSettingRow(
                    title = tr(Str.ZAPRET_AUTO),
                    checked = config.autoStrategy,
                    onCheckedChange = { on ->
                        state.mutate { it.copy(zapret = it.zapret.copy(autoStrategy = on)) }
                    }
                )

                // Список стратегий показывается только при ручном выборе: при
                // автоподборе он всё равно будет перезаписан, и его наличие
                // только сбивает с толку.
                if (!config.autoStrategy) {
                    ZappChoiceRow(
                        label = tr(Str.ZAPRET_STRATEGY),
                        options = remember {
                            ZapretStrategy.selectable.map { Choice(it.code, it.code) }
                        },
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
                        }
                    )
                }

                ZappSettingRow(
                    title = tr(Str.COMMON_TEST),
                    checked = config.verifyStrategy,
                    onCheckedChange = { on ->
                        state.mutate { it.copy(zapret = it.zapret.copy(verifyStrategy = on)) }
                    }
                )
                ZappSettingRow(
                    title = tr(Str.DAEMON_AUTOSTART),
                    checked = config.autoRestart,
                    onCheckedChange = { on ->
                        state.mutate { it.copy(zapret = it.zapret.copy(autoRestart = on)) }
                    }
                )
            }

            StrategyProbeCard(state = state, target = "zapret")
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

        // --- Дополнительно ---
        ZappCard {
            ZappSettingRow(
                title = tr(Str.COMMON_ADVANCED),
                checked = advancedOpen,
                onCheckedChange = { advancedOpen = it }
            )
        }

        if (advancedOpen) {
            ZapretAdvanced(state = state, config = config)
        }

        // --- Журнал: причина запуска и смерти процесса ---
        DaemonLogCard(engine = state.zapretDaemon)
    }
}

/**
 * Тонкие настройки Zapret.
 *
 * Поля группируются по тому, что они меняют: фильтры, способ вмешательства,
 * сеть. Внутри каждой группы -- то, что относится к одному [ZapretFamily],
 * поэтому список всегда показывает только совместимые ручки выбранной стратегии.
 */
@Composable
private fun ZapretAdvanced(state: AppState, config: ZapretConfig) {
    val family = ZapretStrategy.of(config.strategy).family

    // --- Фильтры ---
    PageGroup(tr(Str.ZAPRET_FILTERS)) {
        ZappCard {
            ZappTextField(
                label = tr(Str.ZAPRET_FILTER_TCP),
                value = config.filterTcp,
                onValueChange = { v -> state.mutate { it.copy(zapret = it.zapret.copy(filterTcp = v)) } },
                placeholder = "80,443"
            )
            ZappTextField(
                label = tr(Str.ZAPRET_FILTER_UDP),
                value = config.filterUdp,
                onValueChange = { v -> state.mutate { it.copy(zapret = it.zapret.copy(filterUdp = v)) } },
                placeholder = "443"
            )
        }
    }

    // --- Путь к бинарю ---
    PageGroup(tr(Str.SETTINGS_SYSTEM)) {
        ZappCard {
            ZappTextField(
                label = tr(Str.DAEMON_BINARY),
                value = config.binaryPath,
                onValueChange = { v -> state.mutate { it.copy(zapret = it.zapret.copy(binaryPath = v)) } },
                placeholder = "zapret"
            )
        }
    }

    // --- Способ вмешательства ---
    PageGroup(tr(Str.ZAPRET_STRATEGY)) {
        ZappCard {
            ZappChoiceRow(
                label = tr(Str.ZAPRET_MODE),
                options = ZapretMode.entries.map { Choice(it.code, it.label()) },
                selected = config.mode,
                onSelect = { code -> state.mutate { it.copy(zapret = it.zapret.copy(mode = code)) } }
            )

            ZappChoiceRow(
                label = tr(Str.ZAPRET_DPI_DESYNC),
                options = listOf(
                    Choice("fake", "fake"),
                    Choice("split2", "split2"),
                    Choice("fakedsplit", "fakedsplit"),
                    Choice("multisplit", "multisplit"),
                    Choice("datanozzle", "datanozzle"),
                    Choice("multiback", "multiback"),
                    Choice("seqovl", "seqovl")
                ),
                selected = config.desyncMethods.firstOrNull() ?: "",
                onSelect = { method ->
                    state.mutate {
                        it.copy(zapret = it.zapret.copy(desyncMethods = setOf(method)))
                    }
                }
            )

            // TTL и окно относятся к desync. В других семействах они не имеют
            // смысла, поэтому поля не показываются -- «лишние элементы в
            // случае если они не доступны» должны исчезать, а не сереть.
            if (family == ZapretFamily.DESYNC) {
                ZappNumberField(
                    label = tr(Str.ZAPRET_TTL),
                    value = config.ttl,
                    onValueChange = { v -> state.mutate { it.copy(zapret = it.zapret.copy(ttl = v)) } },
                    placeholder = "2"
                )
                if (config.ttl != null) {
                    // Линейный TTL имеет смысл только вместе с базовым:
                    // без значения, от которого считать, флаг глуп.
                    ZappSettingRow(
                        title = "--dpi-desync-ttl6",
                        checked = config.ttl6,
                        onCheckedChange = { on ->
                            state.mutate { it.copy(zapret = it.zapret.copy(ttl6 = on)) }
                        }
                    )
                }
                ZappNumberField(
                    label = tr(Str.ZAPRET_WINDOW),
                    value = config.wsize,
                    onValueChange = { v -> state.mutate { it.copy(zapret = it.zapret.copy(wsize = v)) } },
                    placeholder = "65535"
                )
                ZappTextField(
                    label = "--dpi-desync-split-pos",
                    value = config.dpiSplitPos,
                    onValueChange = { v -> state.mutate { it.copy(zapret = it.zapret.copy(dpiSplitPos = v)) } },
                    placeholder = "1,m1"
                )
                ZappNumberField(
                    label = "--dpi-desync-split-seqovl",
                    value = config.dpiSplitSeqovl,
                    onValueChange = { v ->
                        state.mutate { it.copy(zapret = it.zapret.copy(dpiSplitSeqovl = v)) }
                    }
                )
                ZappNumberField(
                    label = "--dpi-desync-repeats",
                    value = config.fakeRepeats,
                    onValueChange = { v -> state.mutate { it.copy(zapret = it.zapret.copy(fakeRepeats = v)) } }
                )
            }

            // Подмена пакета: сам флаг -- режим fake у desync, а fooling
            // подставляет вместо старых --fake-csum/--fake-seq свои опции.
            if (family == ZapretFamily.FAKE || family == ZapretFamily.PATCH) {
                ZappSettingRow(
                    title = tr(Str.ZAPRET_FAKE),
                    checked = config.fakePacket,
                    onCheckedChange = { on ->
                        state.mutate { it.copy(zapret = it.zapret.copy(fakePacket = on)) }
                    }
                )
                ZappNumberField(
                    label = tr(Str.DPI_FAKE_SEQ),
                    value = config.fakeSeq,
                    onValueChange = { v -> state.mutate { it.copy(zapret = it.zapret.copy(fakeSeq = v)) } }
                )
                ZappSettingRow(
                    title = tr(Str.DPI_FAKE_CSUM),
                    checked = config.fakeCsum,
                    onCheckedChange = { on ->
                        state.mutate { it.copy(zapret = it.zapret.copy(fakeCsum = on)) }
                    }
                )
                ZappSettingRow(
                    title = "--dpi-desync-fooling=ts",
                    checked = config.foolingTs,
                    onCheckedChange = { on ->
                        state.mutate { it.copy(zapret = it.zapret.copy(foolingTs = on)) }
                    }
                )
            }

            // Правка хоста нужна всем, кроме режимов, где она уже включена
            // стратегией по умолчанию.
            ZappSettingRow(
                title = "--hostcase",
                checked = config.hostCase,
                onCheckedChange = { on -> state.mutate { it.copy(zapret = it.zapret.copy(hostCase = on)) } }
            )
            ZappSettingRow(
                title = "--hostspell",
                checked = config.hostSpell,
                onCheckedChange = { on -> state.mutate { it.copy(zapret = it.zapret.copy(hostSpell = on)) } }
            )
            ZappSettingRow(
                title = "--hostnospace",
                checked = config.hostNoSpace,
                onCheckedChange = { on -> state.mutate { it.copy(zapret = it.zapret.copy(hostNoSpace = on)) } }
            )
            ZappSettingRow(
                title = "--methodeol",
                checked = config.methodEol,
                onCheckedChange = { on -> state.mutate { it.copy(zapret = it.zapret.copy(methodEol = on)) } }
            )
            ZappNumberField(
                label = "--wssize",
                value = config.wsSize,
                onValueChange = { v -> state.mutate { it.copy(zapret = it.zapret.copy(wsSize = v)) } }
            )
        }
    }
}

/**
 * Метки режима.
 *
 * Режимов всего два и оба понятны по смыслу, поэтому подпись переводится.
 */
@Composable
private fun ZapretMode.label(): String {
    val lang = AppLangState.current
    return if (lang == AppLang.RU) ru else en
}
