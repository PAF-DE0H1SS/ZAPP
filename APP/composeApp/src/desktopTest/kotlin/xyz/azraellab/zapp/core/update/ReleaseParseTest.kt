package xyz.azraellab.zapp.core.update

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Обновления: разбор релиза, changelog, анти-откат, полнота ассетов. */
class ReleaseParseTest {

    private val releaseJson = """
        {
          "tag_name": "v2.8.0",
          "html_url": "https://github.com/x/zapp/releases/tag/v2.8.0",
          "name": "Новое",
          "published_at": "2026-10-17T00:00:00Z",
          "body": "Исправлен обход и бэкап.",
          "assets": [
            {"browser_download_url": "https://x/app-release.apk"},
            {"browser_download_url": "https://x/app.msi"}
          ]
        }
    """.trimIndent()

    @Test
    fun parsesReleaseWithChangelogAndApk() {
        val release = parseRelease(releaseJson)
        assertNotNull(release)
        assertEquals("v2.8.0", release.version)
        assertEquals("https://github.com/x/zapp/releases/tag/v2.8.0", release.url)
        assertEquals("Исправлен обход и бэкап.", release.changelog)
        assertEquals("https://x/app-release.apk", release.apkUrl)
    }

    @Test
    fun missingBodyMeansEmptyChangelog() {
        val release = parseRelease("""{"tag_name":"v1","html_url":"https://x"}""")
        assertNotNull(release)
        assertEquals("", release.changelog)
    }

    @Test
    fun blankTagRejected() {
        assertNull(parseRelease("""{"tag_name":"","html_url":"https://x"}"""))
    }

    @Test
    fun blankUrlRejected() {
        assertNull(parseRelease("""{"tag_name":"v1","html_url":""}"""))
    }

    @Test
    fun nonJsonRejected() {
        assertNull(parseRelease("<html>blocked</html>"))
    }

    @Test
    fun noApkMeansBlankApkUrl() {
        val release = parseRelease("""{"tag_name":"v1","html_url":"https://x","assets":[]}""")
        assertNotNull(release)
        assertEquals("", release.apkUrl)
    }

    @Test
    fun newerReleaseIsOk() {
        val release = parseRelease(releaseJson)!!
        assertEquals(ReleaseCheck.OK, checkRelease(release, "2.7.0"))
    }

    @Test
    fun olderReleaseIsRollback() {
        val release = parseRelease(releaseJson)!!
        assertEquals(ReleaseCheck.ROLLBACK, checkRelease(release, "2.9.0"))
    }

    @Test
    fun sameReleaseIsRollback() {
        val release = parseRelease(releaseJson)!!
        assertEquals(ReleaseCheck.ROLLBACK, checkRelease(release, "2.8.0"))
    }

    @Test
    fun releaseWithoutApkIsNoApk() {
        val release = parseRelease("""{"tag_name":"v9","html_url":"https://x"}""")!!
        assertEquals(ReleaseCheck.NO_APK, checkRelease(release, "2.7.0"))
    }

    @Test
    fun tagCompareModeSeesNonNumericUpdate() {
        // нечисловой тег семвер не увидит, но «по смене тега» -- заметит
        val json = """
            {"tag_name":"beta-next","html_url":"https://x",
             "assets":[{"browser_download_url":"https://x/a.apk"}]}
        """.trimIndent()
        val release = parseRelease(json)!!
        assertTrue(updateAvailable(release.version, "2.7.0", UPDATE_COMPARE_TAG))
    }
}
