import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.system.measureNanoTime

class PigmentsModel(
    private val peFreqs: FloatArray = PigmentsModelWeights.peFreqs,
    private val layer0Weights: FloatArray = PigmentsModelWeights.net0Weight,
    private val layer0Bias: FloatArray = PigmentsModelWeights.net0Bias,
    private val layer2Weights: FloatArray = PigmentsModelWeights.net2Weight,
    private val layer2Bias: FloatArray = PigmentsModelWeights.net2Bias,
    private val layer4Weights: FloatArray = PigmentsModelWeights.net4Weight,
    private val layer4Bias: FloatArray = PigmentsModelWeights.net4Bias
) {
    private val encodedSize = 3 + (3 * 2 * peFreqs.size)
    private val l0Out = layer0Bias.size
    private val l2Out = layer2Bias.size
    private val l4Out = layer4Bias.size

    // Pre-allocate working buffers
    private val encodedBuffer = FloatArray(encodedSize)
    private val h1Buffer = FloatArray(l0Out)
    private val h2Buffer = FloatArray(l2Out)
    private val outBuffer = FloatArray(l4Out)

    private fun linearLayer(
        input: FloatArray,
        inputSize: Int,
        weights: FloatArray,
        bias: FloatArray,
        output: FloatArray,
        outputSize: Int
    ) {
        for (i in 0 until outputSize) {
            var sum = bias[i]
            val row = i * inputSize
            for (j in 0 until inputSize) {
                sum += input[j] * weights[row + j]
            }
            output[i] = sum
        }
    }

    private fun encodePosition(r: Float, g: Float, b: Float) {
        val buffer = encodedBuffer
        buffer[0] = r
        buffer[1] = g
        buffer[2] = b

        val pi = Math.PI.toFloat()

        var index = 3
        for (freq in peFreqs) {
            val factor = freq * pi
            buffer[index++] = sin(r * factor)
            buffer[index++] = sin(g * factor)
            buffer[index++] = sin(b * factor)
            
            buffer[index++] = cos(r * factor)
            buffer[index++] = cos(g * factor)
            buffer[index++] = cos(b * factor)
        }
    }

    fun predict(r: Float, g: Float, b: Float): FloatArray {
        encodePosition(r, g, b)

        linearLayer(encodedBuffer, encodedSize, layer0Weights, layer0Bias, h1Buffer, l0Out)
        for (i in 0 until l0Out) h1Buffer[i] = max(0f, h1Buffer[i])

        linearLayer(h1Buffer, l0Out, layer2Weights, layer2Bias, h2Buffer, l2Out)
        for (i in 0 until l2Out) h2Buffer[i] = max(0f, h2Buffer[i])

        linearLayer(h2Buffer, l2Out, layer4Weights, layer4Bias, outBuffer, l4Out)

        // Saturate
        for (i in outBuffer.indices) outBuffer[i] = min(max(0f, outBuffer[i]), 1.0f)

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
