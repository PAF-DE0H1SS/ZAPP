package xyz.azraellab.zapp.core.instance

import java.io.File
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.test.assertEquals

class SingleInstanceTest {

    private fun tempLock(): File =
        File.createTempFile("zapp-lock", ".test").apply { delete() }

    @Test
    fun secondCopyFailsUntilRelease() {
        val lock = tempLock()
        val first = SingleInstance(lock)
        val second = SingleInstance(lock)
        assertTrue(first.tryAcquire(), "первая копия должна взять lock")
        assertFalse(second.tryAcquire(), "вторая копия обязана проиграть")
        first.release()
        assertTrue(second.tryAcquire(), "после release lock должен освободиться")
        second.release()
    }

    @Test
    fun acquireCreatesMissingParent() {
        val lock = File(java.nio.file.Files.createTempDirectory("zapp-test").toFile(), "nested/dir/zapp.lock")
        val instance = SingleInstance(lock)
        assertTrue(instance.tryAcquire())
        instance.release()
    }

    @Test
    fun focusRoundTripRaisesCounter() {
        val server = SingleInstance.startFocusListener({ SingleInstance.raiseFocus() }, 0)
            ?: error("слушатель не поднялся")
        try {
            val before = SingleInstance.focusRequests.value
            assertTrue(SingleInstance.pingFocus(server.localPort), "пинг должен дойти")
            val deadline = System.currentTimeMillis() + 2000
            while (SingleInstance.focusRequests.value == before &&
                System.currentTimeMillis() < deadline
            ) Thread.sleep(10)
            assertEquals(before + 1, SingleInstance.focusRequests.value, "счётчик должен вырасти")
        } finally {
            server.close()
        }
    }

    @Test
    fun pingToDeadPortIsHarmless() {
        val port = java.net.ServerSocket(0, 1, java.net.InetAddress.getLoopbackAddress())
            .use { it.localPort }
        assertFalse(SingleInstance.pingFocus(port), "пинг на закрытый порт -- false")
    }
}
