package xyz.azraellab.zapp.core.deeplink

import kotlinx.coroutines.flow.MutableStateFlow

/**
 * Deep-link, пришедший из интента, в ожидании обработки.
 *
 * Интент живёт в Activity, обработка — в общем коде: Activity кладёт
 * сюда разобранный link, корень приложения читает и тут же гасит.
 */
object DeepLinkRequest {

    /** Кладётся MainActivity при `zapp://`-интенте; сбрасывается после разбора. */
    val pending = MutableStateFlow<DeepLink?>(null)
}
