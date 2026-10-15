package xyz.azraellab.zapp.core

import xyz.azraellab.zapp.core.net.ProxyUri

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
     * Коннект, чей пинг показывать в плашке подключённого туннеля.
     *
     * Ручной режим -- просто активный коннект: в него и вышли, и его
     * задержка единственная, о которой что-то известно.
     *
     * Групповой ([VpnConfig.groupMode]) -- движок поднял urltest-группу
     * и конкретного узла не знает, поэтому показываем верхний по пингу
     * из тех же коннектов, что уходят в группу: sing-box выбирает по
     * своей задержке, а верх списка по нашему пингу -- лучшая оценка
     * того, во что реально вышли. Исключение -- мост Tor: при
     * групповом режиме тор поднимается раньше urltest и работает
     * ровно с тем мостом, что активен.
     *
     * null -- нечего показывать: активного нет, а в группу не попал
     * ни один коннект (только wg и ovpn, у которых свой бэкенд).
     */
    fun pingProfile(vpn: VpnConfig): VpnProfile? {
        val active = vpn.activeProfile()
        if (!vpn.groupMode) return active
        if (active != null && active.protocol == VpnProtocol.TOR.code) return active
        return sort(groupMembers(vpn.profiles), byPing = true).firstOrNull()
    }

    /**
     * Коннекты, которые sing-box берёт в urltest-группу.
     *
     * Повторяет фильтр `boxGroup` движка: живые (или ещё не
     * проверенные) не-мосты со ссылкой `scheme://...`. WireGuard и
     * `.ovpn` в группу не идут -- у них свой бэкенд, -- а значит их
     * пинг в групповом режиме в плашку не попадает. Разбор самой
     * ссылки (`ProxyUri.parse`) здесь не нужен: промах редок, а функция
     * -- оценка для строки статуса, а не сборка конфига.
     */
    fun groupMembers(profiles: List<VpnProfile>): List<VpnProfile> = profiles.filter { p ->
        p.health.alive != false &&
            p.protocol !in GROUP_EXCLUDED &&
            "://" in p.rawConfig
    }

    /**
     * Кандидат на автопереключение: заметно лучше текущего, либо null.
     *
     * Спорят только свежие замеры: кандидат обязан быть живым с
     * измеренным откликом, иначе «быстрее» ничего не значит. Текущий
     * мёртв -- меняем на живого без оглядки на пинг; текущий жив --
     * переподключение стоит обрыва, поэтому нужны [minGainMs] выигрыша
     * (гистерезис против метания между двумя близкими мостами).
     * Замерить текущий не удалось (`latencyMs < 0`) -- базы для
     * сравнения нет, и гадать не станем.
     */
    fun betterBridge(
        current: VpnProfile?,
        candidates: List<VpnProfile>,
        minGainMs: Int
    ): VpnProfile? {
        val pool = candidates.filter { it.id != current?.id }
        val best = pool
            .filter { it.health.alive == true && it.health.latencyMs >= 0 }
            .minByOrNull { it.health.latencyMs }
            ?: return null
        if (current == null || current.health.alive == false) return best
        val currentMs = current.health.latencyMs
        if (currentMs < 0) return null
        return best.takeIf { currentMs - it.health.latencyMs >= minGainMs }
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

    // --- Тег узла в clash_api (A3: здоровье по реальному обмену) ---

    /**
     * Тег, под которым sing-box видит коннект в clash_api; null -- тег
     * отследить нельзя.
     *
     * Tag в конфиге собирается из самой ссылки (`tagOf` = `displayName()`),
     * а не из имени профиля: переименованный вручную коннект в списке
     * называется иначе, чем узел в боксе, и по имени пришёл бы замер от
     * чужого узла. Целые конфиги sing-box и xray не разбираем: там свои
     * outbounds с произвольными тегами, и любое совпадение по имени было
     * бы догадкой.
     */
    fun nodeTag(profile: VpnProfile): String? {
        val protocol = VpnProtocol.of(profile.protocol)
        if (protocol == VpnProtocol.SINGBOX || protocol == VpnProtocol.XRAY) return null
        val raw = profile.rawConfig
        if (raw.isBlank() || "://" !in raw) return null
        return runCatching { ProxyUri.parse(raw)?.displayName() }.getOrNull()?.takeIf { it.isNotBlank() }
    }

    /**
     * Профиль, чей тег совпал с целью пробы, -- по нему пишется здоровье.
     *
     * sing-box снимает дубликаты тегов суффиксом ` (2)`, ` (3)` (см.
     * `SingboxConfig.build`): номер -- порядок профиля в списке, поэтому
     * и второй, и третий дубликат находятся по своему номеру, а не наугад.
     */
    fun profileIdByNodeTag(profiles: List<VpnProfile>, tag: String): String? {
        profiles.firstOrNull { nodeTag(it) == tag }?.let { return it.id }
        val match = DUPLICATE_TAG.find(tag) ?: return null
        val base = match.groupValues[1]
        val copy = match.groupValues[2].toIntOrNull() ?: return null
        return profiles.filter { nodeTag(it) == base }.getOrNull(copy - 1)?.id
    }

    /**
     * Здоровье узла после пробы через него самого; null -- писать нечего.
     *
     * Свежий замер с той же задержкой не перезаписывается: каждый тик
     * обмена двигал бы `checkedAtMs`, и список перерисовывался бы раз в
     * минуту без единого изменения. Устаревший (или отличившийся больше
     * чем на [toleranceMs]) -- обновляется всегда, каким бы он ни был:
     * честность списка важнее покоя перерисовки.
     *
     * @param nowMs текущее время; старые проверки (`checkedAtMs = 0`)
     *   и будущие значения по времени в свежие не попадают.
     * @param windowMs окно свежести; 0 -- обновлять всегда.
     */
    fun exchangeHealth(
        old: LinkHealth,
        delayMs: Int,
        nowMs: Long,
        toleranceMs: Int = 50,
        windowMs: Long = 600_000L
    ): LinkHealth? {
        val fresh = old.alive == true &&
            old.checkedAtMs > 0 &&
            nowMs - old.checkedAtMs in 0 until windowMs &&
            kotlin.math.abs(old.latencyMs - delayMs) <= toleranceMs
        if (fresh) return null
        return LinkHealth(alive = true, latencyMs = delayMs, checkedAtMs = nowMs, error = "")
    }

    private val DUPLICATE_TAG = Regex("""^(.*) \((\d+)\)$""")

    /** Протоколы, которых нет в urltest-группе (см. [groupMembers]). */
    private val GROUP_EXCLUDED = setOf(
        VpnProtocol.TOR.code,
        VpnProtocol.WIREGUARD.code,
        VpnProtocol.AMNEZIA_WG.code,
        VpnProtocol.OPENVPN.code,
        VpnProtocol.SINGBOX.code,
        VpnProtocol.XRAY.code
    )
}
