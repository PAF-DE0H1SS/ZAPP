package xyz.azraellab.zapp.core

/**
 * Страна/регион пользователя.
 *
 * Страна -- один из факторов автонастройки: для части регионов есть
 * [RegionPolicy], которая прячет протоколы, которые в этой стране не работают,
 * и включает обходные механизмы. Все остальные страны получают нейтральную
 * политику и ничем не отличаются от «не выбранного» региона.
 */
data class Region(
    val code: String,
    val nameEn: String,
    val nameRu: String,
    val nameZh: String
)

/**
 * Политика региона: чем локальная сеть отличается от нейтральной.
 *
 * Политика НЕ меняет конфиг напрямую и НЕ делает недостижимые поля
 * недосягаемыми -- она лишь даёт сигналы, а применяют их отдельные механизмы
 * (список протоколов, автонастройка, конфликтные правила). Так политика
 * остаётся чистой декларацией и не зависит от интерфейса.
 */
data class RegionPolicy(
    val code: String,
    /** Hiding protocol codes (см. [VpnProtocol.code]) в списке профилей. */
    val hiddenProtocols: Set<String> = emptySet(),
    /** Список приоритетных протоколов для автонастройки (по убыванию). */
    val preferredProtocols: List<String> = emptyList(),
    /** DNS всегда через прокси (важно там, где резолвер отравляют). */
    val forceDnsViaProxy: Boolean = false,
    /** Предпочитать TCP-транспорты узлам с UDP-транспортом. */
    val preferTcp: Boolean = false
) {
    companion object {
        /** Нейтральная политика: регион ни на что не влияет. */
        val NEUTRAL = RegionPolicy(code = "")
    }
}

/** Все страны мира (ISO 3166-1 alpha-2) с именами на трёх языках. */
object RegionRegistry {

    private val ALL: List<Region> = listOf(
        Region("AF", "Afghanistan", "Афганистан", "阿富汗"),
        Region("AX", "Åland Islands", "Аландские острова", "奥兰群岛"),
        Region("AL", "Albania", "Албания", "阿尔巴尼亚"),
        Region("DZ", "Algeria", "Алжир", "阿尔及利亚"),
        Region("AS", "American Samoa", "Американское Самоа", "美属萨摩亚"),
        Region("AD", "Andorra", "Андорра", "安道尔"),
        Region("AO", "Angola", "Ангола", "安哥拉"),
        Region("AI", "Anguilla", "Ангилья", "安圭拉"),
        Region("AG", "Antigua and Barbuda", "Антигуа и Барбуда", "安提瓜和巴布达"),
        Region("AR", "Argentina", "Аргентина", "阿根廷"),
        Region("AM", "Armenia", "Армения", "亚美尼亚"),
        Region("AW", "Aruba", "Аруба", "阿鲁巴"),
        Region("AU", "Australia", "Австралия", "澳大利亚"),
        Region("AT", "Austria", "Австрия", "奥地利"),
        Region("AZ", "Azerbaijan", "Азербайджан", "阿塞拜疆"),
        Region("BS", "Bahamas", "Багамские Острова", "巴哈马"),
        Region("BH", "Bahrain", "Бахрейн", "巴林"),
        Region("BD", "Bangladesh", "Бангладеш", "孟加拉国"),
        Region("BB", "Barbados", "Барбадос", "巴巴多斯"),
        Region("BY", "Belarus", "Беларусь", "白俄罗斯"),
        Region("BE", "Belgium", "Бельгия", "比利时"),
        Region("BZ", "Belize", "Белиз", "伯利兹"),
        Region("BJ", "Benin", "Бенин", "贝宁"),
        Region("BM", "Bermuda", "Бермудские Острова", "百慕大"),
        Region("BT", "Bhutan", "Бутан", "不丹"),
        Region("BO", "Bolivia", "Боливия", "玻利维亚"),
        Region("BQ", "Bonaire, Sint Eustatius and Saba", "Бонайре, Синт-Эстатиус и Саба", "博奈尔、圣尤斯特歇斯和萨巴"),
        Region("BA", "Bosnia and Herzegovina", "Босния и Герцеговина", "波斯尼亚和黑塞哥维那"),
        Region("BW", "Botswana", "Ботсвана", "博茨瓦纳"),
        Region("BR", "Brazil", "Бразилия", "巴西"),
        Region("IO", "British Indian Ocean Territory", "Британская территория в Индийском океане", "英属印度洋领地"),
        Region("VG", "British Virgin Islands", "Британские Виргинские острова", "英属维尔京群岛"),
        Region("BN", "Brunei", "Бруней", "文莱"),
        Region("BG", "Bulgaria", "Болгария", "保加利亚"),
        Region("BF", "Burkina Faso", "Буркина-Фасо", "布基纳法索"),
        Region("BI", "Burundi", "Бурунди", "布隆迪"),
        Region("CV", "Cape Verde", "Кабо-Верде", "佛得角"),
        Region("KH", "Cambodia", "Камбоджа", "柬埔寨"),
        Region("CM", "Cameroon", "Камерун", "喀麦隆"),
        Region("CA", "Canada", "Канада", "加拿大"),
        Region("KY", "Cayman Islands", "Каймановы острова", "开曼群岛"),
        Region("CF", "Central African Republic", "Центральноафриканская Республика", "中非共和国"),
        Region("TD", "Chad", "Чад", "乍得"),
        Region("CL", "Chile", "Чили", "智利"),
        Region("CN", "China", "Китай", "中国"),
        Region("CX", "Christmas Island", "Остров Рождества", "圣诞岛"),
        Region("CC", "Cocos (Keeling) Islands", "Кокосовые острова", "科科斯群岛"),
        Region("CO", "Colombia", "Колумбия", "哥伦比亚"),
        Region("KM", "Comoros", "Коморы", "科摩罗"),
        Region("CG", "Congo (Republic)", "Республика Конго", "刚果（布）"),
        Region("CD", "Congo (DRC)", "Демократическая Республика Конго", "刚果（金）"),
        Region("CK", "Cook Islands", "Острова Кука", "库克群岛"),
        Region("CR", "Costa Rica", "Коста-Рика", "哥斯达黎加"),
        Region("CI", "Côte d'Ivoire", "Кот-д'Ивуар", "科特迪瓦"),
        Region("HR", "Croatia", "Хорватия", "克罗地亚"),
        Region("CU", "Cuba", "Куба", "古巴"),
        Region("CW", "Curaçao", "Кюрасао", "库拉索"),
        Region("CY", "Cyprus", "Кипр", "塞浦路斯"),
        Region("CZ", "Czech Republic", "Чехия", "捷克"),
        Region("DK", "Denmark", "Дания", "丹麦"),
        Region("DJ", "Djibouti", "Джибути", "吉布提"),
        Region("DM", "Dominica", "Доминика", "多米尼克"),
        Region("DO", "Dominican Republic", "Доминиканская Республика", "多米尼加共和国"),
        Region("EC", "Ecuador", "Эквадор", "厄瓜多尔"),
        Region("EG", "Egypt", "Египет", "埃及"),
        Region("SV", "El Salvador", "Сальвадор", "萨尔瓦多"),
        Region("GQ", "Equatorial Guinea", "Экваториальная Гвинея", "赤道几内亚"),
        Region("ER", "Eritrea", "Эритрея", "厄立特里亚"),
        Region("EE", "Estonia", "Эстония", "爱沙尼亚"),
        Region("SZ", "Eswatini", "Эсватини", "斯威士兰"),
        Region("ET", "Ethiopia", "Эфиопия", "埃塞俄比亚"),
        Region("FK", "Falkland Islands", "Фолклендские острова", "福克兰群岛"),
        Region("FO", "Faroe Islands", "Фарерские острова", "法罗群岛"),
        Region("FJ", "Fiji", "Фиджи", "斐济"),
        Region("FI", "Finland", "Финляндия", "芬兰"),
        Region("FR", "France", "Франция", "法国"),
        Region("GF", "French Guiana", "Французская Гвиана", "法属圭亚那"),
        Region("PF", "French Polynesia", "Французская Полинезия", "法属波利尼西亚"),
        Region("GA", "Gabon", "Габон", "加蓬"),
        Region("GM", "Gambia", "Гамбия", "冈比亚"),
        Region("GE", "Georgia", "Грузия", "格鲁吉亚"),
        Region("DE", "Germany", "Германия", "德国"),
        Region("GH", "Ghana", "Гана", "加纳"),
        Region("GI", "Gibraltar", "Гибралтар", "直布罗陀"),
        Region("GR", "Greece", "Греция", "希腊"),
        Region("GL", "Greenland", "Гренландия", "格陵兰"),
        Region("GD", "Grenada", "Гренада", "格林纳达"),
        Region("GP", "Guadeloupe", "Гваделупа", "瓜德罗普"),
        Region("GU", "Guam", "Гуам", "关岛"),
        Region("GT", "Guatemala", "Гватемала", "危地马拉"),
        Region("GG", "Guernsey", "Гернси", "根西岛"),
        Region("GN", "Guinea", "Гвинея", "几内亚"),
        Region("GW", "Guinea-Bissau", "Гвинея-Бисау", "几内亚比绍"),
        Region("GY", "Guyana", "Гайана", "圭亚那"),
        Region("HT", "Haiti", "Гаити", "海地"),
        Region("HN", "Honduras", "Гондурас", "洪都拉斯"),
        Region("HK", "Hong Kong", "Гонконг", "香港"),
        Region("HU", "Hungary", "Венгрия", "匈牙利"),
        Region("IS", "Iceland", "Исландия", "冰岛"),
        Region("IN", "India", "Индия", "印度"),
        Region("ID", "Indonesia", "Индонезия", "印度尼西亚"),
        Region("IR", "Iran", "Иран", "伊朗"),
        Region("IQ", "Iraq", "Ирак", "伊拉克"),
        Region("IE", "Ireland", "Ирландия", "爱尔兰"),
        Region("IM", "Isle of Man", "Остров Мэн", "马恩岛"),
        Region("IL", "Israel", "Израиль", "以色列"),
        Region("IT", "Italy", "Италия", "意大利"),
        Region("JM", "Jamaica", "Ямайка", "牙买加"),
        Region("JP", "Japan", "Япония", "日本"),
        Region("JE", "Jersey", "Джерси", "泽西岛"),
        Region("JO", "Jordan", "Иордания", "约旦"),
        Region("KZ", "Kazakhstan", "Казахстан", "哈萨克斯坦"),
        Region("KE", "Kenya", "Кения", "肯尼亚"),
        Region("KI", "Kiribati", "Кирибати", "基里巴斯"),
        Region("XK", "Kosovo", "Косово", "科索沃"),
        Region("KW", "Kuwait", "Кувейт", "科威特"),
        Region("KG", "Kyrgyzstan", "Кыргызстан", "吉尔吉斯斯坦"),
        Region("LA", "Laos", "Лаос", "老挝"),
        Region("LV", "Latvia", "Латвия", "拉脱维亚"),
        Region("LB", "Lebanon", "Ливан", "黎巴嫩"),
        Region("LS", "Lesotho", "Лесото", "莱索托"),
        Region("LR", "Liberia", "Либерия", "利比里亚"),
        Region("LY", "Libya", "Ливия", "利比亚"),
        Region("LI", "Liechtenstein", "Лихтенштейн", "列支敦士登"),
        Region("LT", "Lithuania", "Литва", "立陶宛"),
        Region("LU", "Luxembourg", "Люксембург", "卢森堡"),
        Region("MO", "Macau", "Макао", "澳门"),
        Region("MG", "Madagascar", "Мадагаскар", "马达加斯加"),
        Region("MW", "Malawi", "Малави", "马拉维"),
        Region("MY", "Malaysia", "Малайзия", "马来西亚"),
        Region("MV", "Maldives", "Мальдивы", "马尔代夫"),
        Region("ML", "Mali", "Мали", "马里"),
        Region("MT", "Malta", "Мальта", "马耳他"),
        Region("MH", "Marshall Islands", "Маршалловы Острова", "马绍尔群岛"),
        Region("MQ", "Martinique", "Мартиника", "马提尼克"),
        Region("MR", "Mauritania", "Мавритания", "毛里塔尼亚"),
        Region("MU", "Mauritius", "Маврикий", "毛里求斯"),
        Region("YT", "Mayotte", "Майотта", "马约特"),
        Region("MX", "Mexico", "Мексика", "墨西哥"),
        Region("FM", "Micronesia", "Микронезия", "密克罗尼西亚"),
        Region("MD", "Moldova", "Молдова", "摩尔多瓦"),
        Region("MC", "Monaco", "Монако", "摩纳哥"),
        Region("MN", "Mongolia", "Монголия", "蒙古"),
        Region("ME", "Montenegro", "Черногория", "黑山"),
        Region("MS", "Montserrat", "Монтсеррат", "蒙特塞拉特"),
        Region("MA", "Morocco", "Марокко", "摩洛哥"),
        Region("MZ", "Mozambique", "Мозамбик", "莫桑比克"),
        Region("MM", "Myanmar", "Мьянма", "缅甸"),
        Region("NA", "Namibia", "Намибия", "纳米比亚"),
        Region("NR", "Nauru", "Науру", "瑙鲁"),
        Region("NP", "Nepal", "Непал", "尼泊尔"),
        Region("NL", "Netherlands", "Нидерланды", "荷兰"),
        Region("NC", "New Caledonia", "Новая Каледония", "新喀里多尼亚"),
        Region("NZ", "New Zealand", "Новая Зеландия", "新西兰"),
        Region("NI", "Nicaragua", "Никарагуа", "尼加拉瓜"),
        Region("NE", "Niger", "Нигер", "尼日尔"),
        Region("NG", "Nigeria", "Нигерия", "尼日利亚"),
        Region("NU", "Niue", "Ниуэ", "纽埃"),
        Region("NF", "Norfolk Island", "Остров Норфолк", "诺福克岛"),
        Region("KP", "North Korea", "Северная Корея", "朝鲜"),
        Region("MK", "North Macedonia", "Северная Македония", "北马其顿"),
        Region("MP", "Northern Mariana Islands", "Северные Марианские острова", "北马里亚纳群岛"),
        Region("NO", "Norway", "Норвегия", "挪威"),
        Region("OM", "Oman", "Оман", "阿曼"),
        Region("PK", "Pakistan", "Пакистан", "巴基斯坦"),
        Region("PW", "Palau", "Палау", "帕劳"),
        Region("PS", "Palestine", "Палестина", "巴勒斯坦"),
        Region("PA", "Panama", "Панама", "巴拿马"),
        Region("PG", "Papua New Guinea", "Папуа — Новая Гвинея", "巴布亚新几内亚"),
        Region("PY", "Paraguay", "Парагвай", "巴拉圭"),
        Region("PE", "Peru", "Перу", "秘鲁"),
        Region("PH", "Philippines", "Филиппины", "菲律宾"),
        Region("PN", "Pitcairn Islands", "Острова Питкэрн", "皮特凯恩群岛"),
        Region("PL", "Poland", "Польша", "波兰"),
        Region("PT", "Portugal", "Португалия", "葡萄牙"),
        Region("PR", "Puerto Rico", "Пуэрто-Рико", "波多黎各"),
        Region("QA", "Qatar", "Катар", "卡塔尔"),
        Region("RE", "Réunion", "Реюньон", "留尼汪"),
        Region("RO", "Romania", "Румыния", "罗马尼亚"),
        Region("RU", "Russia", "Россия", "俄罗斯"),
        Region("RW", "Rwanda", "Руанда", "卢旺达"),
        Region("BL", "Saint Barthélemy", "Сен-Бартелеми", "圣巴泰勒米"),
        Region("SH", "Saint Helena", "Остров Святой Елены", "圣赫勒拿"),
        Region("KN", "Saint Kitts and Nevis", "Сент-Китс и Невис", "圣基茨和尼维斯"),
        Region("LC", "Saint Lucia", "Сент-Люсия", "圣卢西亚"),
        Region("MF", "Saint Martin", "Сен-Мартен", "法属圣马丁"),
        Region("PM", "Saint Pierre and Miquelon", "Сен-Пьер и Микелон", "圣皮埃尔和密克隆"),
        Region("VC", "Saint Vincent and the Grenadines", "Сент-Винсент и Гренадины", "圣文森特和格林纳丁斯"),
        Region("WS", "Samoa", "Самоа", "萨摩亚"),
        Region("SM", "San Marino", "Сан-Марино", "圣马力诺"),
        Region("ST", "São Tomé and Príncipe", "Сан-Томе и Принсипи", "圣多美和普林西比"),
        Region("SA", "Saudi Arabia", "Саудовская Аравия", "沙特阿拉伯"),
        Region("SN", "Senegal", "Сенегал", "塞内加尔"),
        Region("RS", "Serbia", "Сербия", "塞尔维亚"),
        Region("SC", "Seychelles", "Сейшельские Острова", "塞舌尔"),
        Region("SL", "Sierra Leone", "Сьерра-Леоне", "塞拉利昂"),
        Region("SG", "Singapore", "Сингапур", "新加坡"),
        Region("SX", "Sint Maarten", "Синт-Мартен", "荷属圣马丁"),
        Region("SK", "Slovakia", "Словакия", "斯洛伐克"),
        Region("SI", "Slovenia", "Словения", "斯洛文尼亚"),
        Region("SB", "Solomon Islands", "Соломоновы Острова", "所罗门群岛"),
        Region("SO", "Somalia", "Сомали", "索马里"),
        Region("ZA", "South Africa", "ЮАР", "南非"),
        Region("GS", "South Georgia and the South Sandwich Islands", "Южная Георгия и Южные Сандвичевы острова", "南乔治亚和南桑威奇群岛"),
        Region("KR", "South Korea", "Южная Корея", "韩国"),
        Region("SS", "South Sudan", "Южный Судан", "南苏丹"),
        Region("ES", "Spain", "Испания", "西班牙"),
        Region("LK", "Sri Lanka", "Шри-Ланка", "斯里兰卡"),
        Region("SD", "Sudan", "Судан", "苏丹"),
        Region("SR", "Suriname", "Суринам", "苏里南"),
        Region("SJ", "Svalbard and Jan Mayen", "Шпицберген и Ян-Майен", "斯瓦尔巴和扬马延"),
        Region("SE", "Sweden", "Швеция", "瑞典"),
        Region("CH", "Switzerland", "Швейцария", "瑞士"),
        Region("SY", "Syria", "Сирия", "叙利亚"),
        Region("TW", "Taiwan", "Тайвань", "台湾"),
        Region("TJ", "Tajikistan", "Таджикистан", "塔吉克斯坦"),
        Region("TZ", "Tanzania", "Танзания", "坦桑尼亚"),
        Region("TH", "Thailand", "Таиланд", "泰国"),
        Region("TL", "Timor-Leste", "Восточный Тимор", "东帝汶"),
        Region("TG", "Togo", "Того", "多哥"),
        Region("TK", "Tokelau", "Токелау", "托克劳"),
        Region("TO", "Tonga", "Тонга", "汤加"),
        Region("TT", "Trinidad and Tobago", "Тринидад и Тобаго", "特立尼达和多巴哥"),
        Region("TN", "Tunisia", "Тунис", "突尼斯"),
        Region("TR", "Türkiye", "Турция", "土耳其"),
        Region("TM", "Turkmenistan", "Туркменистан", "土库曼斯坦"),
        Region("TC", "Turks and Caicos Islands", "Тёркс и Кайкос", "特克斯和凯科斯群岛"),
        Region("TV", "Tuvalu", "Тувалу", "图瓦卢"),
        Region("UG", "Uganda", "Уганда", "乌干达"),
        Region("UA", "Ukraine", "Украина", "乌克兰"),
        Region("AE", "United Arab Emirates", "ОАЭ", "阿联酋"),
        Region("GB", "United Kingdom", "Великобритания", "英国"),
        Region("US", "United States", "США", "美国"),
        Region("UY", "Uruguay", "Уругвай", "乌拉圭"),
        Region("VI", "US Virgin Islands", "Американские Виргинские острова", "美属维尔京群岛"),
        Region("UZ", "Uzbekistan", "Узбекистан", "乌兹别克斯坦"),
        Region("VU", "Vanuatu", "Вануату", "瓦努阿图"),
        Region("VA", "Vatican City", "Ватикан", "梵蒂冈"),
        Region("VE", "Venezuela", "Венесуэла", "委内瑞拉"),
        Region("VN", "Vietnam", "Вьетнам", "越南"),
        Region("WF", "Wallis and Futuna", "Уоллис и Футуна", "瓦利斯和富图纳"),
        Region("EH", "Western Sahara", "Западная Сахара", "西撒哈拉"),
        Region("YE", "Yemen", "Йемен", "也门"),
        Region("ZM", "Zambia", "Замбия", "赞比亚"),
        Region("ZW", "Zimbabwe", "Зимбабве", "津巴布韦")
    )

    /** Все страны, отсортированные по имени (для выпадающего списка). */
    val all: List<Region>
        get() = ALL.sortedBy { it.nameEn.lowercase() }

    /**
     * Страны в порядке текущего языка.
     *
     * [all] отсортирован по английскому имени: на английском это то же
     * самое, а при русской локали список шёл по-английски -- Россия
     * искалась между Румынией и Руандой. Китай -- по иероглифам,
     * это и есть его алфавитный порядок.
     */
    fun allSorted(lang: AppLang): List<Region> = ALL.sortedBy {
        when (lang) {
            AppLang.RU -> it.nameRu
            AppLang.ZH -> it.nameZh
            else -> it.nameEn
        }.lowercase()
    }

    fun byCode(code: String?): Region? =
        ALL.firstOrNull { it.code.equals(code?.trim(), ignoreCase = true) }

    /** Имя страны на текущем языке; неизвестный язык -- английское. */
    fun localized(code: String?, lang: AppLang): String {
        val region = byCode(code) ?: return ""
        return when (lang) {
            AppLang.RU -> region.nameRu
            AppLang.ZH -> region.nameZh
            else -> region.nameEn
        }
    }
}

/**
 * Политики регионов: что в этой стране скрыто и что включить по умолчанию.
 *
 * Политика применяется через [RegionPolicy.applyDns] (к настройкам VPN) и
 * через фильтр протоколов в списке. Неизвестный регион -- нейтральная
 * политика, ничего не меняет.
 */
object RegionPolicies {
    private val RU = RegionPolicy(
        code = "RU",
        hiddenProtocols = setOf(
            VpnProtocol.WIREGUARD.code,
            VpnProtocol.AMNEZIA_WG.code,
            VpnProtocol.HYSTERIA2.code,
            VpnProtocol.TUIC.code
        ),
        preferredProtocols = listOf(
            VpnProtocol.VLESS.code,
            VpnProtocol.TROJAN.code,
            VpnProtocol.SHADOWSOCKS.code,
            VpnProtocol.OPENVPN.code
        ),
        forceDnsViaProxy = true,
        preferTcp = true
    )

    private val CN = RegionPolicy(
        code = "CN",
        hiddenProtocols = setOf(
            VpnProtocol.WIREGUARD.code,
            VpnProtocol.AMNEZIA_WG.code,
            VpnProtocol.HYSTERIA2.code,
            VpnProtocol.TUIC.code,
            VpnProtocol.SHADOWSOCKS.code,
            VpnProtocol.OPENVPN.code
        ),
        preferredProtocols = listOf(
            VpnProtocol.VLESS.code,
            VpnProtocol.VMESS.code,
            VpnProtocol.TROJAN.code
        ),
        forceDnsViaProxy = true,
        preferTcp = true
    )

    private val IR = RegionPolicy(
        code = "IR",
        hiddenProtocols = setOf(
            VpnProtocol.WIREGUARD.code,
            VpnProtocol.AMNEZIA_WG.code,
            VpnProtocol.HYSTERIA2.code,
            VpnProtocol.TUIC.code
        ),
        preferredProtocols = listOf(
            VpnProtocol.VLESS.code,
            VpnProtocol.TROJAN.code,
            VpnProtocol.SHADOWSOCKS.code
        ),
        forceDnsViaProxy = true,
        preferTcp = true
    )

    private val POLICIES: Map<String, RegionPolicy> =
        listOf(RU, CN, IR).associateBy { it.code }

    fun of(code: String): RegionPolicy =
        POLICIES[code.uppercase()] ?: RegionPolicy.NEUTRAL

    /** Скрывает ли регион протокол. */
    fun isHidden(region: String, protocol: String): Boolean =
        protocol in of(region).hiddenProtocols
}

/** Применение политики региона к настройкам. */
object RegionTuner {
    /**
     * Включает принудительные настройки политики.
     *
     * Вызывается при смене региона (приветствие, настройки). Не разрушает
     * выбор пользователя: только те поля, которые политика требует включить
     * или выключить.
     */
    fun apply(config: AppConfig, region: String): AppConfig {
        val policy = RegionPolicies.of(region)
        val vpn = config.vpn.copy(
            dnsViaProxy = policy.forceDnsViaProxy || config.vpn.dnsViaProxy
        )
        return config.copy(region = region, vpn = vpn)
    }
}