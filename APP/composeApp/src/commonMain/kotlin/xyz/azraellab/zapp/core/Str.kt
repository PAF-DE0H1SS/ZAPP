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
    COMMON_EXPORT,
    COMMON_IMPORT,
    COMMON_RESET,

    /** Списки доменов и приложений. */
    LIST_DOMAINS,
    LIST_APPS,
    LIST_EXCLUSIONS,
    LIST_PROFILES,

    /** VPN. */
    VPN_PROFILES,
    VPN_ADD_PROFILE,
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
    DPI_SPLIT_OFFSET,
    DPI_FAKE_LEN,
    DPI_FAKE_VAL,
    DPI_FAKE_SEQ,
    DPI_FAKE_CSUM,
    DPI_FAKE_FLAGS,
    DPI_FAKE_MSS,
    DPI_SKIP_ALPN,
    DPI_SKIP_TLS13,
    DPI_KEEP_SNI,
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
    ABOUT_VERSION,
    ABOUT_LICENSE,
    ABOUT_PLATFORM,

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
    TRAFFIC_SMOOTH;

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
        COMMON_START -> pick(lang, "Start", "Запустить", "启动")
        COMMON_STOP -> pick(lang, "Stop", "Остановить", "停止")
        COMMON_ADD -> pick(lang, "Add", "Добавить", "添加")
        COMMON_DELETE -> pick(lang, "Delete", "Удалить", "删除")
        COMMON_SAVE -> pick(lang, "Save", "Сохранить", "保存")
        COMMON_APPLY -> pick(lang, "Apply", "Применить", "应用")
        COMMON_AUTODETECT -> pick(lang, "Auto", "Авто", "自动")
        COMMON_TEST -> pick(lang, "Test", "Проверить", "测试")
        COMMON_EXPORT -> pick(lang, "Export", "Выгрузить", "导出")
        COMMON_IMPORT -> pick(lang, "Import", "Загрузить", "导入")
        COMMON_RESET -> pick(lang, "Reset", "Сбросить", "重置")

        LIST_DOMAINS -> pick(lang, "Domains", "Домены", "域名")
        LIST_APPS -> pick(lang, "Applications", "Приложения", "应用")
        LIST_EXCLUSIONS -> pick(lang, "Exceptions", "Исключения", "例外")
        LIST_PROFILES -> pick(lang, "Profiles", "Профили", "配置")

        VPN_PROFILES -> pick(lang, "Profiles", "Профили", "配置")
        VPN_ADD_PROFILE -> pick(lang, "Add profile", "Добавить профиль", "添加配置")
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
        DPI_SPLIT_OFFSET -> pick(lang, "Split offset", "Смещение разреза", "分割偏移")
        DPI_FAKE_LEN -> pick(lang, "Fake length", "Длина подмены", "伪造长度")
        DPI_FAKE_VAL -> pick(lang, "Filler", "Заполнитель", "填充内容")
        DPI_FAKE_SEQ -> pick(lang, "Fake sequence", "Подмена номера", "伪造序号")
        DPI_FAKE_CSUM -> pick(lang, "Fake checksum", "Подмена контрольной суммы", "伪造校验和")
        DPI_FAKE_FLAGS -> pick(lang, "Fake flags", "Подмена флагов", "伪造标志位")
        DPI_FAKE_MSS -> pick(lang, "Fake MSS", "Подмена MSS", "伪造 MSS")
        DPI_SKIP_ALPN -> pick(lang, "Skip ALPN", "Пропускать ALPN", "跳过 ALPN")
        DPI_SKIP_TLS13 -> pick(lang, "Skip TLS 1.3", "Пропускать TLS 1.3", "跳过 TLS 1.3")
        DPI_KEEP_SNI -> pick(lang, "Keep SNI", "Сохранять SNI", "保留 SNI")
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
        SETTINGS_SYSTEM -> pick(lang, "System", "Система", "系统")
        SETTINGS_APPLY_LIVE -> pick(lang, "Apply on change", "Применять сразу", "立即应用")
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
