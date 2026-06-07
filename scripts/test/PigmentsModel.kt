import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.system.measureNanoTime

private const val INV_TWO_PI = (1.0 / (2.0 * Math.PI)).toFloat()

@Suppress("NOTHING_TO_INLINE")
private inline fun isLeftHalf(angleRadians: Float): Boolean {
    val turns = angleRadians * INV_TWO_PI
    val fractionalTurn = turns - floor(turns)
    return abs(fractionalTurn - 0.5f) < 0.25f
}

@Suppress("NOTHING_TO_INLINE")
private inline fun Boolean.toInt() = if (this) 1 else 0

@Suppress("NOTHING_TO_INLINE")
private inline fun cosFromSin(angleRadians: Float, sinValue: Float): Float {
    return Float.fromBits((isLeftHalf(angleRadians).toInt() shl 31) or
            sqrt(1.0f - sinValue * sinValue).toRawBits())
}

class PigmentsModel() {
    private val peFreqs: FloatArray = PigmentsModelWeights.peFreqs
    private val layer0Weights: FloatArray = PigmentsModelWeights.net0Weight
    private val layer0Bias: FloatArray = PigmentsModelWeights.net0Bias
    private val layer2Weights: FloatArray = PigmentsModelWeights.net2Weight
    private val layer2Bias: FloatArray = PigmentsModelWeights.net2Bias
    private val layer4Weights: FloatArray = PigmentsModelWeights.net4Weight
    private val layer4Bias: FloatArray = PigmentsModelWeights.net4Bias

    // Pre-allocate working buffers
    private val encodedBuffer = FloatArray(3 + (3 * 2 * peFreqs.size))
    private val h1Buffer = FloatArray(layer0Bias.size)
    private val h2Buffer = FloatArray(layer2Bias.size)
    private val outBuffer = FloatArray(layer4Bias.size)

    init {
        // Pre-multiply frequencies by PI to save some instructions in
        // encodePosition()
        for (i in peFreqs.indices) {
            peFreqs[i] *= Math.PI.toFloat()
        }
    }

    private fun linearLayerReLU(
        input: FloatArray,
        weights: FloatArray,
        bias: FloatArray,
        output: FloatArray,
    ) {
        val inputSize = input.size
        val outputSize = output.size

        // Eliminite OOBs on Android
        if (outputSize > bias.size) return
        if (inputSize * outputSize > weights.size) return

        for (i in 0 until output.size) {
            var sum = bias[i]
            val row = i * inputSize
            for (j in 0 until inputSize) {
                sum += input[j] * weights[row + j]
            }
            // Apply the ReLU activation function on write-out
            output[i] = max(0.0f, sum)
        }
    }

    @Suppress("NOTHING_TO_INLINE")
    private inline fun encodePosition(r: Float, g: Float, b: Float) {
        val buffer = encodedBuffer
        buffer[0] = r
        buffer[1] = g
        buffer[2] = b

        var index = 3
        for (freq in peFreqs) {
// TODO: Measure on Android
//           buffer[index++] = sin(r * freq)
//           buffer[index++] = sin(g * freq)
//           buffer[index++] = sin(b * freq)
//           
//           buffer[index++] = cos(r * freq)
//           buffer[index++] = cos(g * freq)
//           buffer[index++] = cos(b * freq)
            val rf = r * freq
            val gf = g * freq
            val bf = b * freq
            val sr = sin(rf)
            val sg = sin(gf)
            val sb = sin(bf)
            buffer[index++] = sr
            buffer[index++] = sg
            buffer[index++] = sb
            buffer[index++] = cosFromSin(rf, sr)
            buffer[index++] = cosFromSin(gf, sg)
            buffer[index++] = cosFromSin(bf, sb)
        }
    }

    fun predict(r: Float, g: Float, b: Float): FloatArray {
        encodePosition(r, g, b)

        // Combine linear layer + ReLU step to reduce instruction count
        linearLayerReLU(encodedBuffer, layer0Weights, layer0Bias, h1Buffer)
        linearLayerReLU(h1Buffer, layer2Weights, layer2Bias, h2Buffer)
        linearLayerReLU(h2Buffer, layer4Weights, layer4Bias, outBuffer)

        // Saturate
        // linearLayerReLU() already does max(0, v)
        for (i in outBuffer.indices) outBuffer[i] = min(outBuffer[i], 1.0f)

        return outBuffer
    }
}

fun main(args: Array<String>) {
    if (args.size != 3) {
        println("Usage: PigmentsModel <r> <g> <b>")
        println("Values between 0.0 and 1.0")
        return
    }
    val model = PigmentsModel()

    val r = args[0].toInt() / 255.0f
    val g = args[1].toInt() / 255.0f
    val b = args[2].toInt() / 255.0f

    val pigments: FloatArray
    val time = measureNanoTime {
        pigments = model.predict(r, g, b)
    }
    println("Predicted output for RGB($r, $g, $b): ${pigments.joinToString(", ")}")
    println("Prediction time: $time ns")
}
