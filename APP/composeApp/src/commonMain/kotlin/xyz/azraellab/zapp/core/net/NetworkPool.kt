package xyz.azraellab.zapp.core.net

import kotlin.concurrent.Volatile

/**
 * Пул доступных сетей с монотонным поколением.
 *
 * Смена аплинка (Wi-Fi -> мобайл) не должна ждать ручного рестарта:
 * колбэк сети двигает поколение, и всё, что собиралось под старую
 * сеть -- правила обхода, конфиг ядра -- узнаёт по поколению, что
 * устарело, вместо повторной сверки «похоже ли». Пул помнит кандидатов
 * и при потере текущего выбирает следующего: движок не ждёт, пока
 * система сама что-нибудь даст.
 *
 * Потоки: кормит сервис из ConnectivityThread, поколение читают
 * потоки ядра -- поля волатильны, инкременты идут только здесь.
 */
class NetworkPool {

    private val available = LinkedHashSet<String>()

    /** Монотонное поколение: любое осмысленное изменение -- вперёд. */
    @Volatile
    var generation: Int = 0
        private set

    /** Текущая сеть -- последняя показанная подложка туннеля. */
    @Volatile
    var current: String? = null
        private set

    /** Сеть появилась: пул растёт, поколение двигается. */
    fun onAvailable(id: String) {
        if (available.add(id)) generation++
    }

    /** Сеть ушла: текущая выбирается заново из оставшихся. */
    fun onLost(id: String): String? {
        if (available.remove(id)) generation++
        if (current == id) {
            current = available.firstOrNull()
            generation++
        }
        return current
    }

    /** Система показала подложку туннеля: она же текущая. */
    fun onCurrent(id: String): String? {
        available.add(id)
        if (current != id) {
            current = id
            generation++
        }
        return current
    }
}

/**
 * Глобальная точка обмена: сервис кормит пул, движок читает поколение
 * при сборке конфига ядра (ZAPP_NET_GEN в окружении демона).
 */
object NetworkBus {
    val pool = NetworkPool()
}
