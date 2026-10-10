package xyz.azraellab.zapp.core.net

import xyz.azraellab.zapp.core.AutoStrategy

/**
 * Фаза TLS-пробы, на которой случился сбой.
 *
 * Фазы -- разные вопросы к сети. Отказ на соединении значит, что до
 * сервера не дошли вообще (он лёг или сеть мертва), а обрыв на handshake --
 * что ClientHello дошёл и ответ не пришёл: это и есть DPI-фильтрация.
 */
enum class TlsPhase {

    /** TCP-соединение: дошли ли до порта 443 вообще. */
    CONNECT,

    /** Handshake: ушёл ClientHello и пришёл ли ServerHello в ответ. */
    HANDSHAKE
}

/** Характер сбоя на своей фазе. */
enum class TlsFailure {

    /** Соединение отклонено (refused). */
    REFUSED,

    /** Таймаут своей фазы: соединение не успело или ответ не пришёл. */
    TIMEOUT,

    /** SSL/TLS-ошибка: alert, битый session_id, нет ServerHello. */
    SSL_ERROR,

    /** Платформа не поддерживает запрошенную версию протокола. */
    PROTOCOL_UNSUPPORTED,

    /** Прочее: сброс, неизвестное исключение. */
    OTHER
}

/**
 * Результат пробы по фазе и характеру сбоя.
 *
 * Правило одной строкой: «DPI режет» -- только то, что оборвалось ПОСЛЕ
 * ClientHello. Отказ в соединении и таймаут подключения не про фильтрацию:
 * такая «блокировка» выбрала бы в цель мёртвый хост и предложила стратегию
 * обхода там, где обходить нечего. Отсюда:
 *
 *  - connect-фаза -- всегда Unknown: сервер лёг или нет сети;
 *  - handshake-фаза с SSL-ошибкой/таймаутом/сбросом -- Blocked: классика
 *    ssl_err (обрыв после ClientHello);
 *  - неподдерживаемый протокол -- Unknown: это про платформу, не про сеть.
 */
fun tlsVerdict(phase: TlsPhase, failure: TlsFailure): AutoStrategy.ProbeResult = when {
    failure == TlsFailure.PROTOCOL_UNSUPPORTED -> AutoStrategy.ProbeResult.Unknown
    phase == TlsPhase.CONNECT -> AutoStrategy.ProbeResult.Unknown
    phase == TlsPhase.HANDSHAKE && failure == TlsFailure.REFUSED -> AutoStrategy.ProbeResult.Unknown
    else -> AutoStrategy.ProbeResult.Blocked
}

/** Короткая подпись причины для заметок отчёта: `phase: failure`. */
fun tlsFailureNote(phase: TlsPhase, failure: TlsFailure): String =
    phase.name.lowercase() + ": " + failure.name.lowercase().replace('_', ' ')
