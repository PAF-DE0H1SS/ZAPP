package xyz.azraellab.zapp.core.deeplink

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Deep-link: разбор схемы zapp://, действий и кодирования параметров. */
class DeepLinkTest {

    @Test
    fun parsesImportWithEncodedUrl() {
        val link = parseDeepLink("zapp://import?url=https%3A%2F%2Fexample.com%2Fc.conf")
        assertEquals(DeepLink.Import("https://example.com/c.conf"), link)
    }

    @Test
    fun parsesConnectByProfile() {
        val link = parseDeepLink("zapp://connect?profile=MyNet")
        assertEquals(DeepLink.Connect("MyNet"), link)
    }

    @Test
    fun plusDecodesToSpace() {
        val link = parseDeepLink("zapp://connect?profile=My+Net")
        assertEquals(DeepLink.Connect("My Net"), link)
    }

    @Test
    fun schemeIsCaseInsensitive() {
        val link = parseDeepLink("ZAPP://connect?profile=a")
        assertEquals(DeepLink.Connect("a"), link)
    }

    @Test
    fun unknownActionRejected() {
        assertNull(parseDeepLink("zapp://drop?profile=a"))
    }

    @Test
    fun wrongSchemeRejected() {
        assertNull(parseDeepLink("https://import?url=x"))
    }

    @Test
    fun missingParamRejected() {
        assertNull(parseDeepLink("zapp://import"))
        assertNull(parseDeepLink("zapp://connect?profile="))
        assertNull(parseDeepLink("zapp://import?url="))
    }

    @Test
    fun barePairWithoutValueIgnored() {
        // `?flag` без `=` не должен ронять разбор остальных параметров
        val link = parseDeepLink("zapp://connect?flag&profile=b")
        assertEquals(DeepLink.Connect("b"), link)
    }

    @Test
    fun malformedPercentLeftAsIs() {
        val link = parseDeepLink("zapp://connect?profile=100%zz")
        assertEquals(DeepLink.Connect("100%zz"), link)
    }

    @Test
    fun emptyStringRejected() {
        assertNull(parseDeepLink(""))
        assertTrue(parseDeepLink("   ") == null)
    }
}
