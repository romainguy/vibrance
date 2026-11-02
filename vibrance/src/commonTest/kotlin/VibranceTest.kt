import dev.romainguy.vibrance.Vibrance
import kotlin.floatArrayOf
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class VibranceTest {
    private val vibrance = Vibrance()

    @Test
    fun colorRoundTrip() {
        val colors = arrayOf(
            floatArrayOf(0.0f, 0.0f, 1.0f),
            floatArrayOf(1.0f, 1.0f, 0.0f),
            floatArrayOf(0.0f, 0.0f, 1.0f),
            floatArrayOf(1.0f, 0.0f, 0.0f),
            floatArrayOf(1.0f, 0.0f, 1.0f),
            floatArrayOf(0.1f, 0.7f, 0.1f),
            floatArrayOf(1.0f, 1.0f, 1.0f),
            floatArrayOf(0.0f, 0.5f, 1.0f),
            floatArrayOf(1.0f, 1.0f, 0.0f),
            floatArrayOf(0.9f, 0.0f, 0.1f)
        )
        for (color in colors) {
            val latent = vibrance.colorToLatentColor(color[0], color[1], color[2])
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
}

private fun assertAlmostEquals(expected: FloatArray, actual: FloatArray, eps: Float = 1e-4f) {
    assertEquals(expected.size, actual.size)
    for (i in actual.indices) {
        assertEquals(expected[i], actual[i], eps)
    }
}
