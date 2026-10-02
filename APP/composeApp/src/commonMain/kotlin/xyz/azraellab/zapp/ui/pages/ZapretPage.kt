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
        // --- Включение ---
        ZappCard {
            ZappSettingRow(
                title = tr(Str.TAB_ZAPRET),
                checked = config.enabled,
                onCheckedChange = { on -> state.mutate { it.copy(zapret = it.zapret.copy(enabled = on)) } }
            )
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
                    label = tr(Str.LIST_APPS),
                    value = config.apps,
                    onValueChange = { v -> state.mutate { it.copy(zapret = it.zapret.copy(apps = v)) } },
                    placeholder = "com.android.chrome"
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
                            state.mutate { it.copy(zapret = it.zapret.copy(strategy = code)) }
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
            }
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
                title = tr(Str.ZAPRET_FILTER_TCP) + " / " + tr(Str.ZAPRET_DPI_PORTS),
                checked = advancedOpen,
                onCheckedChange = { advancedOpen = it }
            )
        }

        if (advancedOpen) {
            ZapretAdvanced(state = state, config = config)
        }
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
            ZappTextField(
                label = tr(Str.ZAPRET_DPI_PORTS),
                value = config.dpiPorts,
                onValueChange = { v -> state.mutate { it.copy(zapret = it.zapret.copy(dpiPorts = v)) } },
                placeholder = "443"
            )
            ZappTextField(
                label = "filter-ip",
                value = config.filterIp,
                onValueChange = { v -> state.mutate { it.copy(zapret = it.zapret.copy(filterIp = v)) } },
                placeholder = "1.2.3.0/24"
            )
            ZappSettingRow(
                title = tr(Str.ZAPRET_NEW_SYNTAX),
                checked = config.newSyntax,
                onCheckedChange = { on ->
                    state.mutate { it.copy(zapret = it.zapret.copy(newSyntax = on)) }
                }
            )
            ZappSettingRow(
                title = tr(Str.ZAPRET_IPDIAG),
                checked = config.ipdiag,
                onCheckedChange = { on -> state.mutate { it.copy(zapret = it.zapret.copy(ipdiag = on)) } }
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
                ZappNumberField(
                    label = tr(Str.ZAPRET_WINDOW),
                    value = config.wsize,
                    onValueChange = { v -> state.mutate { it.copy(zapret = it.zapret.copy(wsize = v)) } },
                    placeholder = "65535"
                )
                ZappTextField(
                    label = tr(Str.ZAPRET_DPI_SPLIT),
                    value = config.dpiSplit,
                    onValueChange = { v -> state.mutate { it.copy(zapret = it.zapret.copy(dpiSplit = v)) } },
                    placeholder = "seqovl"
                )
                ZappTextField(
                    label = "--dpi-split-pos",
                    value = config.dpiSplitPos,
                    onValueChange = { v -> state.mutate { it.copy(zapret = it.zapret.copy(dpiSplitPos = v)) } },
                    placeholder = "1,m1"
                )
                ZappNumberField(
                    label = "--dpi-split-seqovl",
                    value = config.dpiSplitSeqovl,
                    onValueChange = { v ->
                        state.mutate { it.copy(zapret = it.zapret.copy(dpiSplitSeqovl = v)) }
                    }
                )
                ZappSettingRow(
                    title = tr(Str.ZAPRET_MULTIPATH),
                    checked = config.multipath,
                    onCheckedChange = { on ->
                        state.mutate { it.copy(zapret = it.zapret.copy(multipath = on)) }
                    }
                )
                ZappNumberField(
                    label = "--multiply",
                    value = config.multiply,
                    onValueChange = { v -> state.mutate { it.copy(zapret = it.zapret.copy(multiply = v)) } }
                )
            }

            // Подмена пакета относится к fake и patch.
            if (family == ZapretFamily.FAKE || family == ZapretFamily.PATCH) {
                ZappSettingRow(
                    title = tr(Str.ZAPRET_FAKE),
                    checked = config.fakePacket,
                    onCheckedChange = { on ->
                        state.mutate { it.copy(zapret = it.zapret.copy(fakePacket = on)) }
                    }
                )
                ZappTextField(
                    label = "--fake-tls",
                    value = config.fakeTlsMode,
                    onValueChange = { v -> state.mutate { it.copy(zapret = it.zapret.copy(fakeTlsMode = v)) } },
                    placeholder = "SNI"
                )
                ZappTextField(
                    label = "--fake-tls-host",
                    value = config.fakeTlsHost,
                    onValueChange = { v ->
                        state.mutate { it.copy(zapret = it.zapret.copy(fakeTlsHost = v)) }
                    },
                    placeholder = "example.com"
                )
                ZappNumberField(
                    label = "--fake-seq",
                    value = config.fakeSeq,
                    onValueChange = { v -> state.mutate { it.copy(zapret = it.zapret.copy(fakeSeq = v)) } }
                )
                ZappSettingRow(
                    title = "--fake-csum",
                    checked = config.fakeCsum,
                    onCheckedChange = { on ->
                        state.mutate { it.copy(zapret = it.zapret.copy(fakeCsum = on)) }
                    }
                )
                ZappSettingRow(
                    title = "--fake-cut-tls",
                    checked = config.fakeCutTls,
                    onCheckedChange = { on ->
                        state.mutate { it.copy(zapret = it.zapret.copy(fakeCutTls = on)) }
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
                title = "--methodspace",
                checked = config.methodSpace,
                onCheckedChange = { on ->
                    state.mutate { it.copy(zapret = it.zapret.copy(methodSpace = on)) }
                }
            )
            ZappNumberField(
                label = tr(Str.ZAPRET_MSS),
                value = config.mss,
                onValueChange = { v -> state.mutate { it.copy(zapret = it.zapret.copy(mss = v)) } }
            )
            ZappSettingRow(
                title = tr(Str.ZAPRET_LOGGING),
                checked = config.logging,
                onCheckedChange = { on -> state.mutate { it.copy(zapret = it.zapret.copy(logging = on)) } }
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
