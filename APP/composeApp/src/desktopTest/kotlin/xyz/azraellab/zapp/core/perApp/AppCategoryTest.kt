package xyz.azraellab.zapp.core.perApp

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class AppCategoryTest {

    private fun app(pkg: String, category: AppCategory) =
        InstalledApp(pkg, pkg, uid = 1001, isSystem = false, category = category)

    @Test
    fun platformCategoryIsNeverOverridden() {
        // Размеченному приложению мы не указываем: свой список префиксов
        // работает только когда платформа промолчала.
        assertEquals(AppCategory.GAME, refineCategory("org.telegram.messenger", AppCategory.GAME))
        assertEquals(AppCategory.SOCIAL, refineCategory("ru.sberbank", AppCategory.SOCIAL))
        assertEquals(AppCategory.NEWS, refineCategory("com.whatsapp", AppCategory.NEWS))
    }

    @Test
    fun unknownBaseFallsBackToPrefixLists() {
        assertEquals(
            AppCategory.MESSENGER,
            refineCategory("org.telegram.messenger", AppCategory.OTHER)
        )
        assertEquals(
            AppCategory.MESSENGER,
            refineCategory("com.facebook.orca", AppCategory.OTHER)
        )
        assertEquals(AppCategory.FINANCE, refineCategory("ru.tinkoff.mobile", AppCategory.OTHER))
        assertEquals(AppCategory.FINANCE, refineCategory("com.idamob.tinkoff", AppCategory.OTHER))
    }

    @Test
    fun unknownStaysOther() {
        assertEquals(AppCategory.OTHER, refineCategory("com.example.reader", AppCategory.OTHER))
        // Регистр не должен ломать совпадение префикса.
        assertEquals(AppCategory.MESSENGER, refineCategory("ORG.Telegram.messenger", AppCategory.OTHER))
        // Похожий, но не наш префикс: com.sberbank -- не ru.sberbank.
        assertEquals(AppCategory.OTHER, refineCategory("com.sberbank.chat", AppCategory.OTHER))
    }

    @Test
    fun groupsAreOrderedAndDropEmptyOnes() {
        val apps = listOf(
            app("com.a", AppCategory.OTHER),
            app("com.b", AppCategory.MESSENGER),
            app("com.c", AppCategory.GAME),
            app("com.d", AppCategory.MESSENGER)
        )
        val groups = groupByCategory(apps)
        assertEquals(
            listOf(AppCategory.MESSENGER, AppCategory.GAME, AppCategory.OTHER),
            groups.map { it.first }
        )
        assertEquals(listOf("com.b", "com.d"), groups[0].second.map { it.packageName })
        // Прочие замыкают список: сначала содержательное.
        assertEquals("com.a", groups[2].second.single().packageName)
    }

    @Test
    fun singleCategoryGroupSignalsNothingToGroup() {
        // Десктоп: все процессы OTHER -- одна группа, вызывающая сторона
        // по размеру оставляет плоский список (дерево не рвётся).
        val apps = listOf(app("pid:1", AppCategory.OTHER), app("pid:2", AppCategory.OTHER))
        assertEquals(1, groupByCategory(apps).size)
        assertNull(
            groupByCategory(apps).takeIf { it.size > 1 },
            "однородный каталог не должен группироваться"
        )
        assertEquals(0, groupByCategory(emptyList()).size)
    }

    @Test
    fun toggleSelectsMissingOnly() {
        // Частично отмеченная группа: тап доводит до конца, не трогая
        // чужие пакеты и не создавая дублей.
        assertEquals(
            listOf("x", "c", "a", "b"),
            toggleCategory(listOf("x", "c"), listOf("a", "b", "c"))
        )
    }

    @Test
    fun toggleDeselectsWhenAllPicked() {
        assertEquals(
            listOf("x"),
            toggleCategory(listOf("x", "a", "b"), listOf("a", "b"))
        )
    }

    @Test
    fun toggleOnEmptySelectionAddsAll() {
        assertEquals(listOf("a", "b"), toggleCategory(emptyList(), listOf("a", "b")))
        // Пустая группа ничего не делает.
        assertEquals(listOf("x"), toggleCategory(listOf("x"), emptyList()))
    }
}
