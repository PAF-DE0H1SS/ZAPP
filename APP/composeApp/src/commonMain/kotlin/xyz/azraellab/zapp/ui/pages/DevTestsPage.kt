package xyz.azraellab.zapp.ui.pages

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import xyz.azraellab.zapp.core.Str
import xyz.azraellab.zapp.core.dev.DevCategory
import xyz.azraellab.zapp.core.dev.DevRunState
import xyz.azraellab.zapp.core.dev.DevTestRegistry
import xyz.azraellab.zapp.core.dev.DevTestResult
import xyz.azraellab.zapp.core.dev.DevTestRunner
import xyz.azraellab.zapp.ui.components.ComponentColors.ButtonRole
import xyz.azraellab.zapp.ui.components.ZappButton
import xyz.azraellab.zapp.ui.components.ZappButtonRow
import xyz.azraellab.zapp.ui.components.ZappCard
import xyz.azraellab.zapp.ui.components.ZappSettingRow
import xyz.azraellab.zapp.ui.theme.AzraelSuccess
import xyz.azraellab.zapp.ui.tr

/**
 * Раздел «Тесты» (Настройки > Для разработчиков).
 *
 * Запускает общий реестр [DevTestRegistry] -- тот же, что гоняет CI через
 * JUnit-обёртку, поэтому «зелёные здесь» и «зелёные в пайплайне» означают
 * одно и то же. Прогон идёт в фоне ([DevTestRunner] живёт на
 * Dispatchers.Default), UI только подписывается на состояние.
 *
 * Лог пишется всегда: основной файл -- после каждого прогона, кнопка
 * «Сохранить лог» -- копию с меткой времени, чтобы предыдущий отчёт не
 * затёрся следующим.
 */
@Composable
fun DevTestsSection() {
    val runner = remember { DevTestRunner() }
    val state by runner.state.collectAsState()
    val scope = rememberCoroutineScope()

    // Все категории выбраны по умолчанию: «Запустить выбранные» без галочек
    // на пустом наборе -- это кнопка, которая ничего не делает.
    var selected by remember { mutableStateOf(DevCategory.entries.toSet()) }
    var savedPath by remember { mutableStateOf<String?>(null) }

    val running = state is DevRunState.Running

    PageGroup(tr(Str.DEV_TESTS)) {
        ZappCard {
            Text(
                text = tr(Str.DEV_TESTS_HINT),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.fillMaxWidth()
            )
            DevCategory.entries.forEach { category ->
                ZappSettingRow(
                    title = "${tr(category.title)} (${DevTestRegistry.countIn(category)})",
                    checked = category in selected,
                    onCheckedChange = { on ->
                        selected = if (on) selected + category else selected - category
                    },
                    path = "devtests.cat.${category.name.lowercase()}"
                )
            }
        }

        ZappCard {
            ZappButtonRow {
                ZappButton(
                    text = tr(Str.DEV_RUN_SELECTED),
                    onClick = {
                        savedPath = null
                        runner.run(DevTestRegistry.inCategories(selected), scope)
                    },
                    enabled = !running && selected.isNotEmpty(),
                    modifier = Modifier.weight(1f),
                    path = "devtests.run_selected"
                )
                ZappButton(
                    text = tr(Str.DEV_RUN_ALL),
                    role = ButtonRole.SECONDARY,
                    onClick = {
                        savedPath = null
                        runner.run(DevTestRegistry.all, scope)
                    },
                    enabled = !running,
                    modifier = Modifier.weight(1f),
                    path = "devtests.run_all"
                )
            }
            StatusBlock(
                state = state,
                onSaveCopy = { done ->
                    savedPath = runner.saveCopy(done)
                }
            )
        }

        (state as? DevRunState.Done)?.let { done ->
            val failed = done.results.filter { !it.ok }
            if (failed.isNotEmpty()) {
                ZappCard {
                    failed.forEach { result ->
                        FailureRow(result)
                    }
                }
            }
        }

        savedPath?.let { path ->
            Text(
                text = "${tr(Str.DEV_LOG_PATH)} $path",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.fillMaxWidth()
            )
        }
    }
}

/** Строка состояния: ожидание, прогресс или сводка прогона. */
@Composable
private fun StatusBlock(
    state: DevRunState,
    onSaveCopy: (DevRunState.Done) -> Unit
) {
    when (state) {
        DevRunState.Idle -> Text(
            text = tr(Str.DEV_IDLE),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        is DevRunState.Running -> Text(
            text = "${tr(Str.DEV_RUNNING)} ${state.done}/${state.total} ${state.currentId}",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.fillMaxWidth()
        )

        is DevRunState.Done -> {
            val failed = state.results.count { !it.ok }
            val passed = state.results.size - failed
            Text(
                text = "${tr(Str.DEV_TOTAL)}: ${state.results.size}   " +
                    "${tr(Str.DEV_OK)}: $passed   " +
                    "${tr(Str.DEV_FAILED)}: $failed   " +
                    "${state.totalMs} ms",
                style = MaterialTheme.typography.bodyMedium,
                color = if (failed == 0) AzraelSuccess else MaterialTheme.colorScheme.error,
                modifier = Modifier.fillMaxWidth()
            )
            if (failed == 0) {
                Text(
                    text = tr(Str.DEV_NO_FAILURES),
                    style = MaterialTheme.typography.bodyMedium,
                    color = AzraelSuccess,
                    modifier = Modifier.fillMaxWidth()
                )
            }
            state.logPath?.let { path ->
                Text(
                    text = "${tr(Str.DEV_LOG_PATH)} $path",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.fillMaxWidth()
                )
            }
            ZappButton(
                text = tr(Str.DEV_SAVE_LOG),
                role = ButtonRole.SECONDARY,
                onClick = { onSaveCopy(state) },
                modifier = Modifier.fillMaxWidth(),
                path = "devtests.save_log"
            )
        }
    }
}

/** Один провал: id теста и причина, цветом ошибки. */
@Composable
private fun FailureRow(result: DevTestResult) {
    Text(
        text = "${result.test.id}: ${result.message}",
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.error,
        modifier = Modifier.fillMaxWidth()
    )
}
