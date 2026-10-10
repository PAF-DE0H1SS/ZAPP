package xyz.azraellab.zapp.core.domains

/**
 * Связанные домены выбранных приложений: быстрый offline-поиск.
 *
 * Запуск обхода без области действия -- пустая трата: nfqws/goodbyedpi
 * фильтруют по доменам, и без списка целей команда не имеет смысла.
 * Когда пользователь выбирает приложения, их домены дописываются в
 * список автоматически -- вручную составлять его для discord или youtube
 * незачем.
 *
 * Поиск без сети и без задержек, в три шага:
 * 1. Карта «метка пакета -> зерна»: токены имени пакета (com.discord.app
 *    -> discord) ищутся в curated-списке известных продуктов.
 * 2. Рекурсивное расширение: зерно тянет родительскую зону, связанные
 *    зоны (discord.com -> discord.gg) и частые поддомены своей зоны;
 *    повторно посещённые домены отсекаются множеством, поэтому циклы
 *    (a -> b -> a) безопасны.
 * 3. Запасной вывод для неизвестных пакетов: первая содержательная метка
 *    пакета превращается в домен-кандидат. Он может не совпасть ни с чем
 *    реальным -- лишний домен в списке безвреден, а пропущенный найдётся
 *    ручным вводом.
 *
 * Все функции чистые: один и тот же выбор пакетов всегда даёт один и тот
 * же список, поэтому результат проверяется тестами и не зависит от
 * устройства, на котором его собрали.
 */
object AppDomains {

    /** Метка пакета -> зерна доменов продукта. Ключ сравнивается с токенами пакета. */
    private val SEEDS: Map<String, List<String>> = mapOf(
        "discord" to listOf("discord.com", "discord.gg", "discordapp.com", "discordapp.net", "dis.gd"),
        "youtube" to listOf("youtube.com", "youtu.be", "googlevideo.com", "ytimg.com", "youtube-nocookie.com"),
        "google" to listOf("google.com", "googleapis.com", "gstatic.com", "ggpht.com"),
        "telegram" to listOf("telegram.org", "t.me", "telegram.me", "telegra.ph", "telesco.pe"),
        "whatsapp" to listOf("whatsapp.com", "whatsapp.net"),
        "instagram" to listOf("instagram.com", "cdninstagram.com"),
        "facebook" to listOf("facebook.com", "fb.com", "fbcdn.net", "messenger.com", "fb.me"),
        "tiktok" to listOf("tiktok.com", "tiktokv.com", "tiktokcdn.com", "musical.ly"),
        "twitter" to listOf("twitter.com", "x.com", "twimg.com", "t.co"),
        "vk" to listOf("vk.com", "vk.ru", "userapi.com", "vkuservideo.net", "vkcdn.net"),
        "ok" to listOf("ok.ru", "okcdn.ru", "odnoklassniki.ru"),
        "yandex" to listOf("yandex.ru", "yandex.com", "yastatic.net", "ya.ru", "yandex.net"),
        "mail" to listOf("mail.ru", "my.com"),
        "netflix" to listOf("netflix.com", "nflximg.com", "nflxvideo.net", "nflxso.net"),
        "twitch" to listOf("twitch.tv", "ttvnw.net", "jtvnw.net"),
        "reddit" to listOf("reddit.com", "redd.it", "redditstatic.com", "redditmedia.com"),
        "steam" to listOf("steampowered.com", "steamstatic.com", "steamcommunity.com"),
        "epicgames" to listOf("epicgames.com", "epicgames.dev", "unrealengine.com"),
        "epic" to listOf("epicgames.com", "epicgames.dev", "unrealengine.com"),
        "riotgames" to listOf("riotgames.com", "leagueoflegends.com", "playvalorant.com"),
        "riot" to listOf("riotgames.com", "leagueoflegends.com", "playvalorant.com"),
        "ubisoft" to listOf("ubisoft.com", "ubi.com"),
        "spotify" to listOf("spotify.com", "scdn.co", "spoti.fi"),
        "soundcloud" to listOf("soundcloud.com", "sndcdn.com"),
        "zoom" to listOf("zoom.us", "zoomgov.com"),
        "roblox" to listOf("roblox.com", "rbxcdn.com"),
        "mojang" to listOf("minecraft.net", "mojang.com"),
        "minecraft" to listOf("minecraft.net", "mojang.com"),
        "slack" to listOf("slack.com", "slack-edge.com"),
        "signal" to listOf("signal.org", "whispersystems.org"),
        "microsoft" to listOf("live.com", "office.com", "office365.com", "sharepoint.com", "skype.com"),
        "teams" to listOf("live.com", "office.com", "office365.com", "sharepoint.com"),
        "skype" to listOf("skype.com", "live.com"),
        "amazon" to listOf("amazon.com", "amazonaws.com", "amzn.to", "ssl-images-amazon.com"),
        "apple" to listOf("apple.com", "icloud.com", "mzstatic.com"),
        "pinterest" to listOf("pinterest.com", "pinimg.com"),
        "linkedin" to listOf("linkedin.com", "lnkd.in"),
        "snapchat" to listOf("snapchat.com", "snap.com"),
        "paypal" to listOf("paypal.com", "paypalobjects.com"),
        "uber" to listOf("uber.com"),
        "dropbox" to listOf("dropbox.com", "dropboxusercontent.com", "dropboxapi.com"),
        "github" to listOf("github.com", "githubusercontent.com", "github.io", "githubassets.com"),
        "gitlab" to listOf("gitlab.com", "gitlab.io"),
        "npm" to listOf("npmjs.com", "npmjs.org"),
        "mozilla" to listOf("mozilla.org", "firefox.com"),
        "firefox" to listOf("mozilla.org", "firefox.com"),
        "opera" to listOf("opera.com", "operacdn.com"),
        "proton" to listOf("proton.me", "protonmail.com", "protonvpn.com"),
        "vimeo" to listOf("vimeo.com"),
        "xiaomi" to listOf("xiaomi.com", "mi.com", "miui.com"),
        "huawei" to listOf("huawei.com", "hicloud.com"),
        "samsung" to listOf("samsung.com"),
        "mihoyo" to listOf("mihoyo.com", "hoyoverse.com", "hoyolab.com"),
        "hoyoverse" to listOf("hoyoverse.com", "mihoyo.com", "hoyolab.com"),
        "nk" to listOf("nkmsg.com", "mail.ru")
    )

    /** Найденный домен -> связанные зоны того же продукта. */
    private val RELATED: Map<String, List<String>> = mapOf(
        "youtube.com" to listOf("youtu.be", "googlevideo.com", "ytimg.com", "youtube-nocookie.com"),
        "youtu.be" to listOf("youtube.com", "googlevideo.com", "ytimg.com"),
        "discord.com" to listOf("discord.gg", "discordapp.com", "discordapp.net", "dis.gd"),
        "discordapp.com" to listOf("discord.com", "discord.gg", "discordapp.net"),
        "telegram.org" to listOf("t.me", "telegram.me", "telegra.ph"),
        "t.me" to listOf("telegram.org", "telegram.me", "telegra.ph"),
        "whatsapp.com" to listOf("whatsapp.net"),
        "whatsapp.net" to listOf("whatsapp.com"),
        "facebook.com" to listOf("fb.com", "fbcdn.net", "messenger.com", "fb.me"),
        "instagram.com" to listOf("cdninstagram.com"),
        "twitter.com" to listOf("x.com", "twimg.com", "t.co"),
        "x.com" to listOf("twitter.com", "twimg.com", "t.co"),
        "vk.com" to listOf("userapi.com", "vkuservideo.net", "vkcdn.net", "vk.ru"),
        "reddit.com" to listOf("redd.it", "redditstatic.com", "redditmedia.com"),
        "steampowered.com" to listOf("steamstatic.com", "steamcommunity.com"),
        "steamcommunity.com" to listOf("steampowered.com", "steamstatic.com"),
        "netflix.com" to listOf("nflximg.com", "nflxvideo.net", "nflxso.net"),
        "twitch.tv" to listOf("ttvnw.net", "jtvnw.net"),
        "spotify.com" to listOf("scdn.co", "spoti.fi"),
        "soundcloud.com" to listOf("sndcdn.com"),
        "github.com" to listOf("githubusercontent.com", "github.io", "githubassets.com"),
        "google.com" to listOf("googleapis.com", "gstatic.com", "ggpht.com"),
        "roblox.com" to listOf("rbxcdn.com"),
        "minecraft.net" to listOf("mojang.com"),
        "mojang.com" to listOf("minecraft.net"),
        "linkedin.com" to listOf("lnkd.in"),
        "snapchat.com" to listOf("snap.com"),
        "pinterest.com" to listOf("pinimg.com"),
        "dropbox.com" to listOf("dropboxusercontent.com", "dropboxapi.com"),
        "apple.com" to listOf("icloud.com", "mzstatic.com"),
        "amazon.com" to listOf("ssl-images-amazon.com", "amzn.to", "amazonaws.com"),
        "microsoft.com" to listOf("live.com", "office.com", "office365.com", "sharepoint.com"),
        "skype.com" to listOf("live.com"),
        "mozilla.org" to listOf("firefox.com"),
        "firefox.com" to listOf("mozilla.org"),
        "opera.com" to listOf("operacdn.com"),
        "yandex.ru" to listOf("yastatic.net", "ya.ru", "yandex.com"),
        "ok.ru" to listOf("okcdn.ru", "odnoklassniki.ru"),
        "mail.ru" to listOf("my.com"),
        "zoom.us" to listOf("zoomgov.com")
    )

    /**
     * Частые поддомены, достраиваемые к регистрируемой зоне.
     *
     * Список умеренный: каждый лишний поддомен -- строка в hostlist, а
     * реальный трафик на несуществующем поддомене не бывает.
     */
    private val SUBDOMAINS = listOf(
        "www", "m", "api", "cdn", "static", "media",
        "ws", "gateway", "auth", "login", "mail", "dl"
    )

    /** Составные суффиксы: у них родитель -- не одна метка. */
    private val MULTI_SUFFIX = setOf(
        "co.uk", "com.au", "co.jp", "com.br", "com.cn", "co.in",
        "com.tr", "co.il", "com.ar", "co.kr", "com.mx", "org.uk", "net.au"
    )

    /** Метки пакетов, не несущие смысла для домена-кандидата. */
    private val PACKAGE_STOP = setOf(
        "com", "org", "net", "io", "app", "android", "mobile", "client",
        "github", "google", "me", "ru", "dev", "labs", "soft", "apps"
    )

    /** Потолок списка: больше не помещается ни в команду, ни в файл. */
    const val MAX_DOMAINS = 500

    /** Домены для списка пакетов: зерна + рекурсивное расширение. */
    fun discover(packages: Collection<String>): List<String> {
        val seeds = LinkedHashSet<String>()
        for (pkg in packages) seeds += seedsFor(pkg)
        return expand(seeds)
    }

    /**
     * Зерна одного пакета: совпадение токенов с картой, иначе --
     * домен-кандидат из первой содержательной метки.
     */
    internal fun seedsFor(packageName: String): List<String> {
        val tokens = packageName.lowercase().split('.').filter { it.isNotEmpty() }
        val matched = LinkedHashSet<String>()
        for ((key, domains) in SEEDS) {
            if (tokens.any { it == key }) matched += domains
        }
        if (matched.isNotEmpty()) return matched.toList()
        val guess = tokens.firstOrNull { it !in PACKAGE_STOP && it.length >= 3 }
        return if (guess != null) listOf("$guess.com") else emptyList()
    }

    /**
     * Рекурсивное расширение зерен: родитель, связанные зоны, поддомены.
     *
     * Порядок -- обход в ширину, отсечение -- по уже посещённым, поэтому
     * взаимные ссылки карты RELATED не закручиваются в цикл.
     */
    internal fun expand(seeds: Collection<String>): List<String> {
        val out = LinkedHashSet<String>()
        val queue = ArrayDeque<String>()
        for (seed in seeds) queue += normalize(seed)
        while (queue.isNotEmpty() && out.size < MAX_DOMAINS) {
            val domain = queue.removeFirst()
            if (domain.isEmpty() || domain in out) continue
            out += domain
            parent(domain)?.let { queue += it }
            RELATED[domain]?.forEach { queue += normalize(it) }
            // Поддомены -- только у зоны без родителя: у cdn.discord.com
            // родительская discord.com уже обойдена, размножать нечего.
            if (parent(domain) == null) {
                for (sub in SUBDOMAINS) queue += "$sub.$domain"
            }
        }
        return out.toList()
    }

    /**
     * Сливает найденное с уже введённым: ручной список не теряется,
     * новое дописывается в конец без повторов.
     */
    fun mergeInto(existing: String, found: List<String>): String {
        val merged = LinkedHashSet<String>()
        existing.split(',').map { it.trim() }.filter { it.isNotEmpty() }.forEach { merged += it }
        merged += found
        return merged.joinToString(", ")
    }

    /**
     * Регистрируемая зона домена, null -- зона без родителя.
     *
     * Цель зоны, а не ближайший предок: у cdn.discord.com зона --
     * discord.com, промежуточная cdn.discord никому не нужна. У составных
     * суффиксов (co.uk) зона шире двух меток.
     */
    private fun parent(domain: String): String? {
        val labels = domain.split('.')
        if (labels.size <= 2) return null
        if (labels.takeLast(2).joinToString(".") in MULTI_SUFFIX) {
            return if (labels.size <= 3) null else labels.takeLast(3).joinToString(".")
        }
        return labels.takeLast(2).joinToString(".")
    }

    private fun normalize(domain: String): String =
        domain.trim().trimEnd('.').lowercase().removePrefix("www.")
}
