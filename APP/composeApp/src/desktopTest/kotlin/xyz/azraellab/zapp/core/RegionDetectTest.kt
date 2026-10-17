package xyz.azraellab.zapp.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class RegionDetectTest {

    @Test
    fun readsKnownCountryCode() {
        val body = """{"ip":"1.2.3.4","success":true,"country":"Russia","country_code":"RU"}"""
        assertEquals("RU", parseCountryCode(body))
    }

    @Test
    fun lowercasesAndTrimsAreNormalized() {
        val body = """{"success":true,"country_code":" ru "}"""
        assertEquals("RU", parseCountryCode(body))
    }

    @Test
    fun serviceRefusalYieldsNull() {
        assertNull(parseCountryCode("""{"success":false,"message":"Invalid IP address"}"""))
    }

    @Test
    fun unknownCountryIsRejected() {
        // Код вне реестра превратился бы в пустой пункт dropdown.
        assertNull(parseCountryCode("""{"success":true,"country_code":"XX"}"""))
    }

    @Test
    fun malformedCodeYieldsNull() {
        assertNull(parseCountryCode("""{"success":true,"country_code":"RUS"}"""))
        assertNull(parseCountryCode("""{"success":true,"country_code":""}"""))
        assertNull(parseCountryCode("""{"success":true}"""))
    }

    @Test
    fun garbageBodyYieldsNull() {
        assertNull(parseCountryCode("not json at all"))
        assertNull(parseCountryCode(""))
        assertNull(parseCountryCode("[]"))
    }

    @Test
    fun nonStringPrimitiveIsRejected() {
        // Поле-объект вместо строки не должен ронять разбор.
        assertNull(parseCountryCode("""{"success":true,"country_code":{"code":"RU"}}"""))
    }
}
