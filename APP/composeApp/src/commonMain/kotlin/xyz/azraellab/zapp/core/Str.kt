package xyz.azraellab.zapp.core

/**
 * Строки интерфейса. Три языка: EN, RU, ZH -- те же, что у README на GitHub.
 *
 * Ключи не строки, а элементы enum: забытый перевод тогда не собирается, а не
 * показывается пользователю как `CORE_START`. Значение `String` означает «одинаково
 * на всех языках» -- так помечаются только имена собственные и аббревиатуры.
 */
enum class Str {
    APP_NAME,

    TAB_VPN,
    TAB_ZAPRET,
    TAB_GOODBYE_DPI,
    TAB_GPS,

    SETTINGS,
    SETTINGS_THEME,
    SETTINGS_LANGUAGE,
    THEME_SYSTEM,
    THEME_LIGHT,
    THEME_DARK,

    ONBOARDING_TITLE,
    ONBOARDING_SUBTITLE,
    ONBOARDING_PROBE,
    ONBOARDING_CONTINUE,

    COMMON_OK,
    COMMON_CANCEL,
    COMMON_RETRY,
    COMMON_CLOSE,

    GPS_UNSUPPORTED_TITLE,
    GPS_UNSUPPORTED_BODY;

    /**
     * Перевод для языка. `null` означает, что строка одинакова во всех языках,
     * и тогда берётся сам ключ: держать три одинаковые копии смысла нет.
     */
    fun of(lang: AppLang): String = when (this) {
        APP_NAME -> "ZAPP"

        TAB_VPN -> when (lang) {
            AppLang.EN -> "VPN"
            AppLang.RU -> "VPN"
            AppLang.ZH -> "VPN"
        }
        TAB_ZAPRET -> when (lang) {
            AppLang.EN -> "Zapret"
            AppLang.RU -> "Zapret"
            AppLang.ZH -> "Zapret"
        }
        TAB_GOODBYE_DPI -> when (lang) {
            AppLang.EN -> "GoodbyeDPI"
            AppLang.RU -> "GoodbyeDPI"
            AppLang.ZH -> "GoodbyeDPI"
        }
        TAB_GPS -> when (lang) {
            AppLang.EN -> "GPS"
            AppLang.RU -> "GPS"
            AppLang.ZH -> "GPS"
        }

        SETTINGS -> when (lang) {
            AppLang.EN -> "Settings"
            AppLang.RU -> "Настройки"
            AppLang.ZH -> "设置"
        }
        SETTINGS_THEME -> when (lang) {
            AppLang.EN -> "Theme"
            AppLang.RU -> "Тема"
            AppLang.ZH -> "主题"
        }
        SETTINGS_LANGUAGE -> when (lang) {
            AppLang.EN -> "Language"
            AppLang.RU -> "Язык"
            AppLang.ZH -> "语言"
        }
        THEME_SYSTEM -> when (lang) {
            AppLang.EN -> "Follow system"
            AppLang.RU -> "Как в системе"
            AppLang.ZH -> "跟随系统"
        }
        THEME_LIGHT -> when (lang) {
            AppLang.EN -> "Light"
            AppLang.RU -> "Светлая"
            AppLang.ZH -> "浅色"
        }
        THEME_DARK -> when (lang) {
            AppLang.EN -> "Dark"
            AppLang.RU -> "Тёмная"
            AppLang.ZH -> "深色"
        }

        ONBOARDING_TITLE -> when (lang) {
            AppLang.EN -> "ZAPP"
            AppLang.RU -> "ZAPP"
            AppLang.ZH -> "ZAPP"
        }
        ONBOARDING_SUBTITLE -> when (lang) {
            AppLang.EN -> "A local network toolkit. No account, no server, no tracking."
            AppLang.RU -> "Локальный сетевой инструмент. Без аккаунта, без сервера, без слежки."
            AppLang.ZH -> "本地网络工具。无需账号，无需服务器，不做追踪。"
        }
        ONBOARDING_PROBE -> when (lang) {
            AppLang.EN -> "Check what this device allows"
            AppLang.RU -> "Проверить, что позволяет устройство"
            AppLang.ZH -> "检测此设备允许的操作"
        }
        ONBOARDING_CONTINUE -> when (lang) {
            AppLang.EN -> "Continue"
            AppLang.RU -> "Продолжить"
            AppLang.ZH -> "继续"
        }

        COMMON_OK -> when (lang) {
            AppLang.EN -> "OK"
            AppLang.RU -> "ОК"
            AppLang.ZH -> "确定"
        }
        COMMON_CANCEL -> when (lang) {
            AppLang.EN -> "Cancel"
            AppLang.RU -> "Отмена"
            AppLang.ZH -> "取消"
        }
        COMMON_RETRY -> when (lang) {
            AppLang.EN -> "Retry"
            AppLang.RU -> "Повторить"
            AppLang.ZH -> "重试"
        }
        COMMON_CLOSE -> when (lang) {
            AppLang.EN -> "Close"
            AppLang.RU -> "Закрыть"
            AppLang.ZH -> "关闭"
        }

        // GPS не прячет вкладку заглушкой: на десктопе системного API подмены
        // координат нет вообще, и честнее сказать об этом прямо, чем показать
        // пустой экран.
        GPS_UNSUPPORTED_TITLE -> when (lang) {
            AppLang.EN -> "Not available on this platform"
            AppLang.RU -> "Недоступно на этой платформе"
            AppLang.ZH -> "此平台不可用"
        }
        GPS_UNSUPPORTED_BODY -> when (lang) {
            AppLang.EN -> "Coordinate spoofing needs the system test-provider API, " +
                "which exists only on Android. On Windows and Linux there is no " +
                "system-wide way to do this, and this app will not pretend otherwise."
            AppLang.RU -> "Подмена координат требует системного API тест-провайдера, " +
                "а он есть только на Android. В Windows и Linux такого системного " +
                "способа нет, и приложение не будет делать вид, что он есть."
            AppLang.ZH -> "坐标伪装需要系统测试提供者 API，而该 API 仅存在于 Android。" +
                "Windows 和 Linux 没有系统级的实现方式，本应用不会假装有。"
        }
    }
}
