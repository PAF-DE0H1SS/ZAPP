package xyz.azraellab.zapp.core.permissions

import android.Manifest
import android.app.AppOpsManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.VpnService
import android.os.Build
import android.os.Process
import androidx.core.content.ContextCompat
import xyz.azraellab.zapp.core.AndroidCtx
import xyz.azraellab.zapp.core.Str
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Мост между [AndroidPermissionBridge] и лаунчерами Activity.
 *
 * `registerForActivityResult` должен вызываться до `onCreate`, а запрос из
 * общего кода приходит позже, когда экран уже показан. Поэтому Activity
 * регистрирует лаунчеры заранее и передаёт сюда функции-обёртки, а общий
 * код зовёт их, не зная ничего про Activity.
 */
object AndroidPermissionBridge {

    private var runtimeRequest: ((Array<String>, (Map<String, Boolean>) -> Unit) -> Unit)? = null
    private var vpnRequest: (((Boolean) -> Unit) -> Unit)? = null
    private var settingsRequest: ((Intent) -> Unit)? = null

    /** Слушатели, которым нужно узнать, что пользователь вернулся из настроек. */
    private val resumeListeners = ArrayList<() -> Unit>()

    fun install(
        requestRuntime: (Array<String>, (Map<String, Boolean>) -> Unit) -> Unit,
        requestVpn: (((Boolean) -> Unit) -> Unit),
        openSettings: (Intent) -> Unit
    ) {
        runtimeRequest = requestRuntime
        vpnRequest = requestVpn
        settingsRequest = openSettings
    }

    fun requestRuntime(permissions: Array<String>, onResult: (Map<String, Boolean>) -> Unit) {
        val request = runtimeRequest
        if (request == null) {
            onResult(permissions.associateWith { false })
        } else {
            request(permissions, onResult)
        }
    }

    fun requestVpn(onResult: (Boolean) -> Unit) {
        val request = vpnRequest
        if (request == null) onResult(false) else request(onResult)
    }

    fun openSettings(intent: Intent) {
        settingsRequest?.invoke(intent)
    }

    fun addResumeListener(listener: () -> Unit) {
        if (listener !in resumeListeners) resumeListeners += listener
    }

    fun removeResumeListener(listener: () -> Unit) {
        resumeListeners.remove(listener)
    }

    /** Вызывается Activity при возврате на экран: AppOps мог измениться. */
    fun onActivityResumed() {
        // Копия: слушатель имеет право снять сам себя во время обхода,
        // и правка списка под итератором здесь упала бы исключением.
        resumeListeners.toList().forEach { it() }
    }
}

/**
 * Разрешения Android.
 *
 * Один экземпляр на процесс: состоянием владеет именно он, и UI подписывается
 * на [statuses] -- после каждого ответа системы состояние меняется централизованно,
 * а не «где-то там пересчитается».
 */
private class AndroidPermissionGateway : PermissionGateway {

    private val _statuses = MutableStateFlow<List<PermissionStatus>>(readStatuses())
    override val statuses: StateFlow<List<PermissionStatus>> = _statuses.asStateFlow()

    init {
        // Возврат из системных настроек (AppOps имитации) не даёт колбэка
        // лаунчеру, поэтому перечитываем при каждом возобновлении Activity.
        AndroidPermissionBridge.addResumeListener { refresh() }
    }

    override fun refresh() {
        _statuses.value = readStatuses()
    }

    override fun request(id: PermissionId, onResult: (Boolean) -> Unit) {
        when (id) {
            PermissionId.VPN -> AndroidPermissionBridge.requestVpn {
                refresh()
                onResult(statusOf(id).granted)
            }

            PermissionId.LOCATION_MOCK -> {
                // AppOps нельзя спросить диалогом: можно только открыть экран
                // настроек разработчика, где человек выбирает приложение сам.
                // Ответ приходит не сразу, а по возврату в приложение -- иначе
                // цепочка запуска ушла бы дальше, пока пользователь ещё
                // выбирает приложение в настройках.
                AndroidPermissionBridge.openSettings(
                    Intent(android.provider.Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS)
                )
                awaitResume {
                    refresh()
                    onResult(statusOf(id).granted)
                }
            }

            PermissionId.LOCATION_FINE, PermissionId.NOTIFICATIONS -> {
                AndroidPermissionBridge.requestRuntime(arrayOf(permissionOf(id))) { result ->
                    refresh()
                    onResult(result[permissionOf(id)] == true)
                }
            }
        }
    }

    /**
     * Ждёт возвращения в приложение.
     *
     * Используется там, где система не даёт ответа в момент запроса:
     * настройки разработчика -- это отдельная Activity, и её результатом
     * является сам факт возврата.
     */
    private fun awaitResume(onDone: () -> Unit) {
        lateinit var stable: () -> Unit
        stable = {
            AndroidPermissionBridge.removeResumeListener(stable)
            onDone()
        }
        AndroidPermissionBridge.addResumeListener(stable)
    }

    private fun statusOf(id: PermissionId): PermissionStatus =
        readStatuses().firstOrNull { it.id == id } ?: PermissionStatus(id, PermissionState.UNSUPPORTED)

    private fun permissionOf(id: PermissionId): String = when (id) {
        PermissionId.LOCATION_FINE -> Manifest.permission.ACCESS_FINE_LOCATION
        PermissionId.NOTIFICATIONS -> Manifest.permission.POST_NOTIFICATIONS
        else -> ""
    }

    private fun readStatuses(): List<PermissionStatus> = listOf(
        vpnStatus(),
        notificationsStatus(),
        locationFineStatus(),
        locationMockStatus()
    )

    /**
     * Уведомления.
     *
     * Проверяется не только разрешение, а именно «включены ли уведомления
     * приложения»: пользователь может выдать разрешение, а потом всё
     * запретить в шторке -- статус обязан это показать.
     */
    private fun notificationsStatus(): PermissionStatus {
        val context = AndroidCtx.current ?: return unsupported(PermissionId.NOTIFICATIONS)
        val enabled = runCatching {
            androidx.core.app.NotificationManagerCompat.from(context).areNotificationsEnabled()
        }.getOrDefault(false)
        return PermissionStatus(
            PermissionId.NOTIFICATIONS,
            if (enabled) PermissionState.GRANTED else PermissionState.DENIED
        )
    }

    private fun vpnStatus(): PermissionStatus {
        val context = AndroidCtx.current ?: return unsupported(PermissionId.VPN)
        val ready = runCatching { VpnService.prepare(context) == null }.getOrDefault(false)
        return PermissionStatus(PermissionId.VPN, if (ready) PermissionState.GRANTED else PermissionState.DENIED)
    }

    private fun locationFineStatus(): PermissionStatus {
        val context = AndroidCtx.current ?: return unsupported(PermissionId.LOCATION_FINE)
        val granted = ContextCompat.checkSelfPermission(
            context, Manifest.permission.ACCESS_FINE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED
        return PermissionStatus(
            PermissionId.LOCATION_FINE,
            if (granted) PermissionState.GRANTED else PermissionState.DENIED
        )
    }

    private fun locationMockStatus(): PermissionStatus {
        val context = AndroidCtx.current ?: return unsupported(PermissionId.LOCATION_MOCK)
        val allowed = runCatching {
            val appOps = context.getSystemService(Context.APP_OPS_SERVICE) as AppOpsManager
            val mode = if (Build.VERSION.SDK_INT >= 29) {
                appOps.checkOpNoThrow(
                    AppOpsManager.OPSTR_MOCK_LOCATION, Process.myUid(), context.packageName
                )
            } else {
                @Suppress("DEPRECATION")
                appOps.checkOpNoThrow("android:mock_location", Process.myUid(), context.packageName)
            }
            mode == AppOpsManager.MODE_ALLOWED
        }.getOrDefault(false)
        return if (allowed) {
            PermissionStatus(PermissionId.LOCATION_MOCK, PermissionState.GRANTED)
        } else {
            PermissionStatus(
                PermissionId.LOCATION_MOCK,
                PermissionState.SETTINGS,
                hint = Str.PERM_HINT_MOCK
            )
        }
    }

    private fun granted(id: PermissionId) = PermissionStatus(id, PermissionState.GRANTED)

    private fun unsupported(id: PermissionId) =
        PermissionStatus(id, PermissionState.UNSUPPORTED, hint = Str.PERM_HINT_UNSUPPORTED)
}

private val gateway = AndroidPermissionGateway()

actual fun createPermissionGateway(): PermissionGateway = gateway
