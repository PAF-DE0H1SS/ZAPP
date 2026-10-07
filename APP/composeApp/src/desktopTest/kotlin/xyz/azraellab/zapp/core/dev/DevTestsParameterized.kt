package xyz.azraellab.zapp.core.dev

import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.Parameterized

/**
 * JUnit-обёртка над общим реестром.
 *
 * Тесты живут в commonMain, чтобы телефон и CI гоняли один и тот же код;
 * здесь они только предъявляются JUnit как отдельные кейсы -- по одному на
 * каждый [DevTest.id], поэтому отчёт Gradle показывает сотни именованых
 * проверок, а не одну «runAll» с кучей assert'ов внутри.
 *
 * Прогон последовательный и детерминированный: тесты -- чистые функции без
 * сети и времени, параллелизм здесь только мешал бы читать падения.
 */
@RunWith(Parameterized::class)
class DevTestsParameterized(private val testId: String) {

    @Test
    fun runTest() {
        val test = DevTestRegistry.all.firstOrNull { it.id == testId }
            ?: throw AssertionError("нет теста с id=$testId")
        try {
            test.body()
        } catch (failure: AssertionError) {
            throw AssertionError("[${test.id}] ${test.name}: ${failure.message}", failure)
        }
    }

    companion object {
        @JvmStatic
        @Parameterized.Parameters(name = "{0}")
        fun tests(): List<Array<String>> =
            DevTestRegistry.all.map { arrayOf(it.id) }
    }
}
