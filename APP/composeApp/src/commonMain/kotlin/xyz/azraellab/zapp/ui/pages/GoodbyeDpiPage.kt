package xyz.azraellab.zapp.ui.pages

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
 */
@Composable
fun GoodbyeDpiPage(state: AppState) {
    val config = state.config.goodbyeDpi
    var advancedOpen by rememberSaveable { mutableStateOf(false) }
    val lang = AppLangState.current

    PageScaffold(title = tr(Str.TAB_GOODBYE_DPI)) {
        ZappCard {
            ZappSettingRow(
                title = tr(Str.TAB_GOODBYE_DPI),
                checked = config.autoMode || GoodbyeDpiMode.of(config.mode) != GoodbyeDpiMode.DISABLED,
                onCheckedChange = { on ->
                    state.mutate { current ->
                        current.copy(
                            goodbyeDpi = current.goodbyeDpi.copy(
                                autoMode = false,
                                mode = if (on) GoodbyeDpiMode.AUTO.code else GoodbyeDpiMode.DISABLED.code
                            )
                        )
                    }
                }
            )
        }

        PageGroup(tr(Str.LIST_DOMAINS)) {
            ZappCard {
                ZappTextField(
                    label = tr(Str.LIST_DOMAINS),
                    value = config.domains,
                    onValueChange = { v ->
                        state.mutate { it.copy(goodbyeDpi = it.goodbyeDpi.copy(domains = v)) }
                    },
                    placeholder = "example.com, youtube.com"
                )
                ZappTextField(
                    label = tr(Str.LIST_APPS),
                    value = config.apps,
                    onValueChange = { v ->
                        state.mutate { it.copy(goodbyeDpi = it.goodbyeDpi.copy(apps = v)) }
                    },
                    placeholder = "com.android.chrome"
                )
                ZappTextField(
                    label = tr(Str.LIST_EXCLUSIONS),
                    value = config.exclusions,
                    onValueChange = { v ->
                        state.mutate { it.copy(goodbyeDpi = it.goodbyeDpi.copy(exclusions = v)) }
                    },
                    placeholder = "cdn.example.org"
                )
            }
        }

        PageGroup(tr(Str.DPI_MODE)) {
            ZappCard {
                ZappChoiceRow(
                    label = tr(Str.DPI_MODE),
                    options = GoodbyeDpiMode.entries.map { Choice(it.code, it.label(lang)) },
                    selected = config.mode,
                    onSelect = { code ->
                        state.mutate { it.copy(goodbyeDpi = it.goodbyeDpi.copy(mode = code)) }
                    }
                )
            }
        }

        ZappCard {
            ZappSettingRow(
                title = tr(Str.COMMON_TEST),
                checked = advancedOpen,
                onCheckedChange = { advancedOpen = it }
            )
        }

        if (advancedOpen) {
            PageGroup(tr(Str.DPI_MODE)) {
                ZappCard {
                    ZappNumberField(
                        label = tr(Str.DPI_SPLIT_POS),
                        value = config.splitPos.toIntOrNull(),
                        onValueChange = { v ->
                            state.mutate { it.copy(goodbyeDpi = it.goodbyeDpi.copy(splitPos = v?.toString() ?: "")) }
                        },
                        placeholder = "2"
                    )
                    ZappNumberField(
                        label = tr(Str.DPI_SPLIT_OFFSET),
                        value = config.splitOffset,
                        onValueChange = { v ->
                            state.mutate { it.copy(goodbyeDpi = it.goodbyeDpi.copy(splitOffset = v)) }
                        }
                    )
                    ZappNumberField(
                        label = tr(Str.DPI_FAKE_LEN),
                        value = config.fakeLen,
                        onValueChange = { v ->
                            state.mutate { it.copy(goodbyeDpi = it.goodbyeDpi.copy(fakeLen = v)) }
                        }
                    )
                    ZappTextField(
                        label = tr(Str.DPI_FAKE_VAL),
                        value = config.fakeVal,
                        onValueChange = { v ->
                            state.mutate { it.copy(goodbyeDpi = it.goodbyeDpi.copy(fakeVal = v)) }
                        },
                        placeholder = "0xDEADBEEF"
                    )
                    ZappNumberField(
                        label = tr(Str.DPI_FAKE_SEQ),
                        value = config.fakeSeq,
                        onValueChange = { v ->
                            state.mutate { it.copy(goodbyeDpi = it.goodbyeDpi.copy(fakeSeq = v)) }
                        }
                    )
                    ZappSettingRow(
                        title = tr(Str.DPI_FAKE_CSUM),
                        checked = config.fakeCsum,
                        onCheckedChange = { on ->
                            state.mutate { it.copy(goodbyeDpi = it.goodbyeDpi.copy(fakeCsum = on)) }
                        }
                    )
                    ZappNumberField(
                        label = tr(Str.DPI_FAKE_FLAGS),
                        value = config.fakeFlags,
                        onValueChange = { v ->
                            state.mutate { it.copy(goodbyeDpi = it.goodbyeDpi.copy(fakeFlags = v)) }
                        }
                    )
                    ZappNumberField(
                        label = tr(Str.DPI_FAKE_MSS),
                        value = config.fakeMss,
                        onValueChange = { v ->
                            state.mutate { it.copy(goodbyeDpi = it.goodbyeDpi.copy(fakeMss = v)) }
                        }
                    )
                }
            }

            PageGroup(tr(Str.SETTINGS_SYSTEM)) {
                ZappCard {
                    ZappSettingRow(
                        title = tr(Str.DPI_SKIP_ALPN),
                        checked = config.skipAlpn,
                        onCheckedChange = { on ->
                            state.mutate { it.copy(goodbyeDpi = it.goodbyeDpi.copy(skipAlpn = on)) }
                        }
                    )
                    ZappSettingRow(
                        title = tr(Str.DPI_SKIP_TLS13),
                        checked = config.skipTls13,
                        onCheckedChange = { on ->
                            state.mutate { it.copy(goodbyeDpi = it.goodbyeDpi.copy(skipTls13 = on)) }
                        }
                    )
                    ZappSettingRow(
                        title = tr(Str.DPI_KEEP_SNI),
                        checked = config.keepSni,
                        onCheckedChange = { on ->
                            state.mutate { it.copy(goodbyeDpi = it.goodbyeDpi.copy(keepSni = on)) }
                        }
                    )
                    ZappTextField(
                        label = tr(Str.DPI_DAEMON_PATH),
                        value = config.daemonPath,
                        onValueChange = { v ->
                            state.mutate { it.copy(goodbyeDpi = it.goodbyeDpi.copy(daemonPath = v)) }
                        },
                        placeholder = "/data/local/tmp/goodbyedpi"
                    )
                    ZappSettingRow(
                        title = tr(Str.DPI_AUTO_RESTART),
                        checked = config.autoRestart,
                        onCheckedChange = { on ->
                            state.mutate { it.copy(goodbyeDpi = it.goodbyeDpi.copy(autoRestart = on)) }
                        }
                    )
                    ZappSettingRow(
                        title = tr(Str.ZAPRET_LOGGING),
                        checked = config.logging,
                        onCheckedChange = { on ->
                            state.mutate { it.copy(goodbyeDpi = it.goodbyeDpi.copy(logging = on)) }
                        }
                    )
                }
            }
        }
    }
}

@Composable
private fun GoodbyeDpiMode.label(lang: AppLang): String = if (lang == AppLang.RU) ru else en
