package xyz.azraellab.zapp.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Список коннектов на уровне конфига: видимость, архив, выбор.
 *
 * То, что решает, что пользователь видит на VPN-вкладке: мёртвые в
 * архив, мосты Tor -- никогда (они живут на своей вкладке), активный
 * профиль -- ровно тот, что выбран. Отдельно от экрана, потому что
 * экран лишь вызывает эти функции.
 */
class VpnConfigTest {

    private fun profile(
        id: String,
        alive: Boolean? = null,
        protocol: String = VpnProtocol.WIREGUARD.code,
        sourceId: String = ""
    ): VpnProfile = VpnProfile(
        id = id,
        name = id,
        protocol = protocol,
        sourceId = sourceId,
        health = LinkHealth(alive = alive)
    )

    // --- Видимость ---

    @Test
    fun visibleKeepsAliveAndUncheckedDropsArchived() {
        val config = VpnConfig(
            profiles = listOf(
                profile("alive", alive = true),
                profile("unchecked"),
                profile("dead", alive = false)
            )
        )
        assertEquals(listOf("alive", "unchecked"), config.visibleProfiles().map { it.id })
    }

    @Test
    fun visibleNeverShowsTorBridges() {
        // Мосты -- тема вкладки Tor, в список VPN не попадают никогда,
        // независимо от исторического флага torEnabled. Самие профили
        // при этом не удаляются: вкладка Tor их показывает.
        for (torFlag in listOf(false, true)) {
            val config = VpnConfig(
                profiles = listOf(
                    profile("wg", alive = true),
                    profile("bridge", alive = true, protocol = VpnProtocol.TOR.code),
                    profile("dead-bridge", alive = false, protocol = VpnProtocol.TOR.code)
                ),
                torEnabled = torFlag
            )
            assertEquals(listOf("wg"), config.visibleProfiles().map { it.id }, "torEnabled=$torFlag")
            assertEquals(
                listOf("wg", "bridge", "dead-bridge"),
                config.profiles.map { it.id },
                "сами профили не трогаются"
            )
            // Архив тоже чисто VPN: мёртвые мосты остаются на своей вкладке.
            assertEquals(emptyList(), config.archivedProfiles().map { it.id })
        }
    }

    // --- Архив ---

    @Test
    fun archiveKeepsOnlyConfirmedDead() {
        val config = VpnConfig(
            profiles = listOf(
                profile("dead", alive = false),
                profile("alive", alive = true),
                profile("unchecked")
            )
        )
        assertEquals(listOf("dead"), config.archivedProfiles().map { it.id })
    }

    // --- Выбор ---

    @Test
    fun activeProfileIsResolvedById() {
        val config = VpnConfig(
            activeProfileId = "b",
            profiles = listOf(profile("a"), profile("b"))
        )
        assertEquals("b", config.activeProfile()?.id)
        assertEquals("a", config.profileById("a")?.id)
    }

    @Test
    fun unknownActiveProfileIsNull() {
        // Пустой или удалённый id: экран не должен падать, а должен
        // показать «коннект не выбран».
        val config = VpnConfig(
            activeProfileId = "gone",
            profiles = listOf(profile("a"))
        )
        assertNull(config.activeProfile())
        assertNull(config.profileById("gone"))
    }

    // --- Источники ---

    @Test
    fun profilesOfSourceFiltersBySource() {
        val config = VpnConfig(
            profiles = listOf(
                profile("s1-1", sourceId = "s1"),
                profile("s1-2", sourceId = "s1"),
                profile("s2-1", sourceId = "s2"),
                profile("manual")
            )
        )
        assertEquals(listOf("s1-1", "s1-2"), config.profilesOfSource("s1").map { it.id })
        assertTrue(config.profilesOfSource("unknown").isEmpty())
    }
}
