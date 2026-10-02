package xyz.azraellab.zapp.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import xyz.azraellab.zapp.core.AppLang
import xyz.azraellab.zapp.core.AppLangStore
import xyz.azraellab.zapp.core.Str
import xyz.azraellab.zapp.core.resolveLang

/**
 * Текущий язык интерфейса как реактивное состояние.
 *
 * Язык нужен и в композаблах, и в обработчиках кнопок, а обработчики --
 * обычные функции и не могут звать composable. Отдельный
 * CompositionLocal не нужен: язык меняет весь интерфейс, перерисовка и так случится.
 */
object AppLangState {
    /** Явный выбор пользователя. `null` -- «следовать системе». */
    var chosen: AppLang? by mutableStateOf(null)
        private set

    /** Что показываем прямо сейчас: выбор, иначе язык системы, иначе EN. */
    var current: AppLang by mutableStateOf(AppLang.EN)
        private set

    private var loaded: Boolean = false

    fun load() {
        if (loaded) return
        loaded = true
        chosen = AppLang.of(AppLangStore.read())
        applySystem()
    }

    /** Выбрать язык явно. `null` -- вернуться к системному. */
    fun set(next: AppLang?) {
        chosen = next
        loaded = true
        current = resolveLang(next, AppLangStore.systemCode())
        AppLangStore.write(next?.code ?: "")
    }

    /** Пересчитать язык системы. Вызывается на старте и при смене языка в системе. */
    fun refreshSystem() {
        applySystem()
    }

    private fun applySystem() {
        current = resolveLang(chosen, AppLangStore.systemCode())
    }
}

/** Текущий язык в композиции. */
@Composable
fun rememberLang(): AppLang {
    AppLangState.load()
    return AppLangState.current
}

/** Перевод по ключу. Язык берётся из состояния, поэтому смена языка перерисовывает UI. */
@Composable
fun tr(key: Str): String = key.of(rememberLang())
