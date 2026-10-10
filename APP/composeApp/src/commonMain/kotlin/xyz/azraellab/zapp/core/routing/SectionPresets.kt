package xyz.azraellab.zapp.core.routing

/**
 * Готовые секции маршрутизации.
 *
 * Пустая карточка секций ничего не объясняет, а три типовых правила --
 * реклама, свои сервисы и локальные сети -- закрывают бо́льшую часть
 * первых запросов. Пресет -- обычная [RouteSection]: после вставки он
 * редактируется и удаляется ровно как рукописный, никакой магии сверху.
 *
 * Метки приходят от вызывающего (он же переводит кнопки): в конфиге
 * имя секции -- обычная строка, язык интерфейса ему не известен.
 */
object SectionPresets {

    /** Реклама и трекеры: домены рекламных сетей, ответ -- reject. */
    fun ads(label: String): RouteSection = RouteSection(
        label = label,
        action = SectionAction.BLOCK,
        domains = """
            doubleclick.net, googlesyndication.com, googleadservices.com,
            pagead2.googlesyndication.com, adservice.google.com,
            adservice.google.ru, adnxs.com, pubmatic.com, rubiconproject.com,
            criteo.com, moatads.com, scorecardresearch.com, adfox.ru,
            an.yandex.net, an.yandex.ru, ads.yahoo.com
        """.trimIndent()
    )

    /**
     * Российские сервисы мимо туннеля.
     *
     * Список бытовых и государственных площадок: они либо фильтруют
     * иностранные адреса, либо нужны в обход чужого DNS. Внутренние
     * поддомены ловит суффиксное совпадение -- `yandex.ru` берёт и
     * `www.yandex.ru`, и `id.yandex.ru`.
     */
    fun russia(label: String): RouteSection = RouteSection(
        label = label,
        action = SectionAction.DIRECT,
        domains = """
            yandex.ru, ya.ru, yandex.net, yandex.com,
            vk.com, vkuservideo.net, mail.ru, ok.ru, odnoklassniki.ru,
            avito.ru, ozon.ru, wildberries.ru, sberbank.ru, sber.ru,
            gosuslugi.ru, nalog.gov.ru, tinkoff.ru,
            rbkmoney.ru, yoomoney.ru
        """.trimIndent()
    )

    /** Локальные сети напрямую: роутеры, принтеры, NAS, IoT. */
    fun local(label: String): RouteSection = RouteSection(
        label = label,
        action = SectionAction.DIRECT,
        ipCidrs = "10.0.0.0/8, 172.16.0.0/12, 192.168.0.0/16, 169.254.0.0/16, fc00::/7"
    )

    /**
     * Пресет в конец списка.
     *
     * Совпавшая метка получает суффикс ` (2)`, ` (3)`: одинаковые имена
     * в списке -- это путаница в журнале, а тихий пропуск выглядел бы так,
     * будто кнопка не сработала.
     */
    fun add(sections: List<RouteSection>, preset: RouteSection): List<RouteSection> {
        if (sections.none { it.label == preset.label }) return sections + preset
        var n = 2
        while (sections.any { it.label == "${preset.label} ($n)" }) n++
        return sections + preset.copy(label = "${preset.label} ($n)")
    }
}
