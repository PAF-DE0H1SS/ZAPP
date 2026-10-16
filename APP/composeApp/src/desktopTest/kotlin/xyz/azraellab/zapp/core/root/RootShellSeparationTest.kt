package xyz.azraellab.zapp.core.root

import xyz.azraellab.zapp.core.BuildMode
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

/**
 * Гарант разделения root / noroot на уровне прав.
 *
 * Смысла у проверки столько, на сколько устройство теста вообще умеет
 * повышать права: здесь `su` есть, и без гварда `mode()` вернул бы не
 * `NONE`. Это и есть то, что ломается молча -- норут-сборка обязана
 * отвечать `NONE` **до** какой-либо пробы, а не после её результата.
 *
 * Вторая половина -- [RootShell.forget]: без сброса «Root: Нет» на
 * экране остаётся от предыдущей сессии, пока политика Magisk меняется
 * вне приложения.
 */
class RootShellSeparationTest {

    @Test
    fun norootNeverProbes() {
        val saved = BuildMode.rootAllowed
        try {
            RootShell.forget()
            BuildMode.rootAllowed = false
            assertEquals(
                RootShell.Mode.NONE,
                RootShell.mode(),
                "норут обязан отвечать NONE, не запуская su"
            )
            assertFalse(RootShell.available(), "норут не может считать root доступным")

            // Сброс и повтор: кэш не должен переживать запрет сборки.
            RootShell.forget()
            assertEquals(RootShell.Mode.NONE, RootShell.mode(), "после сброса -- всё тот же NONE")
        } finally {
            BuildMode.rootAllowed = saved
            RootShell.forget()
        }
    }

    @Test
    fun forgetAsksAgain() {
        val saved = BuildMode.rootAllowed
        try {
            BuildMode.rootAllowed = true
            RootShell.forget()
            val first = RootShell.mode()
            RootShell.forget()
            val second = RootShell.mode()
            assertEquals(
                first,
                second,
                "повторная проба на том же устройстве даёт тот же ответ"
            )
        } finally {
            BuildMode.rootAllowed = saved
            RootShell.forget()
        }
    }
}
