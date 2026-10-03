package xyz.azraellab.zapp.core.update

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Сравнение версий обновлений.
 *
 * Главная опасность здесь -- строковое сравнение: `0.10.0` обязано быть
 * новее `0.9.0`, хотя по строке наоборот. Теги с `v` и без должны
 * считаться одинаковыми, а битый тег -- не ронять проверку.
 */
class VersionCompareTest {

    @Test
    fun `числовой порядок важнее строкового`() {
        assertTrue(isNewerVersion("0.10.0", "0.9.0"))
        assertTrue(isNewerVersion("1.0.0", "0.99.99"))
        assertTrue(isNewerVersion("2.0", "1.999"))
    }

    @Test
    fun `префикс v не меняет смысл`() {
        assertTrue(isNewerVersion("v0.2.0", "0.1.0"))
        assertTrue(isNewerVersion("0.2.0", "v0.1.0"))
        assertFalse(isNewerVersion("v0.1.0", "0.1.0"))
    }

    @Test
    fun `одинаковые и старые версии не считаются обновлением`() {
        assertFalse(isNewerVersion("0.1.0", "0.1.0"))
        assertFalse(isNewerVersion("0.1.0", "0.2.0"))
        assertFalse(isNewerVersion("0.0.9", "0.1"))
    }

    @Test
    fun `битые теги не дают ложного обновления`() {
        assertFalse(isNewerVersion("", "0.1.0"))
        assertFalse(isNewerVersion("0.1.0", ""))
        // Пререлиз `rc` -- не число, считается нулём и не обгоняет релиз.
        assertFalse(isNewerVersion("0.1.0-rc.1", "0.1.0"))
    }

    @Test
    fun `разная длина частей сравнивается корректно`() {
        assertTrue(isNewerVersion("1.0.0.1", "1.0.0"))
        assertFalse(isNewerVersion("1.0.0", "1.0.0.1"))
    }
}
