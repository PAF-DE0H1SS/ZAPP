package xyz.azraellab.zapp.core.connections

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Живой мост IPC: реальный бинарь zappmon против JVM-стороны.
 *
 * Бинарь собирается отдельно (native/build-mon.sh), поэтому без него
 * тест молча пропускается -- как box-check в SingboxConfigTest. Это
 * единственная проверка, что Rust-энкодер и Kotlin-декодер говорят на
 * одном языке по-настоящему, а не по совпадению голден-байтов.
 */
class ZappMonLiveTest {

    private fun binary(): String? {
        val candidates = listOf(
            File("native/out/x86_64/zappmon"),
            File("../native/out/x86_64/zappmon")
        )
        candidates.firstOrNull { it.canExecute() }?.let { return it.absolutePath }
        return ZappMon.binary()
    }

    @Test
    fun daemonDeliversRealSnapshot() {
        val binary = binary() ?: return
        System.setProperty("zapp.mon", binary)
        try {
            val session = ZappMon.connect() ?: error("демон не подключился: $binary")
            try {
                val snapshot = session.readSnapshot(5000)
                assertEquals(1, snapshot.note, "на Linux /proc читается всегда")
                for (row in snapshot.rows) {
                    assertTrue(
                        row.proto in setOf("tcp", "tcp6", "udp", "udp6"),
                        "неожиданный proto: ${row.proto}"
                    )
                    assertTrue(row.localPort in 0..65535, "порт вне диапазона: ${row.localPort}")
                    assertTrue(row.owner.isNotEmpty(), "владелец должен быть заполнен (или прочерк)")
                }
                // Таблица честная: в живой системе слушает что-то всегда
                // (хотя бы сам тестовый процесс на ephemeral-порту).
                assertTrue(snapshot.rows.isNotEmpty(), "пустой снимок на живой системе")
            } finally {
                session.close()
            }
        } finally {
            System.clearProperty("zapp.mon")
        }
    }
}
