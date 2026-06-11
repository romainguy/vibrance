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
            floatArrayOf(0.0f, 0.0f, 1.0f) to floatArrayOf(0.3211604f, 0.6774757f, 0.004375281f, -0.109690964f, -0.3637043f, 0.21153349f),
            floatArrayOf(1.0f, 1.0f, 0.0f) to floatArrayOf(0.0f, 0.00677f, 0.9853404f, 0.16104305f, 0.08690518f, -0.07893095f),
            floatArrayOf(0.0f, 0.0f, 1.0f) to floatArrayOf(0.3211604f, 0.6774757f, 0.004375281f, -0.109690964f, -0.3637043f, 0.21153349f),
            floatArrayOf(1.0f, 0.0f, 0.0f) to floatArrayOf(0.0f, 0.559122f, 0.43699133f, 0.18845308f, -0.48159546f, -0.35926425f),
            floatArrayOf(1.0f, 0.0f, 1.0f) to floatArrayOf(0.0012811907f, 0.99861294f, 0.0f, 0.2637412f, -0.29221755f, 0.22081065f),
            floatArrayOf(0.1f, 0.7f, 0.1f) to floatArrayOf(0.25876212f, 0.0027093515f, 0.73810565f, -0.026051365f, -0.00632751f, -0.11682441f),
            floatArrayOf(1.0f, 1.0f, 1.0f) to floatArrayOf(0.004451909f, 0.006105016f, 0.002652506f, 0.21576285f, 0.06151277f, 0.056721628f),
            floatArrayOf(0.0f, 0.5f, 1.0f) to floatArrayOf(0.41332102f, 0.33704773f, 4.5567285E-6f, -0.09939486f, -0.0054453015f, 0.15009612f),
            floatArrayOf(1.0f, 1.0f, 0.0f) to floatArrayOf(0.0f, 0.00677f, 0.9853404f, 0.16104305f, 0.08690518f, -0.07893095f),
            floatArrayOf(0.9f, 0.0f, 0.1f) to floatArrayOf(1.5143969E-4f, 0.6298656f, 0.36941552f, 0.09470338f, -0.4547106f, -0.29390875f)
        )

        for ((color, latent) in expected) {
            val latentColor = vibrance.colorToLatentColor(color[0], color[1], color[2])
            assertAlmostEquals(latent, latentColor)
        }
    }

    @Test
    fun latentColorToColor() {
        val expected = mapOf(
            floatArrayOf(0.0f, 0.0f, 1.0f) to floatArrayOf(0.3211604f, 0.6774757f, 0.004375281f, -0.109690964f, -0.3637043f, 0.21153349f),
            floatArrayOf(1.0f, 1.0f, 0.0f) to floatArrayOf(0.0f, 0.00677f, 0.9853404f, 0.16104305f, 0.08690518f, -0.07893095f),
            floatArrayOf(0.0f, 0.0f, 1.0f) to floatArrayOf(0.3211604f, 0.6774757f, 0.004375281f, -0.109690964f, -0.3637043f, 0.21153349f),
            floatArrayOf(1.0f, 0.0f, 0.0f) to floatArrayOf(0.0f, 0.559122f, 0.43699133f, 0.18845308f, -0.48159546f, -0.35926425f),
            floatArrayOf(1.0f, 0.0f, 1.0f) to floatArrayOf(0.0012811907f, 0.99861294f, 0.0f, 0.2637412f, -0.29221755f, 0.22081065f),
            floatArrayOf(0.1f, 0.7f, 0.1f) to floatArrayOf(0.25876212f, 0.0027093515f, 0.73810565f, -0.026051365f, -0.00632751f, -0.11682441f),
            floatArrayOf(1.0f, 1.0f, 1.0f) to floatArrayOf(0.004451909f, 0.006105016f, 0.002652506f, 0.21576285f, 0.06151277f, 0.056721628f),
            floatArrayOf(0.0f, 0.5f, 1.0f) to floatArrayOf(0.41332102f, 0.33704773f, 4.5567285E-6f, -0.09939486f, -0.0054453015f, 0.15009612f),
            floatArrayOf(1.0f, 1.0f, 0.0f) to floatArrayOf(0.0f, 0.00677f, 0.9853404f, 0.16104305f, 0.08690518f, -0.07893095f),
            floatArrayOf(0.9f, 0.0f, 0.1f) to floatArrayOf(1.5143969E-4f, 0.6298656f, 0.36941552f, 0.09470338f, -0.4547106f, -0.29390875f)

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
        assertAlmostEquals(floatArrayOf(0.0f, 0.5411819f, 0.8851201f), color)

        val color2 = vibrance.pigmentsMix(0.0f, 1.0f, 0.0f, 0.0f)
        assertAlmostEquals(floatArrayOf(0.74150074f, 0.29173213f, 0.77911013f), color2)
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
