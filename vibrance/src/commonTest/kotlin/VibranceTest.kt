import dev.romainguy.vibrance.Vibrance
import kotlin.floatArrayOf
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class VibranceTest {
    private val vibrance = Vibrance()

    @Test
    fun colorToLatentColor() {
        val expected = mapOf(
            floatArrayOf(0.0f, 0.0f, 1.0f) to floatArrayOf(0.9765675f, 0.004158199f, 3.9298087E-4f, 0.0f, -0.2219084f, 0.28333044f),
            floatArrayOf(1.0f, 1.0f, 0.0f) to floatArrayOf(0.0014030053f, 0.0f, 0.65452236f, 0.18759239f, 0.267263f, -0.07855922f),
            floatArrayOf(0.0f, 0.0f, 1.0f) to floatArrayOf(0.9765675f, 0.004158199f, 3.9298087E-4f, 0.0f, -0.2219084f, 0.28333044f),
            floatArrayOf(1.0f, 0.0f, 0.0f) to floatArrayOf(0.0f, 0.5241588f, 0.46216163f, 0.25057334f, -0.19671276f, -0.13793278f),
            floatArrayOf(1.0f, 0.0f, 1.0f) to floatArrayOf(0.0f, 0.49043342f, 0.0f, 0.23428977f, -0.2642768f, 0.2879299f),
            floatArrayOf(0.1f, 0.7f, 0.1f) to floatArrayOf(0.1748402f, 0.010940269f, 0.81081474f, 0.002984031f, 0.007104218f, -0.053069822f),
            floatArrayOf(1.0f, 1.0f, 1.0f) to floatArrayOf(0.0f, 0.0024201833f, 0.0f, 0.1566894f, 0.17527312f, 0.14781255f),
            floatArrayOf(0.0f, 0.5f, 1.0f) to floatArrayOf(0.62648684f, 0.00920548f, 0.0010208469f, -0.018665671f, -0.0949896f, 0.22143108f),
            floatArrayOf(1.0f, 1.0f, 0.0f) to floatArrayOf(0.0014030053f, 0.0f, 0.65452236f, 0.18759239f, 0.267263f, -0.07855922f),
            floatArrayOf(0.9f, 0.0f, 0.1f) to floatArrayOf(0.0f, 0.5875807f, 0.41303727f, 0.0416829f, -0.17472038f, -0.13724875f)
        )

        for ((color, latent) in expected) {
            val latentColor = vibrance.colorToLatentColor(color[0], color[1], color[2])
            assertAlmostEquals(latent, latentColor)
        }
    }

    @Test
    fun latentColorToColor() {
        val expected = mapOf(
            floatArrayOf(0.0f, 0.0f, 1.0f) to floatArrayOf(0.9765675f, 0.004158199f, 3.9298087E-4f, 0.0f, -0.2219084f, 0.28333044f),
            floatArrayOf(1.0f, 1.0f, 0.0f) to floatArrayOf(0.0014030053f, 0.0f, 0.65452236f, 0.18759239f, 0.267263f, -0.07855922f),
            floatArrayOf(0.0f, 0.0f, 1.0f) to floatArrayOf(0.9765675f, 0.004158199f, 3.9298087E-4f, 0.0f, -0.2219084f, 0.28333044f),
            floatArrayOf(1.0f, 0.0f, 0.0f) to floatArrayOf(0.0f, 0.5241588f, 0.46216163f, 0.25057334f, -0.19671276f, -0.13793278f),
            floatArrayOf(1.0f, 0.0f, 1.0f) to floatArrayOf(0.0f, 0.49043342f, 0.0f, 0.23428977f, -0.2642768f, 0.2879299f),
            floatArrayOf(0.1f, 0.7f, 0.1f) to floatArrayOf(0.1748402f, 0.010940269f, 0.81081474f, 0.002984031f, 0.007104218f, -0.053069822f),
            floatArrayOf(1.0f, 1.0f, 1.0f) to floatArrayOf(0.0f, 0.0024201833f, 0.0f, 0.1566894f, 0.17527312f, 0.14781255f),
            floatArrayOf(0.0f, 0.5f, 1.0f) to floatArrayOf(0.62648684f, 0.00920548f, 0.0010208469f, -0.018665671f, -0.0949896f, 0.22143108f),
            floatArrayOf(1.0f, 1.0f, 0.0f) to floatArrayOf(0.0014030053f, 0.0f, 0.65452236f, 0.18759239f, 0.267263f, -0.07855922f),
            floatArrayOf(0.9f, 0.0f, 0.1f) to floatArrayOf(0.0f, 0.5875807f, 0.41303727f, 0.0416829f, -0.17472038f, -0.13724875f)
        )

        for ((color, latent) in expected) {
            val resultColor = vibrance.latentColorToColor(latent)
            assertAlmostEquals(color, resultColor)
        }
    }

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
    fun pigmentsMix() {
        val color = vibrance.pigmentsMix(1.0f, 0.0f, 0.0f, 0.0f)
        assertAlmostEquals(floatArrayOf(0.0f, 0.50486183f, 0.8624178f), color)

        val color2 = vibrance.pigmentsMix(0.0f, 1.0f, 0.0f, 0.0f)
        assertAlmostEquals(floatArrayOf(0.8649926f, 0.37347656f, 0.76584274f), color2)
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
