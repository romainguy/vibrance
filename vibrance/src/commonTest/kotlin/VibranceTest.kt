import dev.romainguy.vibrance.Vibrance
import kotlin.floatArrayOf
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class VibranceTest {
    private val vibrance = Vibrance()

    @Test
    fun colorRoundTrip() {
        val colors = arrayOf(
            floatArrayOf(0.0f, 0.0f, 0.0f),
            floatArrayOf(0.0f, 0.0f, 1.0f),
            floatArrayOf(1.0f, 1.0f, 0.0f),
            floatArrayOf(0.0f, 0.0f, 1.0f),
            floatArrayOf(1.0f, 0.0f, 0.0f),
            floatArrayOf(1.0f, 0.0f, 1.0f),
            floatArrayOf(0.1f, 0.7f, 0.1f),
            floatArrayOf(1.0f, 1.0f, 1.0f),
            floatArrayOf(0.0f, 0.5f, 1.0f),
            floatArrayOf(1.0f, 1.0f, 0.0f),
            floatArrayOf(0.9f, 0.0f, 0.1f),
            floatArrayOf(78.0f / 255.0f, 113.0f / 255.0f, 253.0f / 255.0f),
            floatArrayOf(72.0f / 255.0f, 107.0f / 255.0f, 249.0f / 255.0f),
            floatArrayOf(45.0f / 255.0f, 81.0f / 255.0f, 225.0f / 255.0f),
            floatArrayOf(21.0f / 255.0f, 40.0f / 255.0f, 192.0f / 255.0f)
        )
        for (color in colors) {
            val latent = vibrance.colorToLatentColor(color[0], color[1], color[2])
            for (v in latent) {
                assertTrue(v in 0.0f..1.0f, "Expected latent value in [0, 1], got $v")
            }
            val newColor = vibrance.latentColorToColor(latent)
            assertAlmostEquals(color, newColor)
        }
    }

    @Test
    fun latentColorsMix() {
        val latent1 = vibrance.colorToLatentColor(1.0f, 0.0f, 0.0f)
        val latent2 = vibrance.colorToLatentColor(0.0f, 0.0f, 1.0f)

        val result0 = vibrance.latentColorsMix(latent1, latent2, 0.0f)
        assertAlmostEquals(floatArrayOf(1.0f, 0.0f, 0.0f), result0)

        val result1 = vibrance.latentColorsMix(latent1, latent2, 1.0f)
        assertAlmostEquals(floatArrayOf(0.0f, 0.0f, 1.0f), result1)

        val resultHalf = vibrance.latentColorsMix(latent1, latent1, 0.5f)
        assertAlmostEquals(floatArrayOf(1.0f, 0.0f, 0.0f), resultHalf)
    }

    @Test
    fun colorsMix() {
        val result0 = vibrance.colorsMix(1.0f, 0.0f, 0.0f, 0.0f, 0.0f, 1.0f, 0.0f)
        assertAlmostEquals(floatArrayOf(1.0f, 0.0f, 0.0f), result0)

        val result1 = vibrance.colorsMix(1.0f, 0.0f, 0.0f, 0.0f, 0.0f, 1.0f, 1.0f)
        assertAlmostEquals(floatArrayOf(0.0f, 0.0f, 1.0f), result1)

        val resultHalf = vibrance.colorsMix(1.0f, 0.0f, 0.0f, 1.0f, 0.0f, 0.0f, 0.5f)
        assertAlmostEquals(floatArrayOf(1.0f, 0.0f, 0.0f), resultHalf)
    }

    @Test
    fun colorToLatentColorFailures() {
        assertFailsWith<IllegalArgumentException> {
            vibrance.colorToLatentColor(0f, 0f, 0f, FloatArray(5))
        }
    }

    @Test
    fun latentColorToColorFailures() {
        assertFailsWith<IllegalArgumentException> {
            vibrance.latentColorToColor(FloatArray(6), FloatArray(2))
        }
        assertFailsWith<IllegalArgumentException> {
            vibrance.latentColorToColor(FloatArray(5), FloatArray(3))
        }
    }

    @Test
    fun latentColorsMixFailures() {
        assertFailsWith<IllegalArgumentException> {
            vibrance.latentColorsMix(FloatArray(5), FloatArray(6), 0f)
        }
        assertFailsWith<IllegalArgumentException> {
            vibrance.latentColorsMix(FloatArray(6), FloatArray(5), 0f)
        }
        assertFailsWith<IllegalArgumentException> {
            vibrance.latentColorsMix(FloatArray(6), FloatArray(6), 0f, FloatArray(2))
        }
    }

    @Test
    fun colorsMixFailures() {
        assertFailsWith<IllegalArgumentException> {
            vibrance.colorsMix(0f, 0f, 0f, 0f, 0f, 0f, 0f, FloatArray(2))
        }
    }

    @Test
    fun pigmentsMixFailures() {
        assertFailsWith<IllegalArgumentException> {
            vibrance.pigmentsMix(0.5f, 0.5f, 0.5f, 0.5f)
        }
        assertFailsWith<IllegalArgumentException> {
            vibrance.pigmentsMix(1.0f, 0.0f, 0.0f, 0.0f, FloatArray(2))
        }
    }

    @Test
    fun colorsMixMatchesLatentColorsMix() {
        val pairs = arrayOf(
            floatArrayOf(1.0f, 0.0f, 1.0f) to floatArrayOf(45.0f / 255.0f, 81.0f / 255.0f, 225.0f / 255.0f),
            floatArrayOf(1.0f, 1.0f, 0.0f) to floatArrayOf(0.0f, 0.0f, 1.0f)
        )
        val amounts = floatArrayOf(0.0f, 0.25f, 0.5f, 0.75f, 1.0f)
        for ((src, dst) in pairs) {
            val latentSrc = vibrance.colorToLatentColor(src[0], src[1], src[2])
            val latentDst = vibrance.colorToLatentColor(dst[0], dst[1], dst[2])
            for (t in amounts) {
                val direct = vibrance.colorsMix(src[0], src[1], src[2], dst[0], dst[1], dst[2], t)
                val latentMixed = vibrance.latentColorsMix(latentSrc, latentDst, t)
                assertAlmostEquals(latentMixed, direct, eps = 1e-6f)
            }
        }
    }

    @Test
    fun outOfGamutMixAvoidsFloorClamping() {
        val amounts = floatArrayOf(0.1f, 0.25f, 0.5f, 0.75f, 1.0f)
        var prevGreen = 0.0f
        for (t in amounts) {
            val mixed = vibrance.colorsMix(
                1.0f, 0.0f, 1.0f,
                21.0f / 255.0f, 40.0f / 255.0f, 192.0f / 255.0f,
                t
            )
            assertTrue(mixed[1] > prevGreen, "Expected green > $prevGreen at t=$t, got ${mixed[1]}")
            prevGreen = mixed[1]
        }
    }
}

private fun assertAlmostEquals(expected: FloatArray, actual: FloatArray, eps: Float = 1e-4f) {
    assertEquals(expected.size, actual.size)
    for (i in actual.indices) {
        assertEquals(expected[i], actual[i], eps)
    }
}
