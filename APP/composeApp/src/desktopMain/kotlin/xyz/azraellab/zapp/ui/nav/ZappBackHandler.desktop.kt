package xyz.azraellab.zapp.ui.nav

import androidx.compose.runtime.Composable

@Composable
actual fun ZappBackHandler(enabled: Boolean, onBack: () -> Unit) {
    // У десктопа нет системной кнопки «назад»: закрытие подокон идёт
    // явными кнопками в интерфейсе. Ветка нужна только, чтобы общий
    // код мог вызывать хендлер без различий по платформам.
}
