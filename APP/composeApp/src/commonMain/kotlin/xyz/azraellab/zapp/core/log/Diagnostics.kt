package xyz.azraellab.zapp.core.log

import xyz.azraellab.zapp.core.backup.zipPack

/**
 * Значения секретных полей конфига -- под маску.
 *
 * Диагностику собирают в zip и показывают где угодно, поэтому ключи,
 * пароли и токены не должны выезжать наружу. Маскируется значение
 * любого поля, чьё имя читается как секрет; длинные base64-плёнки
 * (ключи Reality и т.п.) дополнительно ловит [redactSensitive].
 */
private val SECRET_FIELD = Regex(
    """("[^"]*(?:privateKey|presharedKey|certPassword|password|passwd|token|license|secret)[^"]*"\s*:\s*)"[^"]*"""",
    RegexOption.IGNORE_CASE
)

/** Убирает значения секретных полей из JSON-текста конфига. */
fun sanitizeConfig(text: String): String =
    SECRET_FIELD.replace(text) { it.groupValues[1] + "\"***\"" }

/**
 * Собирает содержимое отчёта о проблеме: версия, журнал, ошибки, конфиг.
 *
 * Каждая строка журнала проходит через [redactSensitive], конфиг -- через
 * [sanitizeConfig]: отчёт -- это то, что уходит в багрепорт, и секреты
 * не должны попадать туда даже в фоне.
 */
fun buildDiagnostics(
    logs: List<String>,
    errors: List<String>,
    version: String,
    configJson: String
): List<Pair<String, String>> = listOf(
    "version.txt" to version,
    "app.log" to logs.joinToString("\n") { redactSensitive(it) },
    "errors.log" to errors.joinToString("\n") { redactSensitive(it) },
    "config.json" to sanitizeConfig(configJson)
)

/** Пакует отчёт в zip одной кнопкой. */
fun diagnosticsZip(
    logs: List<String>,
    errors: List<String>,
    version: String,
    configJson: String
): ByteArray = zipPack(buildDiagnostics(logs, errors, version, configJson))
