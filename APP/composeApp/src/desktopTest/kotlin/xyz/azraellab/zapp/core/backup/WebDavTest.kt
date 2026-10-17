package xyz.azraellab.zapp.core.backup

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking

/** WebDAV: адрес, авторизация, загрузка и скачивание. */
class WebDavTest {

    @Test
    fun fileUrlJoinsWithSlash() {
        val cfg = WebDavConfig(url = "https://dav.example.com/zapp/", login = "u")
        assertEquals("https://dav.example.com/zapp/zapp-backup.zip", cfg.fileUrl)
    }

    @Test
    fun fileUrlWithoutTrailingSlash() {
        val cfg = WebDavConfig(url = "https://dav.example.com/zapp", login = "u")
        assertEquals("https://dav.example.com/zapp/zapp-backup.zip", cfg.fileUrl)
    }

    @Test
    fun readyNeedsUrlAndLogin() {
        assertTrue(WebDavConfig("https://x", "u").ready)
        assertFalse(WebDavConfig("", "u").ready)
        assertFalse(WebDavConfig("https://x", "").ready)
    }

    @Test
    fun authHeaderIsBasic() {
        // "user:pass" -> dXNlcjpwYXNz
        assertEquals("Basic dXNlcjpwYXNz", webDavAuth("user", "pass"))
    }

    @Test
    fun uploadPassesFileUrlAndAuth() = runBlocking {
        var seenUrl = ""
        var seenAuth = ""
        val client = WebDavClient(
            put = { url, auth, _ -> seenUrl = url; seenAuth = auth; true },
            get = { _, _ -> null }
        )
        val ok = client.upload(WebDavConfig("https://dav/x", "u", "p"), byteArrayOf(1, 2))
        assertTrue(ok)
        assertEquals("https://dav/x/zapp-backup.zip", seenUrl)
        assertEquals(webDavAuth("u", "p"), seenAuth)
    }

    @Test
    fun uploadFailsWhenServerRefuses() = runBlocking {
        val client = WebDavClient(put = { _, _, _ -> false }, get = { _, _ -> null })
        assertFalse(client.upload(WebDavConfig("https://dav", "u", "p"), byteArrayOf()))
    }

    @Test
    fun uploadBlockedWithoutConfig() = runBlocking {
        var called = false
        val client = WebDavClient(put = { _, _, _ -> called = true; true }, get = { _, _ -> null })
        assertFalse(client.upload(WebDavConfig(), byteArrayOf()))
        assertFalse(called)
    }

    @Test
    fun downloadReturnsBytes() = runBlocking {
        val client = WebDavClient(
            put = { _, _, _ -> true },
            get = { _, _ -> "zip".encodeToByteArray() }
        )
        val bytes = client.download(WebDavConfig("https://dav", "u", "p"))
        assertEquals("zip", bytes?.decodeToString())
    }

    @Test
    fun downloadNullWhenServerDown() = runBlocking {
        val client = WebDavClient(put = { _, _, _ -> true }, get = { _, _ -> null })
        assertNull(client.download(WebDavConfig("https://dav", "u", "p")))
    }

    @Test
    fun downloadBlockedWithoutConfig() = runBlocking {
        var called = false
        val client = WebDavClient(put = { _, _, _ -> true }, get = { _, _ -> called = true; null })
        assertNull(client.download(WebDavConfig()))
        assertFalse(called)
    }
}
