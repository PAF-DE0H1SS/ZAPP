package xyz.azraellab.zapp.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Список коннектов: сортировка и фильтр.
 *
 * Это логика, которую видит пользователь при каждом открытии VPN-вкладки,
 * поэтому проверяется здесь, а не экраном: живые впереди, битые в хвост,
 * поиск не зависит от регистра.
 */
class VpnConnectsTest {

    private fun profile(
        name: String,
        alive: Boolean? = null,
        latencyMs: Int = -1,
        protocol: String = VpnProtocol.WIREGUARD.code
    ): VpnProfile = VpnProfile(
        id = name,
        name = name,
        protocol = protocol,
        health = LinkHealth(alive = alive, latencyMs = latencyMs)
    )

    // --- Сортировка по пингу ---

    @Test
    fun aliveComeBeforeUncheckedAndDead() {
        val list = listOf(
            profile("dead", alive = false, latencyMs = 5),
            profile("unchecked"),
            profile("alive", alive = true, latencyMs = 90)
        )
        val sorted = VpnConnects.sort(list, byPing = true)
        // Живой -- первый. Далее «не живые»: у мёртвого есть измеренный
        // пинг, у непроверенного его нет, поэтому мёртвый идёт впереди.
        assertEquals(listOf("alive", "dead", "unchecked"), sorted.map { it.name })
    }

    @Test
    fun aliveAreOrderedByLatencyAscending() {
        val list = listOf(
            profile("slow", alive = true, latencyMs = 200),
            profile("fast", alive = true, latencyMs = 10),
            profile("mid", alive = true, latencyMs = 50)
        )
        val sorted = VpnConnects.sort(list, byPing = true)
        assertEquals(listOf("fast", "mid", "slow"), sorted.map { it.name })
    }

    @Test
    fun aliveWithoutLatencyGoAfterMeasuredOnes() {
        val list = listOf(
            profile("measured", alive = true, latencyMs = 300),
            profile("alive-no-ping", alive = true, latencyMs = -1),
            profile("other", alive = true, latencyMs = 100)
        )
        val sorted = VpnConnects.sort(list, byPing = true)
        assertEquals(listOf("other", "measured", "alive-no-ping"), sorted.map { it.name })
    }

    @Test
    fun deadAndUncheckedAreOrderedByLatencyThenName() {
        // Оба класса (не живые) различаются только пингом и именем:
        // битый с измеренным временем не прыгает выше живого без пинга.
        val list = listOf(
            profile("dead-b", alive = false, latencyMs = 40),
            profile("unchecked-a"),
            profile("dead-a", alive = false, latencyMs = 40)
        )
        val sorted = VpnConnects.sort(list, byPing = true)
        assertEquals(listOf("dead-a", "dead-b", "unchecked-a"), sorted.map { it.name })
    }

    @Test
    fun sortByNameIgnoresAlive() {
        val list = listOf(
            profile("bravo", alive = true, latencyMs = 1),
            profile("alpha", alive = false),
            profile("charlie")
        )
        val sorted = VpnConnects.sort(list, byPing = false)
        assertEquals(listOf("alpha", "bravo", "charlie"), sorted.map { it.name })
    }

    @Test
    fun sameNameSortsAliveFirstAndKeepsIdentity() {
        // Равные имена не теряют различий: живой поднимается выше,
        // объекты не пересоздаются и порядок равных стабилен.
        val dead = profile("same", alive = false, latencyMs = -1)
        val alive = profile("same", alive = true, latencyMs = -1)
        val sorted = VpnConnects.sort(listOf(dead, alive), byPing = true)
        assertEquals(listOf(alive, dead), sorted)
    }

    // --- Фильтр ---

    @Test
    fun blankQueryAndNoAliveFilterReturnsSameList() {
        val list = listOf(profile("one"), profile("two", alive = true))
        val filtered = VpnConnects.filter(list, onlyAlive = false, query = "")
        assertTrue(filtered === list, "без фильтров список не должен копироваться")
    }

    @Test
    fun onlyAliveKeepsConfirmedAliveOnly() {
        val list = listOf(
            profile("alive", alive = true),
            profile("dead", alive = false),
            profile("unchecked")
        )
        val filtered = VpnConnects.filter(list, onlyAlive = true, query = "")
        assertEquals(listOf("alive"), filtered.map { it.name })
    }

    @Test
    fun queryMatchesNameIgnoringCase() {
        val list = listOf(
            profile("Sweden Exit"),
            profile("moscow-1"),
            profile("Tokyo")
        )
        val filtered = VpnConnects.filter(list, onlyAlive = false, query = "sWeDeN")
        assertEquals(listOf("Sweden Exit"), filtered.map { it.name })
    }

    @Test
    fun queryAndAliveFilterCombineWithAnd() {
        val list = listOf(
            profile("Sweden alive", alive = true),
            profile("Sweden dead", alive = false),
            profile("Berlin alive", alive = true)
        )
        val filtered = VpnConnects.filter(list, onlyAlive = true, query = "sweden")
        assertEquals(listOf("Sweden alive"), filtered.map { it.name })
    }

    @Test
    fun queryWithNoMatchesGivesEmpty() {
        val filtered = VpnConnects.filter(listOf(profile("alpha")), false, "omega")
        assertTrue(filtered.isEmpty())
    }

    // --- Кнопка «Показать ещё» ---

    @Test
    fun hasMoreOnlyWhenHiddenRowsRemain() {
        val list = List(5) { profile("p$it") }
        assertTrue(VpnConnects.hasMore(list, limit = 3))
        assertFalse(VpnConnects.hasMore(list, limit = 5))
        assertFalse(VpnConnects.hasMore(list, limit = 50))
        assertFalse(VpnConnects.hasMore(emptyList(), limit = 0))
    }

    // --- Мосты Tor: вкладка Tor видит только их ---

    @Test
    fun torBridgesKeepOnlyTorProtocol() {
        // Смешение протоколов в одном списке ввело бы в заблуждение:
        // выбрать «мост» и подключиться через wireguard -- не результат.
        val list = listOf(
            profile("bridge", protocol = VpnProtocol.TOR.code),
            profile("wg", protocol = VpnProtocol.WIREGUARD.code),
            profile("vless", protocol = VpnProtocol.VLESS.code),
            profile("obfs-tor", protocol = VpnProtocol.TOR.code)
        )
        assertEquals(listOf("bridge", "obfs-tor"), VpnConnects.torBridges(list).map { it.name })
    }

    @Test
    fun torBridgesKeepDeadButDropOthers() {
        // Мёртвые мосты остались на вкладке Tor после того, как архив
        // VPN стал чисто VPN-списком: связь «проверили и отказал» не
        // должна пропасть -- она видна в подписи источника. Непроверенные
        // остаются тоже: их ещё никто не мерял.
        val list = listOf(
            profile("alive", alive = true, protocol = VpnProtocol.TOR.code),
            profile("dead", alive = false, protocol = VpnProtocol.TOR.code),
            profile("unchecked", protocol = VpnProtocol.TOR.code),
            profile("wg", alive = false, protocol = VpnProtocol.WIREGUARD.code)
        )
        assertEquals(listOf("alive", "dead", "unchecked"), VpnConnects.torBridges(list).map { it.name })
    }

    @Test
    fun torBridgesEmptyWhenNoTorProfiles() {
        assertTrue(VpnConnects.torBridges(emptyList()).isEmpty())
        assertTrue(
            VpnConnects.torBridges(listOf(profile("wg"))).isEmpty()
        )
    }

    @Test
    fun torBridgesPreserveOrderForSortInput() {
        // torBridges -- только отбор, порядок задаёт sort: на входе
        // фиксированный порядок, на выходе тот же самый.
        val list = listOf(
            profile("z", protocol = VpnProtocol.TOR.code),
            profile("a", protocol = VpnProtocol.TOR.code),
            profile("m", protocol = VpnProtocol.TOR.code)
        )
        assertEquals(listOf("z", "a", "m"), VpnConnects.torBridges(list).map { it.name })
    }

    // --- Источники: разделы не смешивают подписки ---

    @Test
    fun sourcesSplitByKind() {
        // VPN получает только proxy-подписки, Tor -- только tor:
        // каждое окно видит исключительно то, что относится к его теме.
        val sources = listOf(
            VpnSource(id = "1", name = "black", url = "https://a/1"),
            VpnSource(id = "2", name = "bridges", url = "https://a/2", kind = "tor"),
            VpnSource(id = "3", name = "white", url = "https://a/3", kind = "proxy")
        )
        assertEquals(listOf("1", "3"), VpnConnects.sourcesByKind(sources, "proxy").map { it.id })
        assertEquals(listOf("2"), VpnConnects.sourcesByKind(sources, "tor").map { it.id })
        assertTrue(VpnConnects.sourcesByKind(sources, "unknown").isEmpty())
    }

    // --- Выбор моста для автоподключения: минимум пинга ---

    @Test
    fun selectedAliveBridgeWinsOverFasterOnes() {
        // Выбор пользователя -- не декорация: живой выбранный мост
        // подключается даже если у соседа пинг меньше.
        val list = listOf(
            profile("fast", alive = true, latencyMs = 5, protocol = VpnProtocol.TOR.code),
            profile("picked", alive = true, latencyMs = 90, protocol = VpnProtocol.TOR.code)
        )
        assertEquals("picked", VpnConnects.torBridgeFor(list, "picked")?.name)
    }

    @Test
    fun fastestAliveBridgeIsPickedWithoutSelection() {
        // Автовыбор: минимальный пинг, а не первый в списке.
        val list = listOf(
            profile("slow", alive = true, latencyMs = 300, protocol = VpnProtocol.TOR.code),
            profile("fast", alive = true, latencyMs = 40, protocol = VpnProtocol.TOR.code),
            profile("mid", alive = true, latencyMs = 120, protocol = VpnProtocol.TOR.code),
            profile("dead-fast", alive = false, latencyMs = 1, protocol = VpnProtocol.TOR.code)
        )
        assertEquals("fast", VpnConnects.torBridgeFor(list, "")?.name)
    }

    @Test
    fun unmeasuredBridgeDoesNotBeatMeasuredOne() {
        // Непроверенный не выигрывает у замеренного, но остаётся в игре,
        // когда мерять было нечего.
        val list = listOf(
            profile("unchecked", protocol = VpnProtocol.TOR.code),
            profile("measured", alive = true, latencyMs = 400, protocol = VpnProtocol.TOR.code)
        )
        assertEquals("measured", VpnConnects.torBridgeFor(list, "")?.name)

        val onlyUnchecked = listOf(profile("unchecked", protocol = VpnProtocol.TOR.code))
        assertEquals("unchecked", VpnConnects.torBridgeFor(onlyUnchecked, "")?.name)
    }

    @Test
    fun deadSelectionIsLastResortAndEmptyGivesNull() {
        // Выбранный мёртвый -- всё равно кандидат: tor попробует сам.
        val list = listOf(
            profile("picked-dead", alive = false, protocol = VpnProtocol.TOR.code),
            profile("other-dead", alive = false, protocol = VpnProtocol.TOR.code)
        )
        assertEquals("picked-dead", VpnConnects.torBridgeFor(list, "picked-dead")?.name)
        assertNull(VpnConnects.torBridgeFor(emptyList(), ""))
        assertNull(VpnConnects.torBridgeFor(listOf(profile("wg")), ""))
    }
}
