package xyz.azraellab.zapp.core.log

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import xyz.azraellab.zapp.core.AppLang
import xyz.azraellab.zapp.core.AppLangStore
import xyz.azraellab.zapp.core.Str
import xyz.azraellab.zapp.core.resolveLang

/**
 * Журнал ошибок: только то, что пошло не так.
 *
 * Отдельно от [AppLog], который пишет всё подряд (действия, состояния,
 * причины) и нужен, чтобы посмотреть ход сессии целиком. Этот -- ровно
 * то, что понадобится в багрепорте: сбои демонов, туннеля, GPS,
 * источников и обновлений, без строк «ticker start» и «connect
 * requested». Файл свой (`zapp-errors.log` рядом с `zapp.log`), поэтому
 * его можно приложить к issue, не выискивая ошибки в общем журнале.
 *
 * Признак ошибки -- тот же, что у покраски в журнале ([looksLikeErrorLine]
 * и `DaemonEvent.isError`/`GpsEvent.isError`): логика одна, поэтому
 * «красное на экране» и «попало в файл ошибок» -- одно и то же.
 *
 * Запись подчинена тумблеру детального лога ([AppLog.enabled]): один
 * переключатель решает, пишется ли что-нибудь на диск. Выключенный лог
 * не должен продолжать писать файлы, о которых пользователь забыл.
 */
object ErrorLog {

    /** Буфер меньше, чем у [AppLog]: здесь только причины, а не ход работы. */
    private const val LIMIT = 300

    private const val FILE = "zapp-errors.log"

    private val _lines = MutableStateFlow<List<String>>(emptyList())
    val lines: StateFlow<List<String>> = _lines.asStateFlow()

    /** Мьютекс: события приходят из корутин разных движков. */
    private val lock = Any()

    /** Уже отформатированная строка (например, зеркало из [AppLog]). */
    fun record(line: String) {
        if (!AppLog.enabled) return
        synchronized(lock) {
            _lines.value = capLines(_lines.value + line, LIMIT)
        }
        appendToFile(FILE, line)
    }

    /** Запись с меткой времени: [source] -- тег подсистемы. */
    fun note(source: String, message: String) {
        if (message.isBlank()) return
        record("${platformNowMs()} [$source] $message")
    }

    /**
     * Событие с переводом по текущему языку приложения.
     *
     * Нужно демонам и GPS: их события хранят ключ [Str], а строки ошибки
     * обязаны читаться на языке интерфейса, иначе багрепорт расслоится.
     */
    fun noteStr(source: String, text: Str, arg: String = "") {
        val lang = resolveLang(AppLang.of(AppLangStore.read()), AppLangStore.systemCode())
        note(source, text.of(lang) + if (arg.isEmpty()) "" else " $arg")
    }

    fun clear() {
        synchronized(lock) { _lines.value = emptyList() }
        clearFile(FILE)
    }

    /**
     * Попала бы строка в журнал ошибок.
     *
     * Отдельная функция, а не инлайн в вызывающих: правило фильтрации
     * обязано быть единым и проверяемым, иначе ошибка, которую UI красит
     * в красный, молча не доедет до багрепорта.
     */
    fun captures(message: String): Boolean = looksLikeErrorLine(message)
}

/**
 * Ограничение буфера: держим хвост, не копируем лишнее.
 *
 * Общая для обоих журналов, потому что «новые в конце, старые отваливаются»
 * -- одно и то же правило, а два разных написания -- два разных бага.
 */
internal fun capLines(lines: List<String>, limit: Int): List<String> =
    if (lines.size > limit) lines.takeLast(limit) else lines
