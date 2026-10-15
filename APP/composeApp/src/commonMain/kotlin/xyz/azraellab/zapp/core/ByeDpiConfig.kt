package xyz.azraellab.zapp.core

import kotlinx.serialization.Serializable
import xyz.azraellab.zapp.core.daemon.DpiOverlay

/**
 * Механизм обхода DPI в норут-сборке (§130).
 *
 * Оба варианта слушают один и тот же порт [DpiOverlay.RELAY_PORT], на
 * который box отдаёт целевые порты, -- конфиг туннеля от выбора не
 * зависит, меняется только то, кто отвечает за этот порт:
 *
 * - [RELAY] -- встроенный SOCKS-релей ([xyz.azraellab.zapp.core.dpi.SplitRelay]),
 *   режущий ClientHello на сегменты. Без процессов и без бинарей.
 * - [BYEDPI] -- отдельный процесс byedpi, чей арсенал шире: disorder,
 *   fake, tlsrec и триггеры авто-режима. Работает без прав, потому что
 *   не трогает ядро.
 *
 * Общий для zapret и goodbyedpi: порт у них один, и выбор -- глобальный.
 */
enum class DpiBackend(val code: String, val ru: String, val en: String) {
    RELAY("relay", "Релей", "Relay"),
    BYEDPI("byedpi", "byedpi", "byedpi");

    companion object {
        fun of(code: String?): DpiBackend =
            entries.firstOrNull { it.code == code?.trim()?.lowercase() } ?: RELAY
    }
}

/**
 * Стратегии byedpi: пресет -- готовый набор аргументов целиком.
 *
 * Контракт с CLI byedpi (см. его README): каждый набор ниже -- рабочая
 * комбинация из документации, а не набор флагов «на глаз». Порядок
 * аргументов значим: группы опций --auto обходятся слева направо, и
 * хвост [ByeDpiConfig.extraArgs] идёт после пресета, чтобы пользователь
 * мог переопределить его значения.
 */
enum class ByeDpiStrategy(
    val code: String,
    val ru: String,
    val en: String,
    val args: List<String>
) {
    // README: `--split 7 --disorder 23`. Чистый `--disorder 1` без разреза
    // на практике виснет (потерянная часть не ретрансмитится), поэтому
    // дефолт -- разрез вперёд, он стабилен на всех проверенных сетях.
    DISORDER(
        "disorder", "Disorder", "Disorder",
        listOf("--split", "1", "--disorder", "1")
    ),

    SPLIT_DISORDER(
        "split-disorder", "Разрез + disorder", "Split + disorder",
        listOf("--split", "1+s", "--disorder", "3+s")
    ),

    // README: `--fake 7`. Позиция 1+s режет по началу ClientHello, `-1`
    // на коротких LTE-путях обрывал соединение. Без --md5sig: на ядрах
    // Android TCP MD5 Signature обычно не собран.
    FAKE(
        "fake", "Подмена пакета", "Fake packet",
        listOf("--fake", "1+s", "--ttl", "8")
    ),

    TLSREC("tlsrec", "Разрез TLS-записи", "TLS record split", listOf("--tlsrec", "3+s")),

    // README: tlsrec и подобные мидлбокс-рифы честно включать только по
    // факту блокировки -- `--auto=torst --timeout 3 --tlsrec 3+s`.
    AUTO(
        "auto", "При блокировке", "On block",
        listOf("--auto=torst", "--timeout", "3", "--tlsrec", "3+s")
    );

    companion object {
        fun of(code: String?): ByeDpiStrategy =
            entries.firstOrNull { it.code == code?.trim()?.lowercase() } ?: DISORDER
    }
}

/**
 * Настройки byedpi: выбранная стратегия и хвост своих аргументов.
 *
 * Собирается чисто и без I/O ([toArgs]), потому что аргументы -- контракт
 * с CLI: лишний флаг роняет процесс на старте, отсутствующий -- молча не
 * работает настройка. Тесты держат и состав пресетов, и форму списка
 * доменов.
 */
@Serializable
data class ByeDpiConfig(
    /** Код стратегии из [ByeDpiStrategy]; неизвестный код читается как дефолт. */
    val strategy: String = ByeDpiStrategy.DISORDER.code,

    /** Свои аргументы хвостом, например `--ttl 5 --timeout 3`. */
    val extraArgs: String = ""
) {
    /** Хвост отдельными аргументами; пустые куски отбрасываются. */
    fun extraList(): List<String> =
        extraArgs.split(Regex("\\s+")).filter { it.isNotEmpty() }

    /**
     * Полная аргументная строка бинаря (сам бинарь не входит).
     *
     * Слушатель -- тот же порт, что у релея, и только loopback: бэкенды
     * подменяют друг друга на месте, и конфиг туннеля не меняется.
     * Список доменов уходит inline через двоеточие (`-H :a b c`) -- файл
     * для него не нужен (§133).
     */
    fun toArgs(hosts: List<String> = emptyList()): List<String> = buildList {
        add("-i")
        add("127.0.0.1")
        add("-p")
        add(DpiOverlay.RELAY_PORT.toString())
        addAll(ByeDpiStrategy.of(strategy).args)
        if (hosts.isNotEmpty()) {
            add("-H")
            add(":" + hosts.joinToString(" "))
        }
        addAll(extraList())
    }

    /** Человекочитаемая строка для карточки «Команда». */
    fun toCommandLine(hosts: List<String> = emptyList()): String =
        (listOf("byedpi") + toArgs(hosts)).joinToString(" ")
}
