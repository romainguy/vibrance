import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.system.measureNanoTime

class PigmentsModel(
    private val peFreqs: FloatArray = PigmentsModelWeights.peFreqs,
    private val layer0Weights: Array<FloatArray> = PigmentsModelWeights.net0Weight,
    private val layer0Bias: FloatArray = PigmentsModelWeights.net0Bias,
    private val layer2Weights: Array<FloatArray> = PigmentsModelWeights.net2Weight,
    private val layer2Bias: FloatArray = PigmentsModelWeights.net2Bias,
    private val layer4Weights: Array<FloatArray> = PigmentsModelWeights.net4Weight,
    private val layer4Bias: FloatArray = PigmentsModelWeights.net4Bias
) {
    private val encodedSize = 3 + (3 * 2 * peFreqs.size)

    private fun linearLayer(
        input: FloatArray,
        weights: Array<FloatArray>,
        bias: FloatArray,
        output: FloatArray = FloatArray(weights.size)
    ): FloatArray {
        val inputDimension = input.size
        val outputDimension = output.size

        for (i in 0 until outputDimension) {
            var sum = bias[i]
            val w = weights[i]
            for (j in 0 until inputDimension) {
                sum += input[j] * w[j]
            }
            output[i] = sum
        }

        return output
    }

    private fun encodePosition(r: Float, g: Float, b: Float): FloatArray {
        val encoded = FloatArray(encodedSize)
        
        encoded[0] = r
        encoded[1] = g
        encoded[2] = b

        val pi = Math.PI.toFloat()

        var idx = 3
        for (freq in peFreqs) {
            val factor = freq * pi

            encoded[idx++] = sin(r * factor)
            encoded[idx++] = sin(g * factor)
            encoded[idx++] = sin(b * factor)

            encoded[idx++] = cos(r * factor)
            encoded[idx++] = cos(g * factor)
            encoded[idx++] = cos(b * factor)
        }

        return encoded
    }

    fun predict(r: Float, g: Float, b: Float, output: FloatArray = FloatArray(3)): FloatArray {
        // Step 1: Positional Encoding
        val encoded = encodePosition(r, g, b)

        // Step 2: Hidden Layer 1 (Linear + ReLU)
        val h1 = linearLayer(encoded, layer0Weights, layer0Bias)
        for (i in h1.indices) h1[i] = max(0f, h1[i]) // ReLU activation

        // Step 3: Hidden Layer 2 (Linear + ReLU)
        val h2 = linearLayer(h1, layer2Weights, layer2Bias)
        for (i in h2.indices) h2[i] = max(0f, h2[i]) // ReLU activation

        // Step 4: Output Layer (Linear only, no ReLU)
        val h3 = linearLayer(h2, layer4Weights, layer4Bias, output)

        // Saturate output
        for (i in h3.indices) h3[i] = min(max(0f, h3[i]), 1.0f)

        return h3
    }
}

fun main(args: Array<String>) {
    if (args.size != 3) {
        println("Usage: PigmentsModel <r> <g> <b>")
        println("Values between 0.0 and 1.0")
        return
    }
    val model = PigmentsModel()

    val r = args[0].toFloat()
    val g = args[1].toFloat()
    val b = args[2].toFloat()

    val output: FloatArray
    val time = measureNanoTime {
        output = model.predict(r, g, b)
    }
    println("Predicted output for RGB($r, $g, $b): ${output.joinToString(", ")}")
    println("Prediction time: $time ns")
}
