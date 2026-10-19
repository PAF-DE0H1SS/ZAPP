package xyz.azraellab.zapp.core

import kotlinx.coroutines.Job
import java.util.concurrent.atomic.AtomicInteger

/**
 * Поколение жизни сессии против гонок асинхронных хвостов.
 *
 * Быстрый вкл/выкл оставляет в воздухе хвосты прошлого старта:
 * отложенная геолокация дописывает точку уже в новый старт, ответ
 * сети приносит конфиг, которого больше нет. Хвост запоминает
 * поколение на старте и перед записью сверяется: чужое поколение --
 * молчаливый выход. Дописывать в чужой старт нельзя.
 */
class SessionGeneration {

    private val counter = AtomicInteger()

    /** Новое поколение: предыдущее с этого момента устарело. */
    fun bump(): Int = counter.incrementAndGet()

    /** Живо ли ещё поколение [token]. */
    fun isCurrent(token: Int): Boolean = counter.get() == token

    /** Текущее поколение: снимок перед началом асинхронной работы. */
    fun current(): Int = counter.get()
}

/**
 * Рестарт-барьер: сериализация сессий через join предшественника.
 *
 * СТОП и СТАРТ приходят из UI вперемешку, и без барьера отменённая
 * попытка доспавнивала бы демон уже поверх новой -- «два sing-box на
 * одном tun» и двойные NFQUEUE-цепочки. Вместо локов: новая сессия
 * стартует только после того, как предыдущая докрутила уборку.
 * Регистрация идёт до join, поэтому третья сессия цепляется за
 * вторую, а не за давно завершённую первую.
 */
class SessionBarrier {

    private val lock = Any()

    @Volatile
    private var current: Job? = null

    /** Регистрирует текущую сессию и ждёт завершения предыдущей. */
    suspend fun enter(job: Job) {
        val predecessor = synchronized(lock) {
            val previous = current
            current = job
            previous
        }
        predecessor?.join()
    }

    /** Текущая сессия (для отмены); null -- сессий ещё не было. */
    fun current(): Job? = synchronized(lock) { current }
}
