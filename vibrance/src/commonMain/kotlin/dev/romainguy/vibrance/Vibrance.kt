@file:Suppress("DuplicatedCode", "UnnecessaryVariable", "FloatingPointLiteralPrecision")

package dev.romainguy.vibrance

import kotlin.math.abs
import kotlin.math.max

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
        latentColor[3] = eotfSrgb(r) - buffer[0]
        latentColor[4] = eotfSrgb(g) - buffer[1]
        latentColor[5] = eotfSrgb(b) - buffer[2]

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

        color[0] = oetfSrgb((color[0] + latentColor[3]).fastCoerceIn(0.0f, 1.0f))
        color[1] = oetfSrgb((color[1] + latentColor[4]).fastCoerceIn(0.0f, 1.0f))
        color[2] = oetfSrgb((color[2] + latentColor[5]).fastCoerceIn(0.0f, 1.0f))

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

        color[0] = oetfSrgb((color[0] + lerp(src[3], dst[3], amount)).fastCoerceIn(0.0f, 1.0f))
        color[1] = oetfSrgb((color[1] + lerp(src[4], dst[4], amount)).fastCoerceIn(0.0f, 1.0f))
        color[2] = oetfSrgb((color[2] + lerp(src[5], dst[5], amount)).fastCoerceIn(0.0f, 1.0f))

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
        val srcRemainderR = eotfSrgb(srcR) - color[0]
        val srcRemainderG = eotfSrgb(srcG) - color[1]
        val srcRemainderB = eotfSrgb(srcB) - color[2]

        // Destination
        val dstR = dstR.fastCoerceIn(0.0f, 1.0f)
        val dstG = dstG.fastCoerceIn(0.0f, 1.0f)
        val dstB = dstB.fastCoerceIn(0.0f, 1.0f)

        model.predict(dstR, dstG, dstB, color)
        val dstPigment0 = color[0]
        val dstPigment1 = color[1]
        val dstPigment2 = color[2]

        pigmentsMixLinear(dstPigment0, dstPigment1, dstPigment2, color)
        val dstRemainderR = eotfSrgb(dstR) - color[0]
        val dstRemainderG = eotfSrgb(dstG) - color[1]
        val dstRemainderB = eotfSrgb(dstB) - color[2]

        val c0 = lerp(srcPigment0, dstPigment0, amount)
        val c1 = lerp(srcPigment1, dstPigment1, amount)
        val c2 = lerp(srcPigment2, dstPigment2, amount)
        pigmentsMixLinear(c0, c1, c2, color)

        color[0] = oetfSrgb((color[0] + lerp(srcRemainderR, dstRemainderR, amount)).fastCoerceIn(0.0f, 1.0f))
        color[1] = oetfSrgb((color[1] + lerp(srcRemainderG, dstRemainderG, amount)).fastCoerceIn(0.0f, 1.0f))
        color[2] = oetfSrgb((color[2] + lerp(srcRemainderB, dstRemainderB, amount)).fastCoerceIn(0.0f, 1.0f))

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
        // layer, 16 neurons, neural network. The loops have been unrolled and the code
        // re-organized to minimize register spilling and keep the instructions count
        // low on arm64.
        var r =  0.1539308f - c0 * 1.5850313f + c1 * 0.0049084f - c2 * 0.1980831f
        var g = -0.2101102f - c0 * 0.4561743f - c1 * 0.1060945f + c2 * 0.0193492f
        var b =  0.1130296f - c0 * 0.2899411f + c1 * 0.0961012f - c2 * 2.8117707f

        val h0 = max(0f, -0.5732316f + c0 * 2.4183984f + c1 * 0.1553283f + c2 * 0.0646514f)
        r -= h0 * 0.4831170f
        g -= h0 * 0.1122841f
        b -= h0 * 0.0794509f

        val h1 = max(0f, 0.5309075f - c0 * 6.0326037f - c1 * 0.0136373f + c2 * 0.0213502f)
        r += h1 * 0.4891092f
        g -= h1 * 0.0002045f
        b -= h1 * 0.0159511f

        val h2 = max(0f, -0.1674344f + c0 * 0.1293259f + c1 * 0.3301166f + c2 * 2.7173810f)
        r -= h2 * 0.1710407f
        g += h2 * 0.1534036f
        b += h2 * 0.8488734f

        val h3 = max(0f, 0.0287062f + c0 * 2.4400022f + c1 * 0.0027635f - c2 * 0.1977382f)
        r -= h3 * 0.5497317f
        g += h3 * 0.0361791f
        b += h3 * 0.1983951f

        val h4 = max(0f, 0.4402751f - c0 * 3.0921938f - c1 * 4.6753030f + c2 * 0.0586780f)
        r += h4 * 0.1203473f
        g += h4 * 0.4039454f
        b -= h4 * 0.0212024f

        val h5 = max(0f, 0.0922123f - c0 * 8.8464346f - c1 * 0.1681033f + c2 * 0.1123352f)
        r += h5 * 1.0090508f
        g += h5 * 0.1335829f
        b -= h5 * 0.0686533f

        val h6 = max(0f, -0.6625637f + c0 * 0.1312097f + c1 * 0.1675960f + c2 * 1.9033443f)
        r -= h6 * 0.0016896f
        g += h6 * 0.0071924f
        b += h6 * 0.4053358f

        val h7 = max(0f, 0.4586772f - c0 * 0.0998359f - c1 * 0.2719077f - c2 * 3.2554240f)
        r -= h7 * 0.0355527f
        g += h7 * 0.0159359f
        b += h7 * 0.5412980f

        val h8 = max(0f, 1.4274933f - c0 * 1.3719325f - c1 * 1.0621926f - c2 * 1.4547547f)
        r += h8 * 0.1521681f
        g += h8 * 0.3157202f
        b += h8 * 0.4113466f

        val h9 = max(0f, 0.6399117f - c0 * 1.0160148f - c1 * 2.0311494f - c2 * 0.0227793f)
        r -= h9 * 0.0102088f
        g += h9 * 0.3475253f
        b -= h9 * 0.0262109f

        val h10 = max(0f, 0.8562082f + c0 * 3.4214549f - c1 * 0.8499470f - c2 * 0.8562099f)
        r -= h10 * 0.5840385f
        g += h10 * 0.2226422f
        b -= h10 * 0.0267674f

        val h11 = max(0f, 0.9718049f - c0 * 4.0978346f - c1 * 0.2651648f - c2 * 0.1055439f)
        r += h11 * 0.5092035f
        g += h11 * 0.0837957f
        b += h11 * 0.0589227f

        val h12 = max(0f, -0.1236852f + c0 * 2.4840240f - c1 * 0.0313387f + c2 * 0.6974269f)
        r += h12 * 0.3274774f
        g += h12 * 0.0674772f
        b += h12 * 0.0903832f

        val h13 = max(0f, 0.2737229f - c0 * 7.6579404f - c1 * 0.0605621f + c2 * 0.0917635f)
        r += h13 * 0.8107759f
        g += h13 * 0.0513009f
        b -= h13 * 0.0689548f

        val h14 = max(0f, -0.5286661f + c0 * 1.7521223f + c1 * 2.2740808f + c2 * 0.6740580f)
        r -= h14 * 0.0910447f
        g += h14 * 0.0987217f
        b -= h14 * 0.0352297f

        val h15 = max(0f, -0.0743285f + c0 * 4.1528034f - c1 * 0.0005895f + c2 * 0.0491316f)
        r += h15 * 1.3558975f
        g -= h15 * 0.0600876f
        b += h15 * 0.0716275f

        color[0] = r.fastCoerceIn(0.0f, 1.0f)
        color[1] = g.fastCoerceIn(0.0f, 1.0f)
        color[2] = b.fastCoerceIn(0.0f, 1.0f)
    }
}
