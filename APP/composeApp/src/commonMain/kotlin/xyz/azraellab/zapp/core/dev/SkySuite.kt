package xyz.azraellab.zapp.core.dev

import androidx.compose.ui.graphics.luminance
import kotlin.math.absoluteValue
import kotlin.random.Random
import xyz.azraellab.zapp.core.sky.StarKind
import xyz.azraellab.zapp.core.sky.cometFlight
import xyz.azraellab.zapp.core.sky.cometHead
import xyz.azraellab.zapp.core.sky.cometOpacity
import xyz.azraellab.zapp.core.sky.generateStars
import xyz.azraellab.zapp.core.sky.logUniform
import xyz.azraellab.zapp.core.sky.rayExit
import xyz.azraellab.zapp.core.sky.skyParams
import xyz.azraellab.zapp.core.sky.starBrightness
import xyz.azraellab.zapp.core.sky.starKind
import xyz.azraellab.zapp.ui.starfieldPalette

/**
 * Небо: математика фона -- звёзды, мерцание, кометы, палитра.
 *
 * Тестируется чистая математика из `core/sky/SkyMath.kt` (та самая, что
 * использует отрисовка), поэтому тесты проверяют реальное поведение
 * «звёзды мигают в своём ритме, комета рождается за экраном и умирает за
 * экраном».
 */
internal fun skyTests(out: MutableList<DevTest>) {
    val cat = DevCategory.THEME

    out += DevTest("sky/params-deterministic", cat, "Один seed -- одни параметры") {
        checkEq(skyParams(42L), skyParams(42L), "skyParams должно быть детерминированным")
    }

    out += DevTest("sky/params-differ-by-seed", cat, "Разные seeds -- почти наверняка разные параметры") {
        val a = skyParams(1L)
        val b = skyParams(2L)
        checkTrue(
            a.starCount != b.starCount || a.blinkLifeMaxMs != b.blinkLifeMaxMs ||
                a.cometMax != b.cometMax || a.cometSpawnMinMs != b.cometSpawnMinMs,
            "seeds 1 и 2 дали одинаковые параметры -- алгоритм не меняется"
        )
    }

    out += DevTest("sky/params-in-range", cat, "Параметры параметров внутри физически разумных границ") {
        val p = skyParams(7L)
        checkTrue(p.starCount in 85..140, "starCount=$p.starCount")
        checkTrue(p.blinkLifeMinMs in 180f..9000f, "blinkLifeMin=$p.blinkLifeMinMs")
        checkTrue(p.blinkLifeMaxMs >= p.blinkLifeMinMs, "blinkLifeMax < min")
        checkTrue(p.blinkGapMinMs in 180f..9000f, "blinkGapMin=$p.blinkGapMinMs")
        checkTrue(p.cometMin in 1..3, "cometMin=$p.cometMin")
        checkTrue(p.cometMax in p.cometMin..(p.cometMin + 3), "cometMax=$p.cometMax")
        checkTrue(p.cometSpeedMinPx in 320f..520f, "cometSpeedMin=$p.cometSpeedMinPx")
        checkTrue(p.cometSpeedMaxPx >= p.cometSpeedMinPx, "cometSpeedMax < min")
        checkTrue(p.cometDriftMax in 0.25f..0.7f, "cometDrift=$p.cometDriftMax")
    }

    out += DevTest("sky/stars-count-matches", cat, "Звёзд ровно столько, сколько заказано параметрами") {
        val p = skyParams(99L)
        checkEq(p.starCount, generateStars(p, Random(1)).size, "количество звёзд")
    }

    out += DevTest("sky/stars-in-bounds", cat, "Звёзды лежат внутри поля (0..1 по обе оси)") {
        val p = skyParams(123L)
        val stars = generateStars(p, Random(5))
        stars.forEach { s ->
            checkTrue(s.fx in 0f..1f, "fx=${s.fx}")
            checkTrue(s.fy in 0f..1f, "fy=${s.fy}")
        }
    }

    out += DevTest("sky/stars-blink-lifetimes-valid", cat, "Время жизни мигания больше нуля и в диапазоне") {
        val p = skyParams(123L)
        val stars = generateStars(p, Random(9))
        stars.forEach { s ->
            checkTrue(s.blinkLifeMs in p.blinkLifeMinMs..p.blinkLifeMaxMs, "life=${s.blinkLifeMs}")
            checkTrue(s.blinkGapMs in p.blinkGapMinMs..p.blinkGapMaxMs, "gap=${s.blinkGapMs}")
        }
    }

    out += DevTest("sky/star-king-distribution", cat, "Белые звёзды -- большинство, красные/голубые -- редкость") {
        var white = 0
        var rare = 0
        for (roll in 0 until 10_000) {
            when (starKind(roll / 10_000f)) {
                StarKind.WHITE -> white++
                StarKind.RED, StarKind.BLUE -> rare++
                else -> {}
            }
        }
        checkTrue(white > 5000, "белых белых мало: $white")
        checkTrue(rare in 300..800, "редких слишком много/мало: $rare")
    }

    out += DevTest("sky/star-brightness-cycle", cat, "Звезда мигает: цикл горит/спит в правильных долях") {
        // life 400ms, gap 200ms: треть цикла -- пауза, две трети -- свет.
        val life = 400f
        val gap = 200f
        // На самом старте рампа ещё в нуле; чуть дальше звезда уже разгорается.
        checkTrue(starBrightness(40f, 0f, life, gap) > 0f, "в начале цикла звезда разгорается")
        checkTrue(starBrightness(40f, 0f, life, gap) < 1f, "первая рампа не сразу на максимум")
        // В середине яркой фазы -- максимум.
        checkTrue(starBrightness(life / 2, 0f, life, gap) >= 1f, "максимум в середине вспышки")
        // В глубине паузы -- ноль.
        checkTrue(starBrightness(life + gap / 2, 0f, life, gap) == 0f, "в паузе ноль")
    }

    out += DevTest("sky/star-brightness-phase-shifts", cat, "Фаза сдвигает время вспышки") {
        val life = 500f
        val gap = 500f
        // phase=0.5 -- одна и та же точка, но через половину цикла.
        val same = starBrightness(0f, 0f, life, gap)
        val shifted = starBrightness((life + gap) / 2f, 0.5f, life, gap)
        checkTrue((same - shifted).absoluteValue < 0.001f, "фаза не даёт сдвиг: $same vs $shifted")
    }

    out += DevTest("sky/log-uniform-inside", cat, "logUniform всегда внутри диапазона") {
        val rnd = Random(3)
        repeat(100) {
            val v = logUniform(200f, 5000f, rnd)
            checkTrue(v in 180f..5200f, "logUniform=$v")
        }
    }

    out += DevTest("sky/comet-spawn-outside", cat, "Комета рождается строго за границей экрана") {
        val p = skyParams(7L)
        val rnd = Random(11)
        repeat(200) {
            val f = cometFlight(1080f, 2400f, rnd, 900f, p.cometDriftMax)
            val s = f.spawn
            val outside = s.x < 0f || s.x > 1080f || s.y < 0f || s.y > 2400f
            checkTrue(outside, "спавн внутри экрана: $s")
        }
    }

    out += DevTest("sky/comet-head-reaches-far-side", cat, "Голова проходит весь экран и умирает за другой гранью") {
        val p = skyParams(7L)
        val rnd = Random(12)
        val w = 1080f
        val h = 2400f
        repeat(400) {
            val f = cometFlight(w, h, rnd, 900f, p.cometDriftMax)
            val head = cometHead(f, f.lifeMs)
            val outside = head.x < 0f || head.x > w || head.y < 0f || head.y > h
            checkTrue(outside, "голова на ${f.lifeMs}ms внутри экрана: $head")
        }
    }

    out += DevTest("sky/comet-life-positive", cat, "Время жизни кометы положительно и конечно") {
        val p = skyParams(7L)
        val rnd = Random(13)
        repeat(200) {
            val f = cometFlight(1080f, 2400f, rnd, 900f, p.cometDriftMax)
            checkTrue(f.lifeMs > 0f && f.lifeMs.isFinite(), "lifeMs=${f.lifeMs}")
        }
    }

    out += DevTest("sky/comet-opacity-fades", cat, "Комета разгорается на входе и гаснет у конца пути") {
        val p = skyParams(7L)
        val rnd = Random(14)
        repeat(50) {
            val f = cometFlight(1080f, 2400f, rnd, 900f, p.cometDriftMax)
            checkEq(0f, cometOpacity(f, 0f), "на старте невидима")
            val mid = cometOpacity(f, f.lifeMs / 2)
            checkTrue(mid > 0.9f, "в середине должна гореть: $mid")
            checkTrue(cometOpacity(f, f.lifeMs) < 0.15f, "у конца почти погасла")
        }
    }

    out += DevTest("sky/ray-exit-forward", cat, "rayExit всегда даёт выход вперёд по лучу") {
        val tl = xyz.azraellab.zapp.core.sky.SkyPoint(-160f, -160f)
        val br = xyz.azraellab.zapp.core.sky.SkyPoint(1240f, 2560f)
        repeat(500) {
            val ox = -200f + it % 17 * 13f
            val oy = -200f + it % 13 * 17f
            val ux = kotlin.math.cos(it * 0.37f).toFloat()
            val uy = kotlin.math.sin(it * 0.37f).toFloat()
            val t = rayExit(xyz.azraellab.zapp.core.sky.SkyPoint(ox, oy), ux, uy, tl, br)
            checkTrue(t > 0f, "выход не вперёд: t=$t")
            // Точка выхода -- на границе строго расширенного прямоугольника:
            // за неё комету уводит овершут в cometFlight (+60px).
            val ex = ox + ux * t
            val ey = oy + uy * t
            val inRect = ex in tl.x..br.x && ey in tl.y..br.y
            val onEdge = kotlin.math.abs(ex - tl.x) < 1e-2f || kotlin.math.abs(ex - br.x) < 1e-2f ||
                kotlin.math.abs(ey - tl.y) < 1e-2f || kotlin.math.abs(ey - br.y) < 1e-2f
            checkTrue(onEdge || !inRect, "точка выхода строго внутри прямоугольника: $ex,$ey")
        }
    }

    out += DevTest("sky/palette-has-real-colors", cat, "У палитры есть все спектральные классы") {
        val pal = starfieldPalette(true)
        StarKind.entries.forEach { kind ->
            checkTrue(pal.starColors.containsKey(kind), "нет цвета для $kind")
        }
    }

    out += DevTest("sky/palette-light-inverts", cat, "Светлая тема инвертирует звёзды в тёмные") {
        val dark = starfieldPalette(true)
        val light = starfieldPalette(false)
        val d = dark.starColors[StarKind.WHITE]!!
        val l = light.starColors[StarKind.WHITE]!!
        checkTrue(l.luminance() < d.luminance(), "на светлой теме белая звезда не потемнела")
    }
}