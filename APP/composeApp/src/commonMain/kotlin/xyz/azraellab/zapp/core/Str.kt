package xyz.azraellab.zapp.core

/**
 * Строки интерфейса. Ключи -- элементы `enum`, а не строки: забытый перевод
 * тогда не собирается, а не показывается на экране как `COMMON_START`.
 *
 * Ключи с одинаковым текстом на всех языках (имена собственные, аббревиатуры,
 * названия флагов) отдаются одинаковыми тремя строками -- так их видно глазом.
 *
 * Правило содержания: на экране только то, что помогает что-то сделать или
 * найти. Слов про «локальный инструмент», «без сервера» и «без слежки» здесь
 * нет намеренно -- это рекламные обещания, а интерфейсу они ничего не сообщают.
 * Подпись кнопки говорит, что кнопка делает, и больше ничего.
 */
enum class Str {
    APP_NAME,

    /** Разделы нижней навигации. */
    TAB_VPN,
    TAB_ZAPRET,
    TAB_GOODBYE_DPI,
    TAB_GPS,
    TAB_TRAFFIC,
    TAB_SETTINGS,
    SETTINGS,

    /** Действия. */
    COMMON_CONNECT,
    COMMON_DISCONNECT,
    COMMON_START,
    COMMON_STOP,
    COMMON_ADD,
    COMMON_DELETE,
    COMMON_SAVE,
    COMMON_APPLY,
    COMMON_AUTODETECT,
    COMMON_TEST,
    COMMON_ADVANCED,
    COMMON_EXPORT,
    COMMON_IMPORT,
    COMMON_RESET,
    COMMON_CLOSE,

    /** Списки доменов и приложений. */
    LIST_DOMAINS,
    LIST_APPS,
    LIST_EXCLUSIONS,
    LIST_PROFILES,

    /** VPN. */
    VPN_PROFILES,
    VPN_ADD_PROFILE,
    VPN_NAME,
    VPN_PROTOCOL,
    VPN_TRANSPORT,
    VPN_GATEWAY,
    VPN_PORT,
    VPN_ADDRESS,
    VPN_DNS,
    VPN_PRIVATE_KEY,
    VPN_PEER_KEY,
    VPN_PRESHARED_KEY,
    VPN_MTU,
    VPN_KEEPALIVE,
    VPN_KILL_SWITCH,
    VPN_SPLIT_TUNNELING,
    VPN_AUTO_RECONNECT,
    VPN_BYPASS_LAN,
    VPN_ROUTES_ALL,
    VPN_INTERCEPT_DNS,

    /** Zapret. */
    ZAPRET_STRATEGY,
    ZAPRET_AUTO,
    ZAPRET_MODE,
    ZAPRET_FILTERS,
    ZAPRET_FILTER_TCP,
    ZAPRET_FILTER_UDP,
    ZAPRET_DPI_PORTS,
    ZAPRET_DPI_DESYNC,
    ZAPRET_DPI_SPLIT,
    ZAPRET_FAKE,
    ZAPRET_TTL,
    ZAPRET_WINDOW,
    ZAPRET_MSS,
    ZAPRET_HOST,
    ZAPRET_NEW_SYNTAX,
    ZAPRET_LOGGING,
    ZAPRET_ALL_TRAFFIC,
    ZAPRET_IPV4_ONLY,
    ZAPRET_MULTIPATH,
    ZAPRET_IPDIAG,
    ZAPRET_COMMAND,
    ZAPRET_PROBE_RUNNING,

    /** GoodbyeDPI. */
    DPI_MODE,
    DPI_SPLIT_POS,
    DPI_FAKE_SEQ,
    DPI_FAKE_CSUM,
    DPI_FAKE_SNI,
    DPI_DAEMON_PATH,
    DPI_AUTO_RESTART,

    /** GPS. */
    GPS_MODE,
    GPS_LATITUDE,
    GPS_LONGITUDE,
    GPS_ALTITUDE,
    GPS_SPEED,
    GPS_LOCATION,
    GPS_HEADING,
    GPS_FAKE_PROVIDER,
    GPS_ALLOW_MOCK,
    GPS_APP_WHITELIST,
    GPS_MOCK_APP,
    GPS_ROUTE_POINTS,
    GPS_ROUTE_SPEED,
    GPS_ROUTE_INTERVAL,
    GPS_ROUTE_FORMAT,
        GPS_ACCURACY,
    GPS_JITTER,
    GPS_ROUTE,
    GPS_ROUTE_LOOP,
    GPS_MOCK_TIME,
    GPS_PER_APP,
    GPS_REINSTALL,
    GPS_UNSUPPORTED_TITLE,
    GPS_UNSUPPORTED_DESC,
    GPS_VPN_HINT,

    /** Состояние подмены: показывается в строке состояния. */
    GPS_STATE_OFF,
    GPS_STATE_STARTING,
    GPS_STATE_ACTIVE,
    GPS_STATE_ERROR,

    /** Журнал подмены: события, которые видит пользователь. */
    GPS_LOG_STARTING,
    GPS_LOG_STOPPED,
    GPS_LOG_PROVIDER,
    GPS_LOG_NO_MOCK,
    GPS_LOG_RESOLVED,
    GPS_LOG_NO_GATEWAY,
    GPS_LOG_RESOLVE_FAIL,
    GPS_LOG_ROUTE_EMPTY,
    GPS_LOG_ROUTE_DONE,
    GPS_LOG_PROVIDER_ERROR,

    /** Демоны zapret и goodbyedpi: состояния и события журналов. */
    DAEMON_STATE_STOPPED,
    DAEMON_STATE_STARTING,
    DAEMON_STATE_RUNNING,
    DAEMON_STATE_ERROR,
    DAEMON_LOG_STARTING,
    DAEMON_LOG_RUNNING,
    DAEMON_LOG_STOPPED,
    DAEMON_LOG_EXIT,
    DAEMON_NO_BINARY,
    DAEMON_NO_SNI,
    DAEMON_BINARY,

    /** Мониторинг трафика. */
    TRAFFIC_TOTAL,
    TRAFFIC_SPEED,
    TRAFFIC_RX,
    TRAFFIC_TX,
    TRAFFIC_PEAK,
    TRAFFIC_AVERAGE,
    TRAFFIC_ACTIVE_TIME,
    TRAFFIC_INTERFACES,
    TRAFFIC_APPS,
    TRAFFIC_HISTORY,
    TRAFFIC_INTERVAL,
    TRAFFIC_POINTS,
    TRAFFIC_LIMIT,
    TRAFFIC_UNIT_BINARY,
    TRAFFIC_UNIT_DECIMAL,
    TRAFFIC_BASE_BITS,
    TRAFFIC_BASE_BYTES,
    TRAFFIC_IDLE,

    /** Настройки. */
    SETTINGS_LANGUAGE,
    SETTINGS_PRESETS,
    SETTINGS_PRESET_NAME,
    SETTINGS_ABOUT,
    SETTINGS_SYSTEM,
    SETTINGS_APPLY_LIVE,
    SETTINGS_LOG,
    SETTINGS_DOCS,
    SETTINGS_SECTION_GENERAL,
    ABOUT_VERSION,
    ABOUT_LICENSE,
    ABOUT_PLATFORM,

    /** Журнал (детальный лог). */
    LOG_DETAIL_ON,
    LOG_CLEAR,
    LOG_EMPTY,
    LOG_FILE_HINT,

    /** Приветствие и подсказки. */
    WELCOME_TITLE,
    WELCOME_SUBTITLE,
    WELCOME_NEXT,
    WELCOME_DONE,
    WELCOME_STEP_HINTS,
    WELCOME_HINT_VPN,
    WELCOME_HINT_ZAPRET,
    WELCOME_HINT_DPI,
    WELCOME_HINT_GPS,
    WELCOME_HINT_LOG,
    WELCOME_PERMISSIONS,

    /** Кнопки навигации. */
    COMMON_BACK,

    /** Пустые состояния. */
    LIST_EMPTY_PROFILES,
    LIST_EMPTY_PRESETS,

    /** Ошибки. */
    ERR_NO_ROOT,
    ERR_TUNNEL_FAILED,
    ERR_PRESET_BAD_FILE,
    ERR_PRESET_UNAVAILABLE,
    ERR_PRESET_INVALID,
    ERR_PRESET_SAVED,
    ERR_PRESET_APPLIED,
    ERR_PRESET_EXPORTED,
    ERR_PRESET_IMPORTED,
    ERR_PRESET_DELETED,
    ERR_PRESET_FAILED,
    ERR_PRESET_EMPTY,
    TRAFFIC_OPEN_WINDOW,
    TRAFFIC_PER_APP,
    TRAFFIC_SMOOTH,

    /** Разрешения. */
    SETTINGS_PERMISSIONS,
    PERM_GRANT,
    PERM_OPEN_SETTINGS,
    PERM_STATE_GRANTED,
    PERM_STATE_DENIED,
    PERM_STATE_SETTINGS,
    PERM_STATE_UNSUPPORTED,
    PERM_VPN,
    PERM_NOTIFICATIONS,
    PERM_LOCATION_FINE,
    PERM_LOCATION_MOCK,
    PERM_HINT_DENIED,
    PERM_HINT_MOCK,
    PERM_HINT_UNSUPPORTED,

    /** Разведка устройства. */
    SETTINGS_DEVICE,
    PROBE_YES,
    PROBE_NO,
    PROBE_UNKNOWN,
    PROBE_MODEL,
    PROBE_SYSTEM,
    PROBE_KERNEL,
    PROBE_ROOT,
    PROBE_MAGISK,
    PROBE_VPN,
    PROBE_API,

    /** Туннель. */
    ENGINE_STATE_DISCONNECTED,
    ENGINE_STATE_CONNECTING,
    ENGINE_STATE_CONNECTED,
    ENGINE_STATE_ERROR,
    ENGINE_LOG,
    ENGINE_LOG_EMPTY,
    ERR_TUNNEL_UNSUPPORTED,
    ERR_TUNNEL_ERROR,

    /** Обновления: проверка по релизам GitHub. */
    SETTINGS_UPDATES,
    UPD_CHECK,
    UPD_STATE_UNKNOWN,
    UPD_STATE_CHECKING,
    UPD_STATE_CURRENT,
    UPD_STATE_AVAILABLE,
    UPD_STATE_ERROR,
    UPD_OPEN,

    /** Строка состояния под навигацией. */
    STATUS_PERMS_NEEDED,
    STATUS_DPI,

    /** Названия протоколов -- как в спецификации, без перевода смысла. */
    PROTO_WIREGUARD,
    PROTO_AMNEZIA_WG,
    PROTO_OPENVPN,
    PROTO_VLESS,
    PROTO_VMESS,
    PROTO_TROJAN,
    PROTO_SHADOWSOCKS,
    PROTO_HYSTERIA2,
    PROTO_TUIC,
    PROTO_SINGBOX,
    PROTO_XRAY,
    PROTO_TOR,
    PROTO_SOCKS5,
    PROTO_HTTP,
    PROTO_SSH,

    /** Источники коннектов и проверка. */
    VPN_SOURCES,
    VPN_SOURCE_ADD,
    VPN_SOURCE_NAME,
    VPN_SOURCE_URL,
    VPN_SOURCE_REFRESH,
    VPN_SOURCE_REFRESHED,
    VPN_SOURCE_FAILED,
    VPN_SOURCE_REMOVED,
    VPN_SOURCE_EMPTY,
    VPN_ARCHIVE,
    VPN_ARCHIVE_EMPTY,
    VPN_RECHECK,
    VPN_CHECK_ALL,
    VPN_CHECKING,
    VPN_PING_LABEL,
    VPN_STATUS_ALIVE,
    VPN_STATUS_DEAD,
    VPN_STATUS_UNKNOWN,
    VPN_GROUP_MODE,
    VPN_TOR_ENABLED,
    VPN_ADD_LINKS,
    VPN_IMPORT_NONE,
    VPN_SORT_PING,
    VPN_SORT_NAME,
    VPN_MANUAL,
    VPN_ALIVE_COUNT,
    VPN_IMPORTED,
    VPN_SOURCE_ADDED,

    /** Тонкие настройки туннеля. */
    VPN_DNS_FAKE_IP,
    VPN_DNS_VIA_PROXY,
    VPN_CLASH_API,
    VPN_SECRET,
    VPN_TOR_SOCKS,
    VPN_AUTOSTART,
    VPN_ROUTING_HINT_DNS,

    /** Автоподбор стратегии обхода. */
    STRATEGY_PROBE,
    STRATEGY_APPLIED,
    STRATEGY_NETWORK_FAIL,

    /** Компоненты приложения: распакованные нативные бинари. */
    COMPONENTS,
    COMPONENTS_REINSTALL,
    COMPONENTS_ABSENT,
    DAEMON_AUTOSTART,

    /** Системные уведомления: текст отдаётся в нотификацию целиком. */
    NOTIFY_GPS_ON,
    NOTIFY_GPS_OFF,
    NOTIFY_GPS_ERROR,
    NOTIFY_UPDATE,
    NOTIFY_VPN_ON;

    /** Перевод для языка. */
    fun of(lang: AppLang): String = when (this) {
        APP_NAME -> "ZAPP"

        TAB_VPN -> pick(lang, "VPN", "VPN", "VPN")
        TAB_ZAPRET -> pick(lang, "Zapret", "Zapret", "Zapret")
        TAB_GOODBYE_DPI -> pick(lang, "GoodbyeDPI", "GoodbyeDPI", "GoodbyeDPI")
        TAB_GPS -> pick(lang, "GPS", "GPS", "GPS")
        TAB_TRAFFIC -> pick(lang, "Traffic", "Трафик", "流量")
        TAB_SETTINGS -> pick(lang, "Settings", "Настройки", "设置")
        SETTINGS -> pick(lang, "Settings", "Настройки", "设置")

        COMMON_CONNECT -> pick(lang, "Connect", "Подключить", "连接")
        COMMON_DISCONNECT -> pick(lang, "Disconnect", "Отключить", "断开")
        COMMON_START -> pick(lang, "START", "СТАРТ", "启动")
        COMMON_STOP -> pick(lang, "STOP", "СТОП", "停止")
        COMMON_ADD -> pick(lang, "Add", "Добавить", "添加")
        COMMON_DELETE -> pick(lang, "Delete", "Удалить", "删除")
        COMMON_SAVE -> pick(lang, "Save", "Сохранить", "保存")
        COMMON_APPLY -> pick(lang, "Apply", "Применить", "应用")
        COMMON_AUTODETECT -> pick(lang, "Auto", "Авто", "自动")
        COMMON_TEST -> pick(lang, "Test", "Проверить", "测试")
        COMMON_ADVANCED -> pick(lang, "Advanced", "Дополнительно", "高级")
        COMMON_EXPORT -> pick(lang, "Export", "Выгрузить", "导出")
        COMMON_IMPORT -> pick(lang, "Import", "Загрузить", "导入")
        COMMON_RESET -> pick(lang, "Reset", "Сбросить", "重置")
        COMMON_CLOSE -> pick(lang, "Close", "Закрыть", "关闭")
        COMMON_BACK -> pick(lang, "Back", "Назад", "返回")

        LIST_DOMAINS -> pick(lang, "Domains", "Домены", "域名")
        LIST_APPS -> pick(lang, "Applications", "Приложения", "应用")
        LIST_EXCLUSIONS -> pick(lang, "Exceptions", "Исключения", "例外")
        LIST_PROFILES -> pick(lang, "Profiles", "Профили", "配置")

        VPN_PROFILES -> pick(lang, "Profiles", "Профили", "配置")
        VPN_ADD_PROFILE -> pick(lang, "Add profile", "Добавить профиль", "添加配置")
        VPN_NAME -> pick(lang, "Name", "Имя", "名称")
        VPN_PROTOCOL -> pick(lang, "Protocol", "Протокол", "协议")
        VPN_TRANSPORT -> pick(lang, "Transport", "Транспорт", "传输")
        VPN_GATEWAY -> pick(lang, "Gateway", "Шлюз", "网关")
        VPN_PORT -> pick(lang, "Port", "Порт", "端口")
        VPN_ADDRESS -> pick(lang, "Address", "Адрес", "地址")
        VPN_DNS -> pick(lang, "DNS", "DNS", "DNS")
        VPN_PRIVATE_KEY -> pick(lang, "Private key", "Приватный ключ", "私钥")
        VPN_PEER_KEY -> pick(lang, "Peer key", "Ключ узла", "对端公钥")
        VPN_PRESHARED_KEY -> pick(lang, "Preshared key", "Общий ключ", "预共享密钥")
        VPN_MTU -> pick(lang, "MTU", "MTU", "MTU")
        VPN_KEEPALIVE -> pick(lang, "Keepalive, s", "Keepalive, с", "保活, 秒")
        VPN_KILL_SWITCH -> pick(lang, "Kill switch", "Kill switch", "断网保护")
        VPN_SPLIT_TUNNELING -> pick(lang, "Split tunneling", "Раздельное туннелирование", "分流")
        VPN_AUTO_RECONNECT -> pick(lang, "Auto reconnect", "Переподключение", "自动重连")
        VPN_BYPASS_LAN -> pick(lang, "Bypass local network", "Мимо локальной сети", "跳过局域网")
        VPN_ROUTES_ALL -> pick(lang, "Route all traffic", "Весь трафик в туннель", "全部流量走隧道")
        VPN_INTERCEPT_DNS -> pick(lang, "Intercept DNS", "Перехват DNS", "接管 DNS")

        ZAPRET_STRATEGY -> pick(lang, "Strategy", "Стратегия", "策略")
        ZAPRET_AUTO -> pick(lang, "Pick automatically", "Подбирать автоматически", "自动选择")
        ZAPRET_MODE -> pick(lang, "Mode", "Режим", "模式")
        ZAPRET_FILTERS -> pick(lang, "Filters", "Фильтры", "过滤器")
        ZAPRET_FILTER_TCP -> pick(lang, "TCP ports", "Порты TCP", "TCP 端口")
        ZAPRET_FILTER_UDP -> pick(lang, "UDP ports", "Порты UDP", "UDP 端口")
        ZAPRET_DPI_PORTS -> pick(lang, "DPI ports", "Порты DPI", "DPI 端口")
        ZAPRET_DPI_DESYNC -> pick(lang, "Desync methods", "Методы desync", "desync 方法")
        ZAPRET_DPI_SPLIT -> pick(lang, "Split", "Разрез", "分割")
        ZAPRET_FAKE -> pick(lang, "Fake packet", "Подмена пакета", "伪造包")
        ZAPRET_TTL -> pick(lang, "TTL", "TTL", "TTL")
        ZAPRET_WINDOW -> pick(lang, "Window", "Окно", "窗口")
        ZAPRET_MSS -> pick(lang, "MSS", "MSS", "MSS")
        ZAPRET_HOST -> pick(lang, "Host rules", "Правила по хостам", "主机规则")
        ZAPRET_NEW_SYNTAX -> pick(lang, "New list syntax", "Новый синтаксис списков", "新列表语法")
        ZAPRET_LOGGING -> pick(lang, "Logging", "Логи", "日志")
        ZAPRET_ALL_TRAFFIC -> pick(lang, "All traffic", "Весь трафик", "全部流量")
        ZAPRET_IPV4_ONLY -> pick(lang, "IPv4 only", "Только IPv4", "仅 IPv4")
        ZAPRET_MULTIPATH -> pick(lang, "Multipath", "Мультипуть", "多路径")
        ZAPRET_IPDIAG -> pick(lang, "IP diagnostic", "IP-диагностика", "IP 诊断")
        ZAPRET_COMMAND -> pick(lang, "Command", "Команда", "命令")
        ZAPRET_PROBE_RUNNING -> pick(lang, "Checking network", "Проверка сети", "检查网络")

        DPI_MODE -> pick(lang, "Mode", "Режим", "模式")
        DPI_SPLIT_POS -> pick(lang, "Split position", "Позиция разреза", "分割位置")
        DPI_FAKE_SEQ -> pick(lang, "Fake sequence", "Подмена номера", "伪造序号")
        DPI_FAKE_CSUM -> pick(lang, "Fake checksum", "Подмена контрольной суммы", "伪造校验和")
        DPI_FAKE_SNI -> pick(lang, "Fake SNI domain", "Домен для Fake SNI", "Fake SNI 域名")
        DPI_DAEMON_PATH -> pick(lang, "Daemon path", "Путь к демону", "守护进程路径")
        DPI_AUTO_RESTART -> pick(lang, "Restart daemon", "Перезапуск демона", "重启守护进程")

        GPS_MODE -> pick(lang, "Mode", "Режим", "模式")
        GPS_LATITUDE -> pick(lang, "Latitude", "Широта", "纬度")
        GPS_LONGITUDE -> pick(lang, "Longitude", "Долгота", "经度")
        GPS_ALTITUDE -> pick(lang, "Altitude, m", "Высота, м", "海拔, 米")
        GPS_SPEED -> pick(lang, "Speed, m/s", "Скорость, м/с", "速度, 米/秒")
        GPS_ACCURACY -> pick(lang, "Accuracy, m", "Точность, м", "精度, 米")
        GPS_JITTER -> pick(lang, "Position jitter, m", "Разброс точки, м", "位置抖动, 米")
        GPS_ROUTE -> pick(lang, "Route", "Маршрут", "路线")
        GPS_ROUTE_LOOP -> pick(lang, "Loop route", "Маршрут по кругу", "循环路线")
        GPS_MOCK_TIME -> pick(lang, "Mock time, s", "Время фиксации, с", "模拟时间, 秒")
        GPS_PER_APP -> pick(lang, "Only selected apps", "Только выбранные приложения", "仅指定应用")
        GPS_REINSTALL -> pick(lang, "Reinstall provider", "Переустановить провайдер", "重建设置提供者")

        TRAFFIC_TOTAL -> pick(lang, "Total", "Всего", "总计")
        TRAFFIC_SPEED -> pick(lang, "Speed", "Скорость", "速度")
        TRAFFIC_RX -> pick(lang, "Received", "Принято", "接收")
        TRAFFIC_TX -> pick(lang, "Sent", "Отправлено", "发送")
        TRAFFIC_PEAK -> pick(lang, "Peak", "Пик", "峰值")
        TRAFFIC_AVERAGE -> pick(lang, "Average", "Средняя", "平均")
        TRAFFIC_ACTIVE_TIME -> pick(lang, "Active time", "Активное время", "活跃时长")
        TRAFFIC_INTERFACES -> pick(lang, "Interfaces", "Интерфейсы", "接口")
        TRAFFIC_APPS -> pick(lang, "Applications", "Приложения", "应用")
        TRAFFIC_HISTORY -> pick(lang, "History", "История", "历史")
        TRAFFIC_INTERVAL -> pick(lang, "Interval, ms", "Интервал, мс", "间隔, 毫秒")
        TRAFFIC_POINTS -> pick(lang, "Points", "Точек", "点数")
        TRAFFIC_LIMIT -> pick(lang, "Monthly limit", "Лимит за месяц", "每月上限")
        TRAFFIC_UNIT_BINARY -> pick(lang, "Binary (KiB/MiB)", "Бинарная (KiB/MiB)", "二进制 (KiB/MiB)")
        TRAFFIC_UNIT_DECIMAL -> pick(lang, "Decimal (kB/MB)", "Десятичная (kB/MB)", "十进制 (kB/MB)")
        TRAFFIC_BASE_BITS -> pick(lang, "bit/s", "бит/с", "比特/秒")
        TRAFFIC_BASE_BYTES -> pick(lang, "byte/s", "байт/с", "字节/秒")
        TRAFFIC_IDLE -> pick(lang, "No traffic", "Трафика нет", "无流量")
        TRAFFIC_OPEN_WINDOW -> pick(lang, "Open monitor", "Открыть мониторинг", "打开监控")
        TRAFFIC_PER_APP -> pick(lang, "Per-app breakdown", "По приложениям", "按应用")
        TRAFFIC_SMOOTH -> pick(lang, "Smooth graph", "Сглаживать график", "平滑曲线")

        SETTINGS_LANGUAGE -> pick(lang, "Language", "Язык", "语言")
        SETTINGS_PRESETS -> pick(lang, "Presets", "Пресеты", "预设")
        SETTINGS_PRESET_NAME -> pick(lang, "Preset name", "Имя пресета", "预设名称")
        SETTINGS_ABOUT -> pick(lang, "About", "О программе", "关于")
        SETTINGS_LOG -> pick(lang, "Log", "Журнал", "日志")
        SETTINGS_DOCS -> pick(lang, "Documentation", "Документация", "文档")
        SETTINGS_SECTION_GENERAL -> pick(lang, "General", "Основное", "常规")
        SETTINGS_SYSTEM -> pick(lang, "System", "Система", "系统")
        SETTINGS_APPLY_LIVE -> pick(lang, "Apply on change", "Применять сразу", "立即应用")
        LOG_DETAIL_ON -> pick(lang, "Detailed log", "Детальный журнал", "详细日志")
        LOG_CLEAR -> pick(lang, "Clear log", "Очистить журнал", "清空日志")
        LOG_EMPTY -> pick(lang, "Nothing logged yet", "Записей пока нет", "暂无记录")
        LOG_FILE_HINT -> pick(lang, "Saved to file zapp.log", "Сохраняется в файл zapp.log", "保存到文件 zapp.log")
        WELCOME_TITLE -> pick(lang, "Welcome to ZAPP", "Добро пожаловать в ZAPP", "欢迎使用 ZAPP")
        WELCOME_SUBTITLE -> pick(
            lang,
            "Bypass, VPN and GPS in one app. A short tour and permissions first.",
            "Обход блокировок, VPN и GPS в одном приложении. Сначала короткий тур и разрешения.",
            "绕过封锁、VPN 与 GPS 集于一应俱全。先了解概览并授予权限。"
        )
        WELCOME_NEXT -> pick(lang, "Next", "Далее", "下一步")
        WELCOME_DONE -> pick(lang, "Get started", "Начать", "开始使用")
        WELCOME_STEP_HINTS -> pick(lang, "What the tabs do", "Что делают вкладки", "各标签页的功能")
        WELCOME_HINT_VPN -> pick(
            lang,
            "VPN: import links or sources, pick a live connect, press START.",
            "VPN: добавьте ссылки или источники, выберите живой коннект, нажмите СТАРТ.",
            "VPN：导入链接或源，选择可用节点，点击启动。"
        )
        WELCOME_HINT_ZAPRET -> pick(
            lang,
            "Zapret: DPI bypass rules for throttled resources (YouTube, Discord).",
            "Zapret: правила обхода DPI для ресурсов с замедлением (YouTube, Discord).",
            "Zapret：针对限速资源（YouTube、Discord）的 DPI 绕过规则。"
        )
        WELCOME_HINT_DPI -> pick(
            lang,
            "GoodbyeDPI: another bypass engine, useful when zapret is not enough.",
            "GoodbyeDPI: ещё один движок обхода, полезен, когда zapret не хватает.",
            "GoodbyeDPI：另一绕过引擎，zapret 不够用时可尝试。"
        )
        WELCOME_HINT_GPS -> pick(
            lang,
            "GPS: mock coordinates for apps that check your location.",
            "GPS: подмена координат для приложений, проверяющих местоположение.",
            "GPS：为检查定位的应用伪造坐标。"
        )
        WELCOME_HINT_LOG -> pick(
            lang,
            "Every action and error lands in the Log tab in Settings.",
            "Каждое действие и ошибка попадают в раздел «Журнал» в настройках.",
            "所有操作与错误都会记录在设置的“日志”中。"
        )
        WELCOME_PERMISSIONS -> pick(
            lang,
            "Grant the permissions so the app works. You can review them later in Settings.",
            "Выдайте разрешения, чтобы приложение работало. Позже их можно проверить в настройках.",
            "请授予权限以便应用正常运行。之后可在设置中查看。"
        )
        ABOUT_VERSION -> pick(lang, "Version", "Версия", "版本")
        ABOUT_LICENSE -> pick(lang, "License", "Лицензия", "许可")
        ABOUT_PLATFORM -> pick(lang, "Platform", "Платформа", "平台")

        GPS_LOCATION -> pick(lang, "Location", "Местоположение", "位置")
        GPS_HEADING -> pick(lang, "Heading", "Направление", "方向")
        GPS_FAKE_PROVIDER -> pick(lang, "Fake GPS provider", "Фейковый провайдер GPS", "模拟 GPS 提供器")
        GPS_ALLOW_MOCK -> pick(lang, "Allow mock locations", "Разрешить фиктивные координаты", "允许模拟位置")
        GPS_APP_WHITELIST -> pick(lang, "Apps whitelist", "Разрешённые приложения", "白名单应用")
        GPS_MOCK_APP -> pick(lang, "Mock app", "Приложение-эмулятор", "模拟定位应用")
        GPS_ROUTE_POINTS -> pick(lang, "Route points", "Точки маршрута", "路线点")
        GPS_ROUTE_SPEED -> pick(lang, "Route speed, m/s", "Скорость маршрута, м/с", "路线速度, 米/秒")
        GPS_ROUTE_INTERVAL -> pick(lang, "Update interval, s", "Интервал обновления, с", "更新间隔, 秒")
        GPS_ROUTE_FORMAT -> pick(lang, "One point per line (lat,lon)", "Одна точка в строке (широта,долгота)", "每行一个点 (纬度,经度)")
        GPS_UNSUPPORTED_TITLE -> pick(lang, "GPS spoofing not supported", "Подмена GPS не поддерживается", "不支持 GPS 伪造")
        GPS_UNSUPPORTED_DESC -> pick(lang, "This feature is only available on Android", "Эта функция доступна только на Android", "此功能仅在 Android 上可用")
        GPS_VPN_HINT -> pick(lang, "Coordinates follow the connected gateway: the point is resolved when spoofing starts",
            "Координаты следуют за подключённым шлюзом: точка определяется при старте подмены",
            "坐标跟随已连接的网关：启动伪造时解析坐标")
        GPS_STATE_OFF -> pick(lang, "Off", "Выключено", "已关闭")
        GPS_STATE_STARTING -> pick(lang, "Starting", "Запуск", "启动中")
        GPS_STATE_ACTIVE -> pick(lang, "Active", "Активна", "生效中")
        GPS_STATE_ERROR -> pick(lang, "Error", "Ошибка", "错误")
        GPS_LOG_STARTING -> pick(lang, "Spoofing: start", "Подмена: старт", "伪造：启动")
        GPS_LOG_STOPPED -> pick(lang, "Spoofing: stopped", "Подмена: остановлена", "伪造：已停止")
        GPS_LOG_PROVIDER -> pick(lang, "Mock provider ready", "Тест-провайдер готов", "模拟提供者就绪")
        GPS_LOG_NO_MOCK -> pick(lang, "Mock location permission missing", "Нет разрешения на имитацию местоположения", "缺少模拟位置权限")
        GPS_LOG_RESOLVED -> pick(lang, "VPN point", "Точка VPN", "VPN 点")
        GPS_LOG_NO_GATEWAY -> pick(lang, "VPN gateway address not set", "Адрес шлюза VPN не задан", "未设置 VPN 网关地址")
        GPS_LOG_RESOLVE_FAIL -> pick(lang, "Could not geolocate the gateway", "Не удалось определить координаты шлюза", "无法定位网关坐标")
        GPS_LOG_ROUTE_EMPTY -> pick(lang, "Route has no points", "Маршрут не содержит точек", "路线没有点")
        GPS_LOG_ROUTE_DONE -> pick(lang, "Route completed", "Маршрут пройден", "路线已完成")
        GPS_LOG_PROVIDER_ERROR -> pick(lang, "Provider rejected the fix", "Провайдер отклонил координаты", "提供者拒绝坐标")

        DAEMON_STATE_STOPPED -> pick(lang, "Stopped", "Остановлен", "已停止")
        DAEMON_STATE_STARTING -> pick(lang, "Starting", "Запуск", "启动中")
        DAEMON_STATE_RUNNING -> pick(lang, "Running", "Работает", "运行中")
        DAEMON_STATE_ERROR -> pick(lang, "Error", "Ошибка", "错误")
        DAEMON_LOG_STARTING -> pick(lang, "Starting", "Запуск", "启动")
        DAEMON_LOG_RUNNING -> pick(lang, "Running", "Работает", "运行中")
        DAEMON_LOG_STOPPED -> pick(lang, "Stopped", "Остановлен", "已停止")
        DAEMON_LOG_EXIT -> pick(lang, "Exit code", "Код выхода", "退出码")
        DAEMON_NO_BINARY -> pick(lang, "Binary not found", "Бинарь не найден", "找不到可执行文件")
        DAEMON_NO_SNI -> pick(lang, "Fake SNI mode needs a domain", "Режиму Fake SNI нужен домен", "Fake SNI 模式需要域名")
        DAEMON_BINARY -> pick(lang, "Binary path", "Путь к бинарю", "可执行文件路径")
        LIST_EMPTY_PROFILES -> pick(lang, "No profiles", "Профилей нет", "没有配置")
        LIST_EMPTY_PRESETS -> pick(lang, "No presets", "Пресетов нет", "没有预设")

        ERR_NO_ROOT -> pick(lang, "Root access required", "Нужны права root", "需要 root 权限")
        ERR_TUNNEL_FAILED -> pick(lang, "Tunnel failed to start", "Не удалось поднять туннель", "隧道启动失败")
        ERR_PRESET_BAD_FILE -> pick(lang, "Not a preset file", "Это не файл пресета", "不是预设文件")
        ERR_PRESET_UNAVAILABLE -> pick(lang, "File access unavailable", "Доступ к файлам недоступен", "无法访问文件")
        ERR_PRESET_INVALID -> pick(lang, "Invalid values", "Недопустимые значения", "值不合法")
        ERR_PRESET_SAVED -> pick(lang, "Preset saved", "Пресет сохранён", "预设已保存")
        ERR_PRESET_APPLIED -> pick(lang, "Preset applied", "Пресет применён", "预设已应用")
        ERR_PRESET_EXPORTED -> pick(lang, "Preset exported", "Пресет выгружен", "预设已导出")
        ERR_PRESET_IMPORTED -> pick(lang, "Preset imported", "Пресет загружен", "预设已导入")
        ERR_PRESET_DELETED -> pick(lang, "Preset deleted", "Пресет удалён", "预设已删除")
        ERR_PRESET_FAILED -> pick(lang, "Operation failed", "Операция не удалась", "操作失败")
        ERR_PRESET_EMPTY -> pick(lang, "File is empty", "Файл пуст", "文件为空")

        SETTINGS_PERMISSIONS -> pick(lang, "Permissions", "Разрешения", "权限")
        PERM_GRANT -> pick(lang, "Grant", "Разрешить", "允许")
        PERM_OPEN_SETTINGS -> pick(lang, "Open settings", "Открыть настройки", "打开设置")
        PERM_STATE_GRANTED -> pick(lang, "Granted", "Дано", "已允许")
        PERM_STATE_DENIED -> pick(lang, "Not granted", "Не дано", "未允许")
        PERM_STATE_SETTINGS -> pick(lang, "Via system settings", "Через системные настройки", "需系统设置")
        PERM_STATE_UNSUPPORTED -> pick(lang, "Not on this platform", "Нет на этой платформе", "本平台不支持")
        PERM_VPN -> pick(lang, "VPN tunnel", "Туннель VPN", "VPN 隧道")
        PERM_NOTIFICATIONS -> pick(lang, "Notifications", "Уведомления", "通知")
        PERM_LOCATION_FINE -> pick(lang, "Precise location", "Точное местоположение", "精确位置")
        PERM_LOCATION_MOCK -> pick(lang, "Mock location", "Имитация местоположения", "模拟位置")
        PERM_HINT_DENIED -> pick(lang, "The system dialog was rejected", "Системный диалог отклонён", "系统对话框被拒绝")
        PERM_HINT_MOCK -> pick(lang, "Pick this app as the mock location app in the developer settings",
            "Укажите это приложение для имитации в настройках разработчика", "请在开发者选项中选择本应用进行模拟")
        PERM_HINT_UNSUPPORTED -> pick(lang, "The platform has no such permission", "На платформе нет такого разрешения", "平台无此权限")

        SETTINGS_DEVICE -> pick(lang, "Device", "Устройство", "设备")
        PROBE_YES -> pick(lang, "Yes", "Да", "有")
        PROBE_NO -> pick(lang, "No", "Нет", "无")
        PROBE_UNKNOWN -> pick(lang, "Not checked", "Не проверено", "未检查")
        PROBE_MODEL -> pick(lang, "Model", "Модель", "型号")
        PROBE_SYSTEM -> pick(lang, "System", "Система", "系统")
        PROBE_KERNEL -> pick(lang, "Kernel", "Ядро", "内核")
        PROBE_ROOT -> pick(lang, "Root", "Root", "Root")
        PROBE_MAGISK -> pick(lang, "Magisk", "Magisk", "Magisk")
        PROBE_VPN -> pick(lang, "VPN API", "VPN API", "VPN API")
        PROBE_API -> pick(lang, "API level", "Уровень API", "API 级别")

        ENGINE_STATE_DISCONNECTED -> pick(lang, "Off", "Выключен", "已断开")
        ENGINE_STATE_CONNECTING -> pick(lang, "Connecting...", "Подключение...", "连接中...")
        ENGINE_STATE_CONNECTED -> pick(lang, "Connected", "Подключено", "已连接")
        ENGINE_STATE_ERROR -> pick(lang, "Error", "Ошибка", "出错")
        ENGINE_LOG -> pick(lang, "Log", "Журнал", "日志")
        ENGINE_LOG_EMPTY -> pick(lang, "Nothing yet", "Пока пусто", "暂无内容")
        ERR_TUNNEL_UNSUPPORTED -> pick(lang, "Tunnel is not available on this platform",
            "Туннель недоступен на этой платформе", "本平台不支持隧道")
        ERR_TUNNEL_ERROR -> pick(lang, "Connection error", "Ошибка подключения", "连接错误")

        SETTINGS_UPDATES -> pick(lang, "Updates", "Обновления", "更新")
        UPD_CHECK -> pick(lang, "Check for updates", "Проверить обновления", "检查更新")
        UPD_STATE_UNKNOWN -> pick(lang, "Not checked", "Не проверено", "未检查")
        UPD_STATE_CHECKING -> pick(lang, "Checking...", "Проверка...", "检查中...")
        UPD_STATE_CURRENT -> pick(lang, "Up to date", "Уже актуально", "已是最新")
        UPD_STATE_AVAILABLE -> pick(lang, "Update available", "Доступно обновление", "有可用更新")
        UPD_STATE_ERROR -> pick(lang, "Check failed", "Не удалось проверить", "检查失败")
        UPD_OPEN -> pick(lang, "Open release page", "Открыть страницу релиза", "打开发布页")
        STATUS_PERMS_NEEDED -> pick(lang, "Permissions needed", "Нужны разрешения", "需要权限")
        STATUS_DPI -> pick(lang, "DPI", "DPI", "DPI")
        NOTIFY_GPS_ON -> pick(lang, "GPS spoofing on", "Подмена GPS включена", "GPS 伪造已开启")
        NOTIFY_GPS_OFF -> pick(lang, "GPS spoofing off", "Подмена GPS выключена", "GPS 伪造已关闭")
        NOTIFY_GPS_ERROR -> pick(lang, "GPS spoofing failed", "Подмена GPS не запустилась", "GPS 伪造失败")
        NOTIFY_UPDATE -> pick(lang, "Update available", "Доступно обновление", "有可用更新")
        NOTIFY_VPN_ON -> pick(lang, "VPN tunnel is active", "VPN-туннель активен", "VPN 隧道已启用")

        PROTO_WIREGUARD -> pick(lang, "WireGuard", "WireGuard", "WireGuard")
        PROTO_AMNEZIA_WG -> pick(lang, "Amnezia WG", "Amnezia WG", "Amnezia WG")
        PROTO_OPENVPN -> pick(lang, "OpenVPN", "OpenVPN", "OpenVPN")
        PROTO_VLESS -> "VLESS"
        PROTO_VMESS -> "VMess"
        PROTO_TROJAN -> "Trojan"
        PROTO_SHADOWSOCKS -> pick(lang, "Shadowsocks", "Shadowsocks", "Shadowsocks")
        PROTO_HYSTERIA2 -> pick(lang, "Hysteria 2", "Hysteria 2", "Hysteria 2")
        PROTO_TUIC -> "TUIC"
        PROTO_SINGBOX -> pick(lang, "Sing-box", "Sing-box", "Sing-box")
        PROTO_XRAY -> "Xray"
        PROTO_TOR -> "Tor"
        PROTO_SOCKS5 -> "SOCKS5"
        PROTO_HTTP -> "HTTP"
        PROTO_SSH -> "SSH"

        VPN_SOURCES -> pick(lang, "Sources", "Источники", "来源")
        VPN_SOURCE_ADD -> pick(lang, "Add source", "Добавить источник", "添加来源")
        VPN_SOURCE_NAME -> pick(lang, "Source name", "Название источника", "来源名称")
        VPN_SOURCE_URL -> pick(lang, "Subscription URL", "URL подписки", "订阅链接")
        VPN_SOURCE_REFRESH -> pick(lang, "Update", "Обновить", "更新")
        VPN_SOURCE_REFRESHED -> pick(lang, "Updated: links", "Обновлено: ссылок", "已更新：链接")
        VPN_SOURCE_FAILED -> pick(lang, "Fetch failed", "Не удалось загрузить", "获取失败")
        VPN_SOURCE_REMOVED -> pick(lang, "Source removed", "Источник удалён", "来源已删除")
        VPN_SOURCE_EMPTY -> pick(lang, "No links in source", "В источнике нет ссылок", "来源中没有链接")
        VPN_ARCHIVE -> pick(lang, "Archive", "Архив", "归档")
        VPN_ARCHIVE_EMPTY -> pick(lang, "Archive is empty", "Архив пуст", "归档为空")
        VPN_RECHECK -> pick(lang, "Recheck", "Проверить снова", "重新检查")
        VPN_CHECK_ALL -> pick(lang, "Check all", "Проверить все", "全部检查")
        VPN_CHECKING -> pick(lang, "Checking", "Проверка", "检查中")
        VPN_PING_LABEL -> pick(lang, "Ping", "Пинг", "延迟")
        VPN_STATUS_ALIVE -> pick(lang, "Alive", "Жив", "存活")
        VPN_STATUS_DEAD -> pick(lang, "Dead", "Мёртв", "失效")
        VPN_STATUS_UNKNOWN -> pick(lang, "Not checked", "Не проверялся", "未检查")
        VPN_GROUP_MODE -> pick(lang, "Auto-pick best", "Автовыбор лучшего", "自动选择最优")
        VPN_TOR_ENABLED -> pick(lang, "Tor bridges", "Мосты Tor", "Tor 桥接")
        VPN_ADD_LINKS -> pick(lang, "Add links", "Добавить ссылки", "添加链接")
        VPN_IMPORT_NONE -> pick(lang, "Nothing recognized", "Текст не распознан", "无法识别文本")
        VPN_SORT_PING -> pick(lang, "By ping", "По пингу", "按延迟")
        VPN_SORT_NAME -> pick(lang, "By name", "По имени", "按名称")
        VPN_MANUAL -> pick(lang, "Manual", "Вручную", "手动")
        VPN_ALIVE_COUNT -> pick(lang, "Alive", "Живых", "存活")
        VPN_IMPORTED -> pick(lang, "Imported", "Импортировано", "已导入")
        VPN_SOURCE_ADDED -> pick(lang, "Source added", "Источник добавлен", "来源已添加")

        VPN_DNS_FAKE_IP -> pick(lang, "Fake-IP DNS", "Fake-IP DNS", "Fake-IP DNS")
        VPN_DNS_VIA_PROXY -> pick(lang, "DNS through tunnel", "DNS через туннель", "DNS 走隧道")
        VPN_CLASH_API -> pick(lang, "Clash API (monitoring)", "Clash API (мониторинг)", "Clash API（监控）")
        VPN_SECRET -> pick(lang, "Secret", "Секрет", "密钥")
        VPN_TOR_SOCKS -> pick(lang, "Tor SOCKS port", "SOCKS-порт Tor", "Tor SOCKS 端口")
        VPN_AUTOSTART -> pick(lang, "Start on launch", "Старт при запуске", "启动时自动连接")
        VPN_ROUTING_HINT_DNS -> pick(
            lang,
            "DNS interception works with the sing-box backend; kernel WireGuard keeps the system resolver",
            "Перехват DNS работает на движке sing-box; ядерный WireGuard оставляет системный резолвер",
            "DNS 拦截仅在 sing-box 引擎生效；内核 WireGuard 使用系统解析器"
        )

        STRATEGY_PROBE -> pick(lang, "Detect strategy", "Подобрать стратегию", "检测策略")
        STRATEGY_APPLIED -> pick(lang, "Strategy applied", "Стратегия применена", "策略已应用")
        STRATEGY_NETWORK_FAIL -> pick(
            lang,
            "Network check failed",
            "Не удалось проверить сеть",
            "网络检查失败"
        )
        COMPONENTS -> pick(lang, "Components", "Компоненты", "组件")
        COMPONENTS_REINSTALL -> pick(lang, "Reinstall", "Переустановить", "重新安装")
        COMPONENTS_ABSENT -> pick(lang, "Not installed", "Не установлены", "未安装")
        DAEMON_AUTOSTART -> pick(lang, "Start on launch", "Старт при запуске", "启动时自动运行")
    }

    /**
     * Три языка одним вызовом.
     *
     * Порядок аргументов всегда EN, RU, ZH, поэтому перестановка языков в
     * вызове даст ошибку компиляции, а не перепутанный текст в интерфейсе.
     */
    private fun pick(lang: AppLang, en: String, ru: String, zh: String): String = when (lang) {
        AppLang.EN -> en
        AppLang.RU -> ru
        AppLang.ZH -> zh
    }
}
