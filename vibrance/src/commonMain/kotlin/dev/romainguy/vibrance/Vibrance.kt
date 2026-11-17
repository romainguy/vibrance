@file:Suppress("DuplicatedCode", "UnnecessaryVariable", "FloatingPointLiteralPrecision")

package dev.romainguy.vibrance

import kotlin.math.abs

/**
 * [Vibrance] can be used to mix (or interpolate) sRGB colors as if they were made
 * of different concentration of paint pigments. This replicates the process of
 * subtractive color mixing, in opposition to the additive mixing process normally
 * used when mixing RGB colors.
 *
 * # Mixing process
 *
 * To mix two sRGB colors in this manner, we must first upscale them to a _latent
 * color space_, where they are represented by a series of pigment concentrations,
 * plus an RGB remainder. The full process is:
 * - Convert the source and destination sRGB colors to _latent colors_.
 * - Mix (or interpolate) the source and destination latent colors.
 * - Convert the result back to sRGB.
 *
 * To interpolate 25% between sRGB blue and yellow, you would therefore do the
 * following:
 * ```
 * val vibrance = Vibrance()
 * val latent0 = vibrance.colorToLatentColor(0.0f, 0.0f, 1.0f)
 * val latent1 = vibrance.colorToLatentColor(0.0f, 1.0f, 1.0f)
 * val color = vibrance.latentColorsMix(latent0, latent1, 0.25f)
 * ```
 * Note that [latentColorsMix] combines the interpolation and the sRGB conversion
 * steps.
 *
 * For convenience, you can achieve the same result by calling a single function:
 * ```
 * val color = vibrance.colorsMix(0.0f, 0.0f, 1.0f, 0.0f, 1.0f, 1.0f, 0.25f)
 * ```
 *
 * See the Optimization section below for more details on how to best use these
 * APIs.
 *
 * # Latent color representation
 *
 * An sRGB color is encoded in a 3D space, using the three components red, green,
 * and blue. A latent color is encoded in a 7D space:
 * - 4 pigment concentrations
 * - 3 red/green/blue remainders
 *
 * The remainders are used to guarantee that process is bijective.
 *
 * In practice, a latent color only encodes 3 pigment concentrations as the
 * 4th one is implicit and can be derived from the first 3 (see below for more
 * information).
 *
 * ## Pigments and concentrations
 *
 * The 4 paint pigments used by [Vibrance] are based on measured data for:
 * - Phthalo Blue (Green Shade)
 * - Quinacridone Magenta
 * - Hansa Yellow
 * - Titanium White
 *
 * A pigment concentration is a value between 0 and 1 representing how much of
 * that pigment to use in the final mixture. Importantly, the *sum* of the 4
 * concentrations _must_ equal 1. This is why only 3 pigments are encoded in
 * latent colors: the concentration of white is always defined as
 * `1 - (blue + magenta + yellow)`.
 *
 * # Optimization
 *
 * The [Vibrance] APIs can be used to optimize for both execution time and
 * allocations.
 *
 * ## Avoiding allocations
 *
 * All the APIs accept an optional `FloatArray` used to store the result. For
 * instance, you can avoid allocations when converting from sRGB to latent color
 * space by passing an array of 6 floats to [colorToLatentColor]:
 * ```
 * val latentColor = FloatArray(6)
 * // ...
 * colorToLatentColor(0.0f, 1.0f, 0.0f, latentColor)
 * ```
 * The array you pass will also be returned.
 *
 * [Vibrance] uses two types of `FloatArray`:
 * - Arrays of 3 floats for sRGB colors
 * - Arrays of 6 floats for latent colors
 *
 * ## Optimizing for execution time
 *
 * The method [colorsMix] is a convenient way to interpolate two sRGB colors
 * in latent color space, but the sRGB inputs must be upscaled to latent space
 * on every call. This upscaling step is the most expensive part of the mixing
 * process. When the interpolation occurs frequently (during an animation for
 * instance), it is recommended to first pre-compute the latent colors, and
 * then use [latentColorsMix].
 *
 * For reference, here are the execution times for the APIs provided by this
 * class (as measured on a Google Pixel 6 running Android 16):
 *
 * | API                  |  Time  |
 * |----------------------|--------|
 * | [colorToLatentColor] | 3.2µs  |
 * | [latentColorToColor] | 85ns   |
 * | [latentColorsMix]    | 74ns   |
 * | [colorsMix]          | 6.4µs  |
 * | [pigmentsMix]        | 80ns   |
 *
 * # Pigments mixing
 *
 * [Vibrance] also provides an API to mix pigments direction:
 * ```
 * val color = vibrance.pigmentsMix(0.1f, 0.2f, 0.5f, 0.2f)
 * ```
 *
 * As defined earlier, the sum of the pigment concetrations passed to this
 * API must equal exactly 1.
 */
class Vibrance {
    internal val model = ColorToPigmentsModel()
    internal val pigmentsBuffer = FloatArray(3)

    /**
     * Converts an sRGB color to a latent color. A latent color is a representation of a
     * color that uses a series of paint pigments concentrations and an RGB remainder.
     * Latent colors are represented as arrays of 6 floats. All the values in the arrays
     * are between 0 and 1.
     *
     * @param r The red component of the sRGB color to convert to latent space.
     * @param g The green component of the sRGB color to convert to latent space.
     * @param b The blue component of the sRGB color to convert to latent space.
     * @param latentColor An array of at least 6 floats that will store the resulting latent color.
     * @return The [latentColor] array if specified, otherwise a newly allocated array of 6 floats.
     */
    fun colorToLatentColor(
        r: Float,
        g: Float,
        b: Float,
        latentColor: FloatArray = FloatArray(6)
    ): FloatArray {
        requirePrecondition(latentColor.size >= 6) { "latentColor must have a size >= 6" }

        val r = r.fastCoerceIn(0.0f, 1.0f)
        val g = g.fastCoerceIn(0.0f, 1.0f)
        val b = b.fastCoerceIn(0.0f, 1.0f)

        val buffer = pigmentsBuffer
        model.predict(r, g, b, buffer)

        val latent0 = buffer[0]
        val latent1 = buffer[1]
        val latent2 = buffer[2]

        pigmentsMixLinear(latent0, latent1, latent2, buffer)

        latentColor[0] = latent0
        latentColor[1] = latent1
        latentColor[2] = latent2
        latentColor[3] = encodeRemainder(eotfSrgb(r), buffer[0])
        latentColor[4] = encodeRemainder(eotfSrgb(g), buffer[1])
        latentColor[5] = encodeRemainder(eotfSrgb(b), buffer[2])

        return latentColor
    }

    /**
     * Converts a latent color to an sRGB color. A latent color is a representation of a
     * color that uses a series of paint pigments concentrations and an RGB remainder.
     * Latent colors are represented as arrays of 6 floats and can be computed using
     * [colorToLatentColor]. All the values in the arrays must be between 0 and 1. The
     * sum of the first 3 elements of [latentColor] must be <= 1.0.
     *
     * @param color An array of at least 3 floats that will store the resulting sRGB color.
     * @return The [color] array if specified, otherwise a newly allocated array of 3 floats.
     */
    fun latentColorToColor(
        latentColor: FloatArray,
        color: FloatArray = FloatArray(3)
    ): FloatArray {
        requirePrecondition(latentColor.size >= 6) { "latentColor must have a size >= 6" }
        requirePrecondition(color.size >= 3) { "color must have a size >= 3" }

        val blue = latentColor[0].fastCoerceIn(0.0f, 1.0f)
        val magenta = latentColor[1].fastCoerceIn(0.0f, 1.0f)
        val yellow = latentColor[2].fastCoerceIn(0.0f, 1.0f)

        pigmentsMixLinear(blue, magenta, yellow, color)

        color[0] = oetfSrgb(applyRemainder(color[0], latentColor[3]))
        color[1] = oetfSrgb(applyRemainder(color[1], latentColor[4]))
        color[2] = oetfSrgb(applyRemainder(color[2], latentColor[5]))

        return color
    }

    /**
     * Mixes two latent colors and computes the resulting sRGB color. A latent color is a
     * representation of a color that uses a series of paint pigments concentrations and
     * an RGB remainder. Latent colors are represented as arrays of 6 floats and can be
     * computed using [colorToLatentColor]. All the values in the arrays must be between
     * 0 and 1.
     *
     * @param src The source latent color to mix, must be an array of at least 6 floats, with
     *     values between 0 and 1.
     * @param src The destination latent color to mix, must be an array of at least 6 floats, with
     *     values between 0 and 1.
     * @param amount The mix (or interpolation) amount between the source and destination
     *     colors, between 0 and 1.
     * @param color An array of at least 3 floats that will store the interpolated sRGB color.
     * @return The [color] array if specified, otherwise a newly allocated array of 3 floats.
     */
    fun latentColorsMix(
        src: FloatArray,
        dst: FloatArray,
        amount: Float,
        color: FloatArray = FloatArray(3)
    ): FloatArray {
        requirePrecondition(src.size >= 6 && dst.size >= 6) { "src and dst must have a size >= 6" }
        requirePrecondition(color.size >= 3) { "color must have a size >= 3" }

        val c0 = lerp(src[0], dst[0], amount)
        val c1 = lerp(src[1], dst[1], amount)
        val c2 = lerp(src[2], dst[2], amount)
        pigmentsMixLinear(c0, c1, c2, color)

        color[0] = oetfSrgb(applyRemainder(color[0], lerp(src[3], dst[3], amount)))
        color[1] = oetfSrgb(applyRemainder(color[1], lerp(src[4], dst[4], amount)))
        color[2] = oetfSrgb(applyRemainder(color[2], lerp(src[5], dst[5], amount)))

        return color
    }

    /**
     * Mixes two sRGB colors as concentrations of paint pigments. This method exists for convenience
     * but requires to upscale the input sRGB colors to a series of pigment concentrations every
     * time it is invoked. If the same input colors will be mixed multiple times using a different
     * mix amount, it is recommended to instead precompute the latent colors for each input using
     * [colorToLatentColor], and mixing them using [latentColorsMix].
     *
     * On a Google Pixel 6, using this method directly takes 6.4µs, while [latentColorsMix] only
     * takes 74ns (85x faster).
     *
     * The component of each input color must be between 0 and 1.
     *
     * @param srcR The red component of the source color, between 0 and 1.
     * @param srcG The green component of the source color, between 0 and 1.
     * @param srcB The blue component of the source color, between 0 and 1.
     * @param dstR The red component of the destination color, between 0 and 1.
     * @param dstG The green component of the destination color, between 0 and 1.
     * @param dstB The blue component of the destination color, between 0 and 1.
     * @param amount The mix (or interpolation) amount between the source and destination
     *     colors, between 0 and 1.
     * @param color An array of at least 3 floats that will store the interpolated sRGB color.
     * @return The [color] array if specified, otherwise a newly allocated array of 3 floats.
     */
    fun colorsMix(
        srcR: Float,
        srcG: Float,
        srcB: Float,
        dstR: Float,
        dstG: Float,
        dstB: Float,
        amount: Float,
        color: FloatArray = FloatArray(3)
    ): FloatArray {
        requirePrecondition(color.size >= 3) { "color must have a size >= 3" }

        val srcR = srcR.fastCoerceIn(0.0f, 1.0f)
        val srcG = srcG.fastCoerceIn(0.0f, 1.0f)
        val srcB = srcB.fastCoerceIn(0.0f, 1.0f)

        // Source
        model.predict(srcR, srcG, srcB, color)
        val srcPigment0 = color[0]
        val srcPigment1 = color[1]
        val srcPigment2 = color[2]

        pigmentsMixLinear(srcPigment0, srcPigment1, srcPigment2, color)
        val srcRemainderR = encodeRemainder(eotfSrgb(srcR), color[0])
        val srcRemainderG = encodeRemainder(eotfSrgb(srcG), color[1])
        val srcRemainderB = encodeRemainder(eotfSrgb(srcB), color[2])

        // Destination
        val dstR = dstR.fastCoerceIn(0.0f, 1.0f)
        val dstG = dstG.fastCoerceIn(0.0f, 1.0f)
        val dstB = dstB.fastCoerceIn(0.0f, 1.0f)

        model.predict(dstR, dstG, dstB, color)
        val dstPigment0 = color[0]
        val dstPigment1 = color[1]
        val dstPigment2 = color[2]

        pigmentsMixLinear(dstPigment0, dstPigment1, dstPigment2, color)
        val dstRemainderR = encodeRemainder(eotfSrgb(dstR), color[0])
        val dstRemainderG = encodeRemainder(eotfSrgb(dstG), color[1])
        val dstRemainderB = encodeRemainder(eotfSrgb(dstB), color[2])

        val c0 = lerp(srcPigment0, dstPigment0, amount)
        val c1 = lerp(srcPigment1, dstPigment1, amount)
        val c2 = lerp(srcPigment2, dstPigment2, amount)
        pigmentsMixLinear(c0, c1, c2, color)

        color[0] = oetfSrgb(applyRemainder(color[0], lerp(srcRemainderR, dstRemainderR, amount)))
        color[1] = oetfSrgb(applyRemainder(color[1], lerp(srcRemainderG, dstRemainderG, amount)))
        color[2] = oetfSrgb(applyRemainder(color[2], lerp(srcRemainderB, dstRemainderB, amount)))

        return color
    }

    /**
     * Mixes 4 pigment concentrations to produce an sRGB color. The 4 pigments are the following:
     * - Phthalo Blue (Green Shade)
     * - Quinacridone Magenta
     * - Hansa Yellow
     * - Titanium White
     *
     * Each concentration must be a value between 0 and 1, and the sum of the concentrations
     * *must* be 1.
     *
     * The computed sRGB color uses the range 0 to 1 for each of the R, G, and B components.
     *
     * @param blue The concentration between 0 and 1 of Phthalo Blue to mix in.
     * @param magenta The concentration between 0 and 1 of Quinacridone Magenta to mix in.
     * @param yellow The concentration between 0 and 1 of Hansa Yellow to mix in.
     * @param white The concentration between 0 and 1 of Titanium White to mix in.
     * @param color An array of at least 3 floats that will store the computed sRGB color.
     * @return The [color] array if specified, otherwise a newly allocated array of 3 floats.
     */
    fun pigmentsMix(
        blue: Float,
        magenta: Float,
        yellow: Float,
        white: Float,
        color: FloatArray = FloatArray(3)
    ): FloatArray {
        requirePrecondition(abs(1.0f - (blue + magenta + yellow + white)) < 1e-6f) {
            "The sum of the pigment concentrations must be equal to 1.0"
        }
        requirePrecondition(color.size >= 3) { "color must have a size >= 3" }

        pigmentsMixLinear(blue, magenta, yellow, color)

        color[0] = oetfSrgb(color[0])
        color[1] = oetfSrgb(color[1])
        color[2] = oetfSrgb(color[2])

        return color
    }

    /**
     * This version of pigments mixing returns a _linear_ sRGB color.
     */
    internal fun pigmentsMixLinear(
        c0: Float,
        c1: Float,
        c2: Float,
        color: FloatArray
    ) {
        // Mixing pigments to a linear sRGB color is implemented using a single hidden
        // layer, 16 neurons, neural network with rational tinting-strength features
        // and C2 quartic smooth-ReLU activations (transition widths pre-folded into weights).
        // The loops have been unrolled and the code re-organized to minimize register
        // spilling and keep the instructions count low on arm64.
        val q0 = c0 / (c0 + 0.0398001f)
        val q1 = c1 / (c1 + 0.2109625f)
        val q2 = c2 / (c2 + 0.1513699f)

        var r =  0.0942057f + c0 * 0.1703877f + c1 * 0.2319316f - c2 * 0.1716009f +
            q0 * 0.0394091f - q1 * 0.0489831f + q2 * 0.1606205f
        var g =  0.3998840f - c0 * 0.6139438f - c1 * 0.0244454f - c2 * 0.0807311f -
            q0 * 0.1587674f - q1 * 0.0588993f + q2 * 0.2137057f
        var b =  0.0436599f + c0 * 0.1173228f + c1 * 0.0383668f - c2 * 0.0129965f +
            q0 * 0.2922518f + q1 * 0.3375300f - q2 * 0.6318032f

        val u0 = 0.7617934f - c0 * 2.0405555f + c1 * 0.3112379f - c2 * 0.8018587f +
            q0 * 2.3255994f - q1 * 0.4363129f + q2 * 0.0858946f
        val s0 = u0.fastCoerceIn(0.0f, 1.0f)
        val s0Sq = s0 * s0
        val h0 = (u0 - 1.0f).fastCoerceAtLeast(0.0f) + s0Sq * (s0 - 0.5f * s0Sq)
        r -= h0 * 0.0846060f
        g -= h0 * 0.0050965f
        b += h0 * 0.0108343f

        val u1 = -0.3669254f + c0 * 0.5272365f + c1 * 0.0513481f + c2 * 0.1808509f +
            q0 * 0.7708003f + q1 * 0.0586200f + q2 * 0.2390250f
        val s1 = u1.fastCoerceIn(0.0f, 1.0f)
        val s1Sq = s1 * s1
        val h1 = (u1 - 1.0f).fastCoerceAtLeast(0.0f) + s1Sq * (s1 - 0.5f * s1Sq)
        r -= h1 * 0.7024046f
        g -= h1 * 0.0563102f
        b += h1 * 0.0369187f

        val u2 = 1.3967189f - c0 * 0.9044641f - c1 * 0.0164963f - c2 * 0.0648650f -
            q0 * 0.4116434f - q1 * 0.2583996f - q2 * 0.1407897f
        val s2 = u2.fastCoerceIn(0.0f, 1.0f)
        val s2Sq = s2 * s2
        val h2 = (u2 - 1.0f).fastCoerceAtLeast(0.0f) + s2Sq * (s2 - 0.5f * s2Sq)
        r += h2 * 0.0530008f
        g += h2 * 0.2685580f
        b += h2 * 0.2063591f

        val u3 = 1.9257392f + c0 * 0.4095860f + c1 * 0.2479234f + c2 * 0.4018383f -
            q0 * 1.7728392f - q1 * 0.2889820f - q2 * 0.3477753f
        val s3 = u3.fastCoerceIn(0.0f, 1.0f)
        val s3Sq = s3 * s3
        val h3 = (u3 - 1.0f).fastCoerceAtLeast(0.0f) + s3Sq * (s3 - 0.5f * s3Sq)
        r += h3 * 0.2273123f
        g += h3 * 0.0223245f
        b -= h3 * 0.0063244f

        val u4 = 0.5804944f + c0 * 0.1197487f - c1 * 0.3406990f + c2 * 0.1528339f -
            q0 * 0.7473584f - q1 * 0.1581982f - q2 * 0.0572037f
        val s4 = u4.fastCoerceIn(0.0f, 1.0f)
        val s4Sq = s4 * s4
        val h4 = (u4 - 1.0f).fastCoerceAtLeast(0.0f) + s4Sq * (s4 - 0.5f * s4Sq)
        r += h4 * 0.9123240f
        g += h4 * 0.2034202f
        b -= h4 * 0.1264191f

        val u5 = -0.0325220f + c0 * 0.3890758f - c1 * 0.4171871f + c2 * 0.0243509f +
            q0 * 0.3468475f + q1 * 0.1799036f - q2 * 0.3129576f
        val s5 = u5.fastCoerceIn(0.0f, 1.0f)
        val s5Sq = s5 * s5
        val h5 = (u5 - 1.0f).fastCoerceAtLeast(0.0f) + s5Sq * (s5 - 0.5f * s5Sq)
        r -= h5 * 0.3006504f
        g -= h5 * 0.0019110f
        b -= h5 * 0.5820184f

        val u6 = 0.1023628f - c0 * 0.0347458f + c1 * 0.6043723f + c2 * 0.0326402f -
            q0 * 0.0679549f + q1 * 0.2249385f + q2 * 0.2909658f
        val s6 = u6.fastCoerceIn(0.0f, 1.0f)
        val s6Sq = s6 * s6
        val h6 = (u6 - 1.0f).fastCoerceAtLeast(0.0f) + s6Sq * (s6 - 0.5f * s6Sq)
        r -= h6 * 0.3818003f
        g -= h6 * 0.3901738f
        b -= h6 * 0.0220673f

        val u7 = 0.4741918f - c0 * 1.0170648f + c1 * 0.1637024f - c2 * 0.0065886f -
            q0 * 0.0713866f - q1 * 0.6906144f - q2 * 0.0780988f
        val s7 = u7.fastCoerceIn(0.0f, 1.0f)
        val s7Sq = s7 * s7
        val h7 = (u7 - 1.0f).fastCoerceAtLeast(0.0f) + s7Sq * (s7 - 0.5f * s7Sq)
        r += h7 * 0.7496945f
        g += h7 * 1.4022825f
        b += h7 * 0.1176288f

        val u8 = 0.6980488f + c0 * 0.4319992f - c1 * 0.0626281f - c2 * 0.6592367f +
            q0 * 0.3713518f - q1 * 0.1318354f - q2 * 0.3573421f
        val s8 = u8.fastCoerceIn(0.0f, 1.0f)
        val s8Sq = s8 * s8
        val h8 = (u8 - 1.0f).fastCoerceAtLeast(0.0f) + s8Sq * (s8 - 0.5f * s8Sq)
        r -= h8 * 0.3760870f
        g += h8 * 0.0907319f
        b += h8 * 0.0117416f

        val u9 = 0.0680660f + c0 * 0.0858009f + c1 * 0.2646287f + c2 * 0.2600586f +
            q0 * 0.0216613f + q1 * 0.3207402f - q2 * 0.4917397f
        val s9 = u9.fastCoerceIn(0.0f, 1.0f)
        val s9Sq = s9 * s9
        val h9 = (u9 - 1.0f).fastCoerceAtLeast(0.0f) + s9Sq * (s9 - 0.5f * s9Sq)
        r -= h9 * 0.0526490f
        g += h9 * 0.0008695f
        b -= h9 * 1.1715037f

        val u10 = 0.4834386f - c0 * 0.5719878f - c1 * 1.1263524f - c2 * 0.2889980f -
            q0 * 0.0203989f - q1 * 0.0477446f - q2 * 0.5009462f
        val s10 = u10.fastCoerceIn(0.0f, 1.0f)
        val s10Sq = s10 * s10
        val h10 = (u10 - 1.0f).fastCoerceAtLeast(0.0f) + s10Sq * (s10 - 0.5f * s10Sq)
        r -= h10 * 0.4328068f
        g += h10 * 0.2370650f
        b += h10 * 0.9636616f

        val u11 = 3.3638372f + c0 * 1.7292200f - c1 * 1.5350319f - c2 * 0.0993377f -
            q0 * 1.8210709f + q1 * 0.7025958f - q2 * 1.3914435f
        val s11 = u11.fastCoerceIn(0.0f, 1.0f)
        val s11Sq = s11 * s11
        val h11 = (u11 - 1.0f).fastCoerceAtLeast(0.0f) + s11Sq * (s11 - 0.5f * s11Sq)
        r += h11 * 0.1149270f
        g += h11 * 0.0251914f
        b += h11 * 0.0850134f

        val u12 = 0.8389716f - c0 * 0.4684559f - c1 * 0.2579706f + c2 * 0.0343448f -
            q0 * 0.6279241f + q1 * 0.7005695f - q2 * 0.0209734f
        val s12 = u12.fastCoerceIn(0.0f, 1.0f)
        val s12Sq = s12 * s12
        val h12 = (u12 - 1.0f).fastCoerceAtLeast(0.0f) + s12Sq * (s12 - 0.5f * s12Sq)
        r += h12 * 0.1540918f
        g -= h12 * 0.5343750f
        b += h12 * 0.1404618f

        val u13 = 1.1363839f + c0 * 1.8413706f + c1 * 1.1003418f - c2 * 0.2236535f -
            q0 * 1.8478841f - q1 * 0.3094627f + q2 * 1.6426561f
        val s13 = u13.fastCoerceIn(0.0f, 1.0f)
        val s13Sq = s13 * s13
        val h13 = (u13 - 1.0f).fastCoerceAtLeast(0.0f) + s13Sq * (s13 - 0.5f * s13Sq)
        r += h13 * 0.0273948f
        g -= h13 * 0.0074183f
        b -= h13 * 0.0093523f

        val u14 = -0.6866699f + c0 * 1.0747178f + c1 * 0.0307113f + c2 * 0.0335598f +
            q0 * 0.9366120f + q1 * 0.4623602f + q2 * 0.0181216f
        val s14 = u14.fastCoerceIn(0.0f, 1.0f)
        val s14Sq = s14 * s14
        val h14 = (u14 - 1.0f).fastCoerceAtLeast(0.0f) + s14Sq * (s14 - 0.5f * s14Sq)
        r += h14 * 0.0695353f
        g += h14 * 0.3202189f
        b -= h14 * 0.1638511f

        val u15 = 1.1973850f - c0 * 0.0920046f - c1 * 0.8114011f + c2 * 0.0703730f -
            q0 * 0.0336330f - q1 * 0.4128532f - q2 * 0.3697207f
        val s15 = u15.fastCoerceIn(0.0f, 1.0f)
        val s15Sq = s15 * s15
        val h15 = (u15 - 1.0f).fastCoerceAtLeast(0.0f) + s15Sq * (s15 - 0.5f * s15Sq)
        r += h15 * 0.0843518f
        g += h15 * 0.3336647f
        b += h15 * 0.5230504f

        color[0] = r.fastCoerceIn(0.0f, 1.0f)
        color[1] = g.fastCoerceIn(0.0f, 1.0f)
        color[2] = b.fastCoerceIn(0.0f, 1.0f)
    }
}
