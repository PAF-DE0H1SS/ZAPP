package xyz.azraellab.zapp.core.update

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Объявления разработчика: разбор блока в теле релиза, ID содержимого,
 * правило видимости. Всё чистое -- фид и настройки не нужны.
 */
class NoticeParseTest {

    private fun body(block: String) = "Краткий changelog.\n$block"

    @Test
    fun parsesFullBlock() {
        val notice = parseNotice(body("""
            <!-- zapp-notice
            title: Серверы переехали
            expires: 2026-11-01
            url: https://t.me/zapp
            Новые адреса узлов.
            Старые работают до конца месяца.
            -->
        """.trimIndent()))
        assertNotNull(notice)
        assertEquals("Серверы переехали", notice.title)
        assertEquals("Новые адреса узлов.\nСтарые работают до конца месяца.", notice.content)
        assertEquals("https://t.me/zapp", notice.url)
        // Конец 2026-11-01: сутки после дня 20758 от эпохи.
        assertEquals(1_793_577_600_000L, notice.expiresMs)
        assertTrue(notice.id.isNotBlank())
    }

    @Test
    fun plainChangelogHasNoNotice() {
        assertNull(parseNotice("Исправлен обход и бэкап."))
        // Обрыв блока без закрывающей метки -- тоже не объявление.
        assertNull(parseNotice("text\n<!-- zapp-notice\ntitle: X\n"))
    }

    @Test
    fun titleIsRequired() {
        assertNull(parseNotice(body("<!-- zapp-notice\nПросто текст.\n-->")))
        assertNull(parseNotice(body("<!-- zapp-notice\ntitle:   \nТекст.\n-->")))
    }

    @Test
    fun badExpiryDropsWholeNotice() {
        // Битая дата гасит всё: висеть вечно под видом срочного -- хуже,
        // чем не показать.
        assertNull(parseNotice(body("<!-- zapp-notice\ntitle: X\nexpires: 2026-13-01\n-->")))
        assertNull(parseNotice(body("<!-- zapp-notice\ntitle: X\nexpires: вчера\n-->")))
    }

    @Test
    fun badUrlKeepsNoticeWithoutLink() {
        val notice = parseNotice(body("<!-- zapp-notice\ntitle: X\nurl: ftp://host/x\nТекст.\n-->"))
        assertNotNull(notice)
        assertEquals("", notice.url)
        assertEquals("X", notice.title)
    }

    @Test
    fun idDependsOnTextOnly() {
        val a = parseNotice(body("<!-- zapp-notice\ntitle: X\nТекст.\n-->"))
        val b = parseNotice(body("<!-- zapp-notice\ntitle: X\nТекст!\n-->"))
        assertNotNull(a); assertNotNull(b)
        // Та же формулировка в следующем релизе -- то же объявление
        // (уже закрытое не всплывает); любое отличие -- новое.
        assertEquals(a.id, noticeId("X", "Текст."))
        assertNotEquals(a.id, b.id)
    }

    @Test
    fun visibleUntilDismissedOrExpired() {
        val expires = 1_793_577_600_000L
        val notice = NoticeInfo(id = "id1", title = "X", expiresMs = expires)
        assertTrue(noticeVisible(notice, expires - 1, dismissedId = ""))
        assertFalse(noticeVisible(notice, expires - 1, dismissedId = "id1"))
        // Последний день включительно: срока хватает ровно до конца даты.
        assertFalse(noticeVisible(notice, expires, dismissedId = ""))
        val forever = NoticeInfo(id = "id2", title = "X")
        assertTrue(noticeVisible(forever, Long.MAX_VALUE / 2, dismissedId = ""))
    }

    @Test
    fun toleratesCrlfBlock() {
        // Релизные тела пишутся с любым переводом строк -- \r не должен
        // превращать заголовок в нераспознанный.
        val notice = parseNotice(
            "<!-- zapp-notice\r\ntitle: X\r\nТекст.\r\n-->"
        )
        assertNotNull(notice)
        assertEquals("X", notice.title)
        assertEquals("Текст.", notice.content)
    }
}
