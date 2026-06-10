package dev.romainguy.vibrance

import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

private const val PI: Float = 3.1415927f

/**
 * A Multi-Layer Perceptron that can upsample an input sRGB color to a set of pigment
 * concentrations. These concentrations are used to build a representation of the
 * input color in a 7D latent space.
 *
 * This MLP has a simple architecture:
 * - Input: 3 floats (R, G, B)
 * - 3 layers (input, hidden, output), with 32 neurons
 * - ReLU activation
 * - Output: 3 floats (pigment concentration 1, 2, 3)
 *
 * The input values must be in the [0..1] range.
 * The output of the MLP is 3 values in the [0..1] range.
 *
 * Note: this class is not thread-safe. It is however cheap to allocate per thread.
 */
internal class PigmentsModel {
    private val positionalEncodingFrequencies: FloatArray =
            PigmentsModelWeights.positionalEncodingFrequencies
    private val layer0Weights: FloatArray = PigmentsModelWeights.net0Weight
    private val layer0Bias: FloatArray = PigmentsModelWeights.net0Bias
    private val layer2Weights: FloatArray = PigmentsModelWeights.net2Weight
    private val layer2Bias: FloatArray = PigmentsModelWeights.net2Bias
    private val layer4Weights: FloatArray = PigmentsModelWeights.net4Weight
    private val layer4Bias: FloatArray = PigmentsModelWeights.net4Bias

    // Pre-allocate working buffers
    private val encodedBuffer = FloatArray(3 + (3 * 2 * positionalEncodingFrequencies.size))
    private val h1Buffer = FloatArray(layer0Bias.size)
    private val h2Buffer = FloatArray(layer2Bias.size)

    private fun linearLayerReLU(
        input: FloatArray,
        weights: FloatArray,
        bias: FloatArray,
        output: FloatArray,
    ) {
        val inputSize = input.size
        val outputSize = output.size

        // Eliminate OOBs on Android
        if (outputSize > bias.size) return
        if (inputSize * outputSize > weights.size) return

        for (i in output.indices) {
            var sum = bias[i]
            val row = i * inputSize
            for (j in 0 until inputSize) {
                sum += input[j] * weights[row + j]
            }
            // Apply the ReLU activation function on write-out
            output[i] = max(0.0f, sum)
        }
    }

    private fun encodePosition(r: Float, g: Float, b: Float) {
        val buffer = encodedBuffer
        buffer[0] = r
        buffer[1] = g
        buffer[2] = b

        var index = 3
        var frequency = PI
        repeat(positionalEncodingFrequencies.size) {
            buffer[index++] = sin(r * frequency)
            buffer[index++] = sin(g * frequency)
            buffer[index++] = sin(b * frequency)

            buffer[index++] = cos(r * frequency)
            buffer[index++] = cos(g * frequency)
            buffer[index++] = cos(b * frequency)

            // NOTE: This assumes the frequencies are powers of 2
            frequency *= 2.0f
        }
    }

    /**
     * Predicts the pigment concentrations for the given sRGB input. The values output
     * by this method are always in the range 0 to 1.
     *
     * @param r The red component of the input color, between 0 and 1.
     * @param g The green component of the input color, between 0 and 1.
     * @param b The blue component of the input color, between 0 and 1
     * @param concentrations The array where the output concentrations will
     *   be written, starting at index 0. The array must be of at least size 3.
     */
    fun predict(r: Float, g: Float, b: Float, concentrations: FloatArray): FloatArray {
        encodePosition(r, g, b)

        // Combine linear layer + ReLU step to reduce instruction count
        linearLayerReLU(encodedBuffer, layer0Weights, layer0Bias, h1Buffer)
        linearLayerReLU(h1Buffer, layer2Weights, layer2Bias, h2Buffer)
        linearLayerReLU(h2Buffer, layer4Weights, layer4Bias, concentrations)

        // Saturate
        // linearLayerReLU() already does max(0, v)
        concentrations[0] = min(concentrations[0], 1.0f)
        concentrations[1] = min(concentrations[1], 1.0f)
        concentrations[2] = min(concentrations[2], 1.0f)

        return concentrations
    }
}
