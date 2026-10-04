package xyz.azraellab.zapp.core

/**
 * Сортировка и фильтр списка коннектов.
 *
 * Вынесены из экрана в чистые функции: экран только вызывает, а проверяется
 * логика здесь -- живые впереди, битые в хвост, пинг без ответа в конец,
 * поиск без учёта регистра. Ничего не знает о Compose, поэтому тесты
 * desktopTest гоняют те же функции, что и телефон.
 */
object VpnConnects {
    /**
     * Сортировка списка.
     *
     * [byPing] -- живые впереди и по возрастанию задержки; иначе по имени.
     * Коннекты без ответа (`latencyMs < 0`) идут после всех ответивших,
     * чтобы «10 ms» не пряталось за «не проверялся».
     */
    fun sort(profiles: List<VpnProfile>, byPing: Boolean): List<VpnProfile> =
        if (byPing) {
            profiles.sortedWith(
                compareBy(
                    { if (it.health.alive == true) 0 else 1 },
                    { if (it.health.latencyMs >= 0) it.health.latencyMs else Int.MAX_VALUE },
                    { it.name }
                )
            )
        } else {
            profiles.sortedBy { it.name }
        }

    /**
     * Фильтр списка.
     *
     * [onlyAlive] оставляет только подтверждённо живые, [query] ищет по
     * имени без учёта регистра. Оба условия независимы: пустой запрос и
     * выключенный фильтр возвращают список как есть, без копирования.
     */
    fun filter(profiles: List<VpnProfile>, onlyAlive: Boolean, query: String): List<VpnProfile> {
        if (!onlyAlive && query.isBlank()) return profiles
        return profiles.filter { p ->
            (!onlyAlive || p.health.alive == true) &&
                (query.isBlank() || p.name.contains(query, ignoreCase = true))
        }
    }

    /** Идёт ли после [limit] скрытых строк: экран рисует кнопку «Показать ещё». */
    fun hasMore(filtered: List<VpnProfile>, limit: Int): Boolean = filtered.size > limit

    /**
     * Мосты для вкладки Tor: протокол `tor`, все, включая мёртвые.
     *
     * Вкладка Tor показывает исключительно мосты, а не «всё, что можно
     * завернуть в Tor»: смешение протоколов в одном списке вводит в
     * заблуждение при выборе моста. Мёртвые остались здесь после того,
     * как архив VPN стал чисто VPN-списком: связь «проверили и отказал»
     * нужна и мостам, и показывается в подписи выбора. `torEnabled`
     * («показывать в общем списке VPN») на вкладку не влияет: здесь
     * мосты видны всегда, иначе вкладка пустела бы и выглядела бы
     * сломанной.
     */
    fun torBridges(profiles: List<VpnProfile>): List<VpnProfile> =
        profiles.filter { it.protocol == VpnProtocol.TOR.code }

    /**
     * Мост для автоподключения: выбранный живой -> самый быстрый живой ->
     * выбранный (пусть мёртвый: tor попробует сам, список в конфиге его).
     *
     * «Самый быстрый» -- минимальный замеренный пинг: автовыбору важна
     * задержка соединения, а не порядок в списке. Непроверенные
     * (`latencyMs < 0`) не обгоняют замеренных, но остаются в игре,
     * когда мерять было нечего.
     */
    fun torBridgeFor(profiles: List<VpnProfile>, activeProfileId: String): VpnProfile? {
        // Отбор протокола здесь, а не у вызывающего: контракт функции --
        // «мост», и список из профилей любого протокола не должен
        // подсунуть wireguard вместо моста.
        val bridges = torBridges(profiles)
        val selected = bridges.firstOrNull { it.id == activeProfileId }
        val alive = bridges.filter { it.health.alive != false }
        return selected?.takeIf { it.health.alive != false }
            ?: alive.minByOrNull { it.health.latencyMs.takeIf { ms -> ms >= 0 } ?: Int.MAX_VALUE }
            ?: selected
    }

    /**
     * Источники-подписки: [kind] -- как в [VpnSource].
     *
     * Мостовые подписки (`kind == "tor"`) показываются на вкладке Tor,
     * остальные -- в списке VPN: каждый раздел получает только то, что
     * относится к его теме.
     */
    fun sourcesByKind(sources: List<VpnSource>, kind: String): List<VpnSource> =
        sources.filter { it.kind == kind }
}
