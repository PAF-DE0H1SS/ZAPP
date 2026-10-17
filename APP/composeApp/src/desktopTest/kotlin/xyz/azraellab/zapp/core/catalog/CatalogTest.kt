package xyz.azraellab.zapp.core.catalog

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Каталог: разбор фида, сравнение версий, отказ чужого фида. */
class CatalogTest {

    private val feedJson = """
        {
          "feedVersion": "1.2",
          "entries": [
            {
              "id": "zapret-ru",
              "name": "Обход RU",
              "description": "Стратегия для России",
              "version": "2.0",
              "changelogUrl": "https://x/ru.md",
              "presetUrl": "https://x/ru.json"
            },
            {
              "id": "zapret-min",
              "name": "Минимум",
              "version": "1.0",
              "presetUrl": "https://x/min.json"
            }
          ]
        }
    """.trimIndent()

    @Test
    fun parsesFeedEntries() {
        val feed = CatalogService.parseFeed(feedJson)
        assertNotNull(feed)
        assertEquals("1.2", feed.feedVersion)
        assertEquals(2, feed.entries.size)
        assertEquals("Обход RU", feed.entries[0].name)
        assertEquals("https://x/ru.json", feed.entries[0].presetUrl)
        assertEquals("Минимум", feed.entries[1].name)
    }

    @Test
    fun emptyEntryDefaultsAreSafe() {
        val feed = CatalogService.parseFeed("""{"entries":[{"id":"a"}]}""")
        assertNotNull(feed)
        val entry = feed.entries.single()
        assertEquals("", entry.name)
        assertEquals("", entry.presetUrl)
        assertEquals("", entry.version)
    }

    @Test
    fun garbageRejected() {
        assertNull(CatalogService.parseFeed("not json"))
    }

    @Test
    fun emptyObjectYieldsEmptyFeed() {
        val feed = CatalogService.parseFeed("{}")
        assertNotNull(feed)
        assertTrue(feed.entries.isEmpty())
    }

    @Test
    fun unknownKeysIgnored() {
        val feed = CatalogService.parseFeed("""{"entries":[{"id":"a","extra":1}],"zzz":true}""")
        assertNotNull(feed)
        assertEquals("a", feed.entries.single().id)
    }

    @Test
    fun newerVersionDetected() {
        val entry = CatalogEntry(version = "2.1")
        assertTrue(CatalogService.hasUpdate(entry, "2.0"))
    }

    @Test
    fun sameVersionNoUpdate() {
        val entry = CatalogEntry(version = "2.0")
        assertFalse(CatalogService.hasUpdate(entry, "2.0"))
    }

    @Test
    fun olderVersionNoUpdate() {
        val entry = CatalogEntry(version = "1.0")
        assertFalse(CatalogService.hasUpdate(entry, "2.0"))
    }

    @Test
    fun blankVersionsNoUpdate() {
        assertFalse(CatalogService.hasUpdate(CatalogEntry(version = ""), "2.0"))
        assertFalse(CatalogService.hasUpdate(CatalogEntry(version = "2.0"), ""))
    }
}
