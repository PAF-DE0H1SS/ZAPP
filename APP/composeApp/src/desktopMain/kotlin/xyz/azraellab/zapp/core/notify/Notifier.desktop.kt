package xyz.azraellab.zapp.core.notify

/**
 * На десктопе системных уведомлений в общей поставке нет: библиотек
 * для трей-иконок здесь не стоит тащить ради одной строки статуса --
 * для этого есть строка состояния внизу окна.
 */
actual fun createNotifier(): Notifier = object : Notifier {
    override val supported: Boolean = false
    override fun show(title: String, body: String, tag: String) = Unit
}
