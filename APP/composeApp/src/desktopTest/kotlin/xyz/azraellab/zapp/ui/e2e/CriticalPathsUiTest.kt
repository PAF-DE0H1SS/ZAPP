package xyz.azraellab.zapp.ui.e2e

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import java.util.Locale
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.junit.After
import org.junit.Before
import org.junit.Rule
import xyz.azraellab.zapp.core.AppConfig
import xyz.azraellab.zapp.core.AppLang
import xyz.azraellab.zapp.core.AppState
import xyz.azraellab.zapp.core.ConfigStore
import xyz.azraellab.zapp.core.Preset
import xyz.azraellab.zapp.core.PresetFileGateway
import xyz.azraellab.zapp.core.PerfConfig
import xyz.azraellab.zapp.core.Str
import xyz.azraellab.zapp.core.VpnConfig
import xyz.azraellab.zapp.core.daemon.DaemonState
import xyz.azraellab.zapp.core.engine.TunnelState
import xyz.azraellab.zapp.ui.theme.AzraelTheme
import xyz.azraellab.zapp.ui.pages.GpsPage
import xyz.azraellab.zapp.ui.pages.VpnPage
import xyz.azraellab.zapp.ui.pages.ZapretPage
import xyz.azraellab.zapp.ui.nav.AppRoot

/**
 * UI-тесты критичных путей: подключение VPN, старт zapret, GPS-страница
 * и навигация. Противоположность unit-тестам: здесь проверяется, что
 * нажатие кнопки доходит до движка и состояние возвращается на экран.
 *
 * Среда: десктопная композиция headless (Skia без окна). AppState живёт
 * настоящий, но на пустом хранилище и с заполненным DNS, чтобы
 * авто-подбор не шёл в сеть, а подключение без профиля честно падало в
 * ERROR -- так и устроен боевой путь «нажал СТАРТ, профилей нет».
 *
 * Язык: фиксируется на EN через локаль JVM до первого tr() в прогоне.
 * Альтернатива (запись файла языка) — побочный эффект в конфиге машины.
 */
class CriticalPathsUiTest {

    @get:Rule
    val rule = createComposeRule()

    private lateinit var state: AppState
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    /** Хранилище в памяти: конфиг с DNS (без сетевого прогона) и без анимаций. */
    private class TestStore : ConfigStore {
        var config: AppConfig = AppConfig(
            perf = PerfConfig(animatedBackground = false),
            // Заполненный список DNS: авто-подбор в connect() иначе пингует
            // кандидатов в реальной сети.
            vpn = VpnConfig(dnsServers = "1.1.1.1")
        )
        override fun readConfig(): AppConfig = config
        override fun writeConfig(config: AppConfig) { this.config = config }
        override fun readPresets(): List<Preset> = emptyList()
        override fun writePreset(preset: Preset) {}
        override fun deletePreset(id: String) {}
    }

    /** Файловый шлюз без диалогов: импорт в тесте не нужен. */
    private class TestGateway : PresetFileGateway {
        override suspend fun read(): String? = null
        override suspend fun write(content: String, suggestedName: String): Boolean = false
        override suspend fun readBytes(): ByteArray? = null
        override suspend fun writeBytes(content: ByteArray, suggestedName: String): Boolean = false
        override val available: Boolean = false
    }

    companion object {
        init {
            // Тексты интерфейса -- английские независимо от локали машины:
            // сброс языка по умолчанию действует до первого tr().
            Locale.setDefault(Locale.US)
        }
    }

    @Before
    fun setUp() {
        state = AppState(store = TestStore(), gateway = TestGateway(), scope = scope)
        state.load()
    }

    @After
    fun tearDown() {
        scope.cancel()
    }

    @Test
    fun vpnPageShowsStartWhenOff() {
        rule.setContent { AzraelTheme { VpnPage(state) } }
        // Статус и кнопка -- из словаря EN: локаль зафиксирована в init.
        rule.onNodeWithText(Str.ENGINE_STATE_DISCONNECTED.of(AppLang.EN))
            .assertIsDisplayed()
        rule.onNodeWithContentDescription(Str.COMMON_START.of(AppLang.EN))
            .assertIsDisplayed()
    }

    @Test
    fun vpnConnectWithoutProfileEndsInVisibleError() {
        rule.setContent { AzraelTheme { VpnPage(state) } }
        rule.onNodeWithContentDescription(Str.COMMON_START.of(AppLang.EN))
            .performClick()
        // Критичный путь: нажатие доходит до движка, пустой выбор профилей
        // не зависает в CONNECTING, а даёт честную ошибку на экране.
        rule.waitUntil(5_000) { state.tunnel.state.value == TunnelState.ERROR }
        rule.onNodeWithText(Str.ENGINE_STATE_ERROR.of(AppLang.EN))
            .assertIsDisplayed()
        assertTrue(
            state.tunnel.log.value.any { it.contains("no active profile") },
            "в журнале туннеля должна быть причина, получено: ${state.tunnel.log.value}"
        )
    }

    @Test
    fun gpsPageExplainsUnsupportedOnDesktop() {
        rule.setContent { AzraelTheme { GpsPage(state) } }
        // GPS-путь на десктопе: не пустая страница, а честное объяснение.
        rule.onNodeWithText(Str.GPS_UNSUPPORTED_TITLE.of(AppLang.EN))
            .assertIsDisplayed()
    }

    @Test
    fun zapretStartWithoutScopeRefusesAndKeepsStopped() {
        rule.setContent { AzraelTheme { ZapretPage(state) } }
        rule.onNodeWithContentDescription("btn:zapret.start").performClick()
        rule.waitUntil(5_000) { state.zapretDaemon.log.value.isNotEmpty() }
        // Отказ без выбранной области: демон не стартует, кнопка остаётся
        // «Старт», а не превращается в «Стоп» от мнимого запуска.
        assertEquals(DaemonState.STOPPED, state.zapretDaemon.state.value)
        rule.onNodeWithContentDescription("btn:zapret.start").assertIsDisplayed()
    }

    @Test
    fun appRootNavigatesToZapretSlot() {
        rule.setContent { AzraelTheme { AppRoot(state) } }
        // Парный слот «Zapret | GoodbyeDPI» на нижней панели.
        rule.onNodeWithContentDescription("btn:nav.zapret.goodbye_dpi")
            .performClick()
        rule.onNodeWithContentDescription("btn:zapret.start").assertIsDisplayed()
    }
}
