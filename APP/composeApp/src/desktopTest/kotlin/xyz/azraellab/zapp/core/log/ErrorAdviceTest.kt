package xyz.azraellab.zapp.core.log

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import xyz.azraellab.zapp.core.Str

/**
 * Рекомендации «что делать» по тексту ошибки.
 *
 * Таблица решает две задачи сразу: строку показать пользователю и флаг
 * transient отдать движку -- повторять имеет смысл только первое.
 */
class ErrorAdviceTest {

    @Test
    fun `занятый порт -- не повторять, а освободить`() {
        val advice = adviceFor("bind: address already in use")!!
        assertEquals(Str.ERR_ADV_PORT, advice.key)
        assertFalse(advice.transient)
    }

    @Test
    fun `таймаут -- повторяемая ситуация`() {
        val advice = adviceFor("context deadline exceeded: i/o timeout")!!
        assertEquals(Str.ERR_ADV_TIMEOUT, advice.key)
        assertTrue(advice.transient)
    }

    @Test
    fun `отказ соединения -- узел мёртв, другой узел`() {
        val advice = adviceFor("dial tcp 1.2.3.4:443: connection refused")!!
        assertEquals(Str.ERR_ADV_REFUSED, advice.key)
        assertFalse(advice.transient)
    }

    @Test
    fun `нет сети -- повторить после восстановления`() {
        assertTrue(adviceFor("network is unreachable")!!.transient)
        assertTrue(adviceFor("no route to host")!!.transient)
    }

    @Test
    fun `tls и dns -- повторяемые, но свои строки`() {
        assertEquals(Str.ERR_ADV_TLS, adviceFor("tls: handshake failure")!!.key)
        assertEquals(Str.ERR_ADV_DNS, adviceFor("lookup foo.example: server misbehaving")!!.key)
    }

    @Test
    fun `нет прав и нет файла -- не повторять`() {
        assertFalse(adviceFor("open /data/x: permission denied")!!.transient)
        assertFalse(adviceFor("exec: no such file or directory")!!.transient)
        assertFalse(adviceFor("unknown option --wat")!!.transient)
    }

    @Test
    fun `регистр текста не важен`() {
        assertEquals(
            adviceFor("Address Already In Use"),
            adviceFor("address already in use")
        )
    }

    @Test
    fun `незнакомый текст -- без выдумок`() {
        assertNull(adviceFor("всё хорошо и это не ошибка"))
        assertNull(adviceFor(""))
    }
}
