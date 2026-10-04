package xyz.azraellab.zapp.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Настройки: формат файла и обратная совместимость.
 *
 * Файл настроек переживает обновления: старый конфиг без `perf`/`usage`
 * обязан читаться с дефолтами, а новый -- записываться целиком, чтобы после
 * обновления фон и список выглядели ровно как до него.
 */
class AppConfigCodecTest {

    @Test
    fun defaultsSurviveRoundtrip() {
        val original = AppConfig()
        val decoded = ConfigCodec.decodeConfig(ConfigCodec.encodeConfig(original))
        assertEquals(original, decoded)
    }

    @Test
    fun perfAndUsageSurviveRoundtrip() {
        val original = AppConfig(
            perf = PerfConfig(animatedBackground = false, backgroundFps = 15, listPageSize = 60),
            usage = UsageProfile(
                purpose = UsageProfile.PURPOSE_PRIVACY,
                device = UsageProfile.DEVICE_LOW,
                priority = UsageProfile.PRIORITY_BATTERY,
                applied = true
            ),
            welcomeDone = true
        )
        val decoded = ConfigCodec.decodeConfig(ConfigCodec.encodeConfig(original))
        assertEquals(original, decoded)
        assertEquals(false, decoded.perf.animatedBackground)
        assertEquals(UsageProfile.PURPOSE_PRIVACY, decoded.usage.purpose)
        assertTrue(decoded.welcomeDone)
    }

    @Test
    fun oldFileWithoutPerfAndUsageGetsDefaults() {
        // Старый файл: ни блока perf, ни блока usage в нём нет.
        val old = """
            {
                "zapret": {"allTraffic": true},
                "trafficMonitorEnabled": false,
                "welcomeDone": true,
                "version": 1
            }
        """.trimIndent()
        val decoded = ConfigCodec.decodeConfig(old)
        assertEquals(PerfConfig(), decoded.perf)
        assertEquals(UsageProfile(), decoded.usage)
        // Старые поля не потеряны.
        assertTrue(decoded.zapret.allTraffic)
        assertEquals(false, decoded.trafficMonitorEnabled)
        assertTrue(decoded.welcomeDone)
    }

    @Test
    fun unknownKeysAreIgnored() {
        val withJunk = """
            {"futureField": {"nested": 1}, "perf": {"backgroundFps": 24}, "other": 5}
        """.trimIndent()
        val decoded = ConfigCodec.decodeConfig(withJunk)
        assertNotNull(decoded)
        assertEquals(24, decoded.perf.backgroundFps)
        assertEquals(30, decoded.perf.listPageSize, "отсутствующие поля берут дефолт")
    }

    @Test
    fun corruptFileFallsBackToDefaults() {
        assertEquals(AppConfig(), ConfigCodec.decodeConfig("not a json at all"))
        assertEquals(AppConfig(), ConfigCodec.decodeConfig("{\"zapret\": "))
    }

    @Test
    fun encodedFileContainsEverything() {
        // encodeDefaults=true: в файле видно все ручки, а не только изменённые.
        val text = ConfigCodec.encodeConfig(AppConfig())
        for (needle in listOf(
            "\"animatedBackground\"", "\"backgroundFps\"", "\"listPageSize\"",
            "\"usage\"", "\"applied\"", "\"welcomeDone\"", "\"detailLogEnabled\""
        )) {
            assertTrue(needle in text, "в файле нет $needle")
        }
    }
}
