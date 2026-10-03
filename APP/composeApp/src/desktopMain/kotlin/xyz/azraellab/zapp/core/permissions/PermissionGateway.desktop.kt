package xyz.azraellab.zapp.core.permissions

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Разрешения на десктопе.
 *
 * Их здесь нет и не бывает: у Linux и Windows нет системного диалога
 * «разрешить VPN», AppOps и уведомлений в привычном смысле. Шлюз всё
 * равно существует, чтобы общий код не ветвился по платформам, но он
 * честно отдаёт `UNSUPPORTED` -- экран настроек покажет «нет на этой
 * платформе», а не будет врать, что «не дано».
 */
private class DesktopPermissionGateway : PermissionGateway {

    private val unsupported: List<PermissionStatus> = PermissionId.entries.map { id ->
        PermissionStatus(
            id = id,
            state = PermissionState.UNSUPPORTED,
            hint = xyz.azraellab.zapp.core.Str.PERM_HINT_UNSUPPORTED
        )
    }

    private val _statuses = MutableStateFlow(unsupported)
    override val statuses: StateFlow<List<PermissionStatus>> = _statuses.asStateFlow()

    override fun refresh() {
        // Состояние неизменяемо: перечитывать нечего.
    }

    override fun request(id: PermissionId, onResult: (Boolean) -> Unit) {
        onResult(false)
    }
}

actual fun createPermissionGateway(): PermissionGateway = DesktopPermissionGateway()
