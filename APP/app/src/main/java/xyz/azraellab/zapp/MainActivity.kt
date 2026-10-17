package xyz.azraellab.zapp

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import xyz.azraellab.zapp.core.deeplink.DeepLinkRequest
import xyz.azraellab.zapp.core.deeplink.parseDeepLink
import xyz.azraellab.zapp.core.installPresetFileBridge
import xyz.azraellab.zapp.core.permissions.AndroidPermissionBridge
import xyz.azraellab.zapp.ui.nav.AppNavRequest

/**
 * Единственная Activity.
 *
 * Кроме композиции здесь ещё две вещи, которых нельзя сделать из common-кода:
 * регистрация launcher'ов SAF и регистрация launcher'ов разрешений.
 * `registerForActivityResult` должен вызываться до `onCreate`, иначе Activity
 * выбросит, -- отсюда инициализация полей, а не создание лаунчеров внутри
 * колбэка. Контекст и режим сборки приложение получает в ZappApp.onCreate --
 * раньше, чем эта Activity успевает родиться.
 *
 * Диалоги разрешений приходят из общего кода, который не знает про Activity,
 * поэтому лаунчеры здесь -- это мост [AndroidPermissionBridge]: общий код
 * просит «спросить», Activity знает, как спросить.
 */
class MainActivity : ComponentActivity() {

    /** Результат чтения пресета: продолжение, ожидающее URI, и само оно. */
    private var pendingRead: ((Uri?) -> Unit)? = null
    private var pendingWrite: ((Uri?) -> Unit)? = null

    /** Ожидающие ответа системных диалогов разрешений. */
    private var pendingRuntime: ((Map<String, Boolean>) -> Unit)? = null
    private var pendingVpn: ((Boolean) -> Unit)? = null

    private val readLauncher =
        registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            pendingRead?.invoke(uri)
            pendingRead = null
        }

    private val writeLauncher =
        registerForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
            pendingWrite?.invoke(uri)
            pendingWrite = null
        }

    /** Групповой запрос runtime-разрешений (гео). */
    private val runtimeLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { result ->
            pendingRuntime?.invoke(result)
            pendingRuntime = null
        }

    /**
     * Системный запрос на доступ к VPN.
     *
     * `VpnService.prepare()` возвращает Intent, который нужно прогнать через
     * startActivityForResult: ответ -- это «да/нет» от пользователя, а не
     * runtime-разрешение, поэтому лаунчер здесь отдельный.
     */
    private val vpnLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            pendingVpn?.invoke(result.resultCode == RESULT_OK)
            pendingVpn = null
        }

    /**
     * Открытие системных настроек (AppOps имитации местоположения).
     *
     * Результат не важен: как только пользователь вернулся, статус
     * перечитывается через onResume, поэтому здесь нет ожидающего колбэка.
     */
    private val settingsLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        // Мост устанавливается до первого Composable: иначе кнопки импорта и
        // экспорта на странице настроек увидят `available = false` до того,
        // как кто-то успеет их нажать.
        installPresetFileBridge(
            context = this,
            onPickRead = { onResult ->
                pendingRead = onResult
                readLauncher.launch(arrayOf("application/json", "application/octet-stream", "*/*"))
            },
            onPickWrite = { name, onResult ->
                pendingWrite = onResult
                writeLauncher.launch(name.ifBlank { "zapp-preset.json" })
            }
        )

        AndroidPermissionBridge.install(
            requestRuntime = { permissions, onResult ->
                pendingRuntime = onResult
                runtimeLauncher.launch(permissions)
            },
            requestVpn = { onResult ->
                pendingVpn = onResult
                val consent = runCatching { android.net.VpnService.prepare(this) }.getOrNull()
                if (consent == null) {
                    // null -- разрешение уже получено, диалог не нужен.
                    pendingVpn = null
                    onResult(true)
                } else {
                    vpnLauncher.launch(consent)
                }
            },
            openSettings = { intent -> settingsLauncher.launch(intent) }
        )

        // Кнопка «открыть настройки» из шторки: intent приходит сюда,
        // дальше запрос уходит в общий код и переключает вкладку.
        consumeOpenSettings(intent)
        consumeDeepLink(intent)

        setContent {
            App()
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        // Та же кнопка, когда приложение уже живо и Activity переиспользуется
        // (SINGLE_TOP из флага PendingIntent'а).
        consumeOpenSettings(intent)
        consumeDeepLink(intent)
    }

    /** Разбирает `zapp://`-интент и кладёт действие в общий мост. */
    private fun consumeDeepLink(intent: Intent?) {
        val uri = intent?.data ?: return
        DeepLinkRequest.pending.value = parseDeepLink(uri.toString())
    }

    /** Превращает extra из intent'а в запрос навигации для AppRoot. */
    private fun consumeOpenSettings(intent: Intent?) {
        if (intent?.getBooleanExtra(AppNavRequest.EXTRA_OPEN_SETTINGS, false) == true) {
            AppNavRequest.openSettings.value = true
        }
    }

    override fun onResume() {
        super.onResume()
        // Возврат из системных настроек не даёт колбэка лаунчеру: AppOps
        // имитации меняется молча, и статус перечитывается здесь.
        AndroidPermissionBridge.onActivityResumed()
    }
}
