package xyz.azraellab.zapp.core

/**
 * Справка по функциям и настройкам (вкладка «Документация»).
 *
 * Текст живёт отдельно от [Str]: справка -- не кнопки, а абзацы, и держать
 * их в enum означало бы превратить его в рукописный справочник.
 * Локализация та же тройка: en / ru / zh.
 */
object Docs {

    /** Тройка текстов на языках. */
    data class L(val en: String, val ru: String, val zh: String) {
        fun pick(lang: AppLang): String = when (lang) {
            AppLang.EN -> en
            AppLang.RU -> ru
            AppLang.ZH -> zh
        }
    }

    data class Item(val title: L, val body: L)

    data class Section(val title: L, val items: List<Item>)

    val sections: List<Section> = listOf(
        Section(
            L("VPN", "VPN", "VPN"),
            listOf(
                Item(
                    L("Connect", "Подключение", "连接"),
                    L(
                        "Choose a connect from the list (or enable group mode for automatic selection) and press START. The button СТОП tears the tunnel down. The status and the reason for an error are shown right under the button.",
                        "Выберите коннект из списка (или включите режим группы для автовыбора) и нажмите СТАРТ. Кнопка СТОП рвёт туннель. Статус и причина ошибки видны прямо под кнопкой.",
                        "从列表中选择节点（或开启分组模式自动选择），点击启动。停止按钮会断开隧道。状态和错误原因直接显示在按钮下方。"
                    )
                ),
                Item(
                    L("Live check", "Проверка в живую", "在线检测"),
                    L(
                        "«Check all» pings every connect through the server; the result is the ping and alive/dead. Dead connects move to the archive with the reason and a recheck button.",
                        "«Проверить все» пингует каждый коннект через сервер; результат -- пинг и жив/мёртв. Мёртвые уезжают в архив с причиной и кнопкой перепроверки.",
                        "“全部检测”通过服务器为每个节点测速；结果为延迟与存活状态。失效节点会移入归档并附原因与重新检测按钮。"
                    )
                ),
                Item(
                    L("Sources", "Источники", "源"),
                    L(
                        "A source is a URL that yields links: a plain list, a subscription, or Tor bridges. Update and delete live in the source row. Own links can be added manually.",
                        "Источник -- URL, из которого берутся ссылки: обычный список, подписка или мосты Tor. Обновление и удаление -- в строке источника. Свои ссылки можно добавить вручную.",
                        "源是提供链接的网址：普通列表、订阅或 Tor 桥接。更新与删除位于源行中，也可手动添加自有链接。"
                    )
                ),
                Item(
                    L("Kill switch", "Kill switch", "防火墙开关"),
                    L(
                        "With root, iptables blocks all outbound traffic except the connect endpoints while the tunnel is up. If the tunnel dies, nothing leaks.",
                        "При наличии root iptables блокирует весь исходящий трафик, кроме адресов коннекта, пока туннель поднят. Если туннель упал -- ничего не утекает.",
                        "有 root 时，iptables 除节点地址外阻断全部出站流量。隧道断开也不会泄露。"
                    )
                )
            )
        ),
        Section(
            L("Zapret", "Zapret", "Zapret"),
            listOf(
                Item(
                    L("Purpose", "Назначение", "用途"),
                    L(
                        "DPI bypass at the packet level: split, fake packets, delays. It unblocks throttled resources (YouTube, Discord) without a VPN.",
                        "Обход DPI на уровне пакетов: разделение, подмена, задержки. Разблокирует ресурсы с замедлением (YouTube, Discord) без VPN.",
                        "在数据包层面绕过 DPI：分片、伪造、延迟。无需 VPN 即可解锁被限速的资源（YouTube、Discord）。"
                    )
                ),
                Item(
                    L("Strategy", "Стратегия", "策略"),
                    L(
                        "«Detect strategy» tests presets against a reference site and applies the one that works. Presets are also available from zapret-discord-youtube.",
                        "«Подобрать стратегию» проверяет пресеты на эталонном сайте и применяет рабочий. Пресеты также есть в zapret-discord-youtube.",
                        "“检测策略”会对照参考站点测试预设并应用有效者。zapret-discord-youtube 也提供预设。"
                    )
                )
            )
        ),
        Section(
            L("GoodbyeDPI", "GoodbyeDPI", "GoodbyeDPI"),
            listOf(
                Item(
                    L("Modes", "Режимы", "模式"),
                    L(
                        "A second bypass engine next to zapret: AUTO picks modes by itself, Fake SNI fakes the TLS handshake domain (a domain is required), DISABLED stops the daemon.",
                        "Второй движок обхода рядом с zapret: AUTO выбирает режимы сам, Fake SNI подменяет домен в TLS-рукопожатии (домен обязателен), DISABLED останавливает демон.",
                        "zapret 之外的第二绕过引擎：AUTO 自动选择模式，Fake SNI 伪造 TLS 握手域名（必须填写域名），DISABLED 停止守护进程。"
                    )
                )
            )
        ),
        Section(
            L("GPS", "GPS", "GPS"),
            listOf(
                Item(
                    L("Spoofing", "Подмена", "伪装"),
                    L(
                        "Mock location for apps that check coordinates. Needs the location permission and the mock-location app set in developer settings. «By VPN» sends the connect gateway coordinates.",
                        "Подмена координат для приложений, проверяющих местоположение. Нужно разрешение на гео и приложение-имитатор в настройках разработчика. «Через VPN» подставляет координаты шлюза коннекта.",
                        "为检查定位的应用伪造坐标。需要定位权限并在开发者设置中选择模拟应用。“通过 VPN”使用节点网关坐标。"
                    )
                )
            )
        ),
        Section(
            L("Presets", "Пресеты", "预设"),
            listOf(
                Item(
                    L("Save and share", "Сохранение и перенос", "保存与迁移"),
                    L(
                        "A preset is a named snapshot of bypass rules and VPN profiles. Export/import moves it between devices as a single file.",
                        "Пресет -- именованный снимок правил обхода и VPN-профилей. Экспорт/импорт переносит его между устройствами одним файлом.",
                        "预设是绕过规则与 VPN 配置的命名快照。导出/导入可用单个文件在设备间迁移。"
                    )
                )
            )
        ),
        Section(
            L("Log", "Журнал", "日志"),
            listOf(
                Item(
                    L("What is recorded", "Что записывается", "记录内容"),
                    L(
                        "Every action and error: starts, stops, imports, checks, tunnel and daemon output. Turn it off in Settings > Log if you do not need it; the buffer is also saved to the file zapp.log.",
                        "Каждое действие и ошибка: старты, остановки, импорты, проверки, вывод туннеля и демонов. Выключить можно в Настройки > Журнал; буфер также пишется в файл zapp.log.",
                        "记录所有操作与错误：启动、停止、导入、检测、隧道与守护进程输出。可在“设置 > 日志”关闭；缓冲同时写入 zapp.log 文件。"
                    )
                )
            )
        ),
        Section(
            L("Permissions", "Разрешения", "权限"),
            listOf(
                Item(
                    L("Why they are needed", "Зачем нужны", "用途"),
                    L(
                        "VPN -- system consent for the tunnel; Notifications -- foreground service and GPS messages; Location and mock location -- GPS spoofing. Everything can be granted later in Settings > Permissions.",
                        "VPN -- системное согласие на туннель; Уведомления -- foreground-сервис и сообщения GPS; Гео и имитация -- подмена GPS. Всё можно выдать позже в Настройки > Разрешения.",
                        "VPN：隧道的系统授权；通知：前台服务与 GPS 消息；定位与模拟定位：GPS 伪装。均可稍后在“设置 > 权限”中授予。"
                    )
                )
            )
        )
    )
}
