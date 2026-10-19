package xyz.azraellab.zapp.core.sleep

/**
 * Desktop: сон ловится опросом, без системных событий.
 *
 * У JVM нет подписки на засыпание, зато есть монотонные часы: пока
 * компьютер спит, разница между тиками растёт на всё время сна.
 * По пробуждении наблюдатель отдаёт [onSleep] и [onWake] одной
 * парой: сеть пропала вместе со сном, туннель уже мёртв, так что
 * «отключить при засыпании» здесь -- снять состояние, а по
 * «переподключить при пробуждении» поднять заново. Очередность
 * гарантирует рестарт-барьер движка: отключение уходит раньше.
 *
 * Порог в двадцать секунд отделяет сон от лага GC: пауза сборки
 * дольше -- почти нереальность, ночная заморозка -- обычное дело.
 */
actual object SleepWatch {

    /** Шаг опроса. */
    private const val INTERVAL_MS = 3_000L

    /** Отставание, за которым признаём пробуждение. */
    private const val SUSPEND_GAP_MS = 20_000L

    @Volatile
    private var thread: Thread? = null

    actual fun start(onSleep: () -> Unit, onWake: () -> Unit) {
        stop()
        thread = Thread({
            var last = System.nanoTime()
            while (!Thread.currentThread().isInterrupted) {
                try {
                    Thread.sleep(INTERVAL_MS)
                } catch (_: InterruptedException) {
                    break
                }
                val now = System.nanoTime()
                val gapMs = (now - last) / 1_000_000
                last = now
                if (gapMs > SUSPEND_GAP_MS) {
                    // Часы отставали -- нас разбудили. Пара событий
                    // в одном тике, см. док класса.
                    runCatching { onSleep() }
                    runCatching { onWake() }
                }
            }
        }, "zapp-sleep-watch").apply {
            isDaemon = true
            start()
        }
    }

    actual fun stop() {
        thread?.interrupt()
        thread = null
    }
}
