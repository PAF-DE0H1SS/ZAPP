package xyz.azraellab.zapp.core.log

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import xyz.azraellab.zapp.core.backup.zipUnpack

/** Диагностика: санитизация конфига, сборка отчёта, zip. */
class DiagnosticsTest {

    @Test
    fun configSecretsAreMasked() {
        val config = """{"privateKey":"SECRETKEY","name":"home","password":"hunter2"}"""
        val clean = sanitizeConfig(config)
        assertFalse(clean.contains("SECRETKEY"))
        assertFalse(clean.contains("hunter2"))
        assertTrue(clean.contains("\"name\":\"home\""))
    }

    @Test
    fun configKeepsOrdinaryFields() {
        val config = """{"gateway":"1.2.3.4","gatewayPort":51820}"""
        assertEquals(config, sanitizeConfig(config))
    }

    @Test
    fun reportHasExpectedFiles() {
        val entries = buildDiagnostics(
            logs = listOf("ok"),
            errors = listOf("boom"),
            version = "2.7.26",
            configJson = "{}"
        ).toMap()
        assertEquals("2.7.26", entries["version.txt"])
        assertTrue(entries.containsKey("app.log"))
        assertTrue(entries.containsKey("errors.log"))
        assertTrue(entries.containsKey("config.json"))
    }

    @Test
    fun logsAreRedacted() {
        // base64-плёнка 44 символа (приватный ключ) не должна выезжать
        val long = "A".repeat(44)
        val entries = buildDiagnostics(
            logs = listOf("key=$long"),
            errors = emptyList(),
            version = "1",
            configJson = "{}"
        ).toMap()
        assertFalse(entries.getValue("app.log").contains(long))
    }

    @Test
    fun zipRoundTrips() {
        val zip = diagnosticsZip(
            logs = listOf("hello"),
            errors = listOf("oops"),
            version = "9.9.9",
            configJson = """{"privateKey":"SEC"}"""
        )
        val unpacked = zipUnpack(zip)
        assertTrue(unpacked != null)
        assertEquals("9.9.9", unpacked["version.txt"])
        assertTrue(unpacked.getValue("app.log").contains("hello"))
        assertFalse(unpacked.getValue("config.json").contains("SEC"))
    }
}
