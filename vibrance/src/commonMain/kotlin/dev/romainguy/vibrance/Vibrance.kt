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
 * | API                  | Time  |
 * |----------------------|-------|
 * | [colorToLatentColor] | 3.1µs |
 * | [latentColorToColor] | 40ns  |
 * | [latentColorsMix]    | 44ns  |
 * | [colorsMix]          | 6.3µs |
 * | [pigmentsMix]        | 34ns  |
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
    internal val model = PigmentsModel()
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
        val latent3 = 1.0f - (buffer[0] + buffer[1] + buffer[2])

        pigmentsMixLinear(latent0, latent1, latent2, latent3, buffer)

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
        val white = 1.0f - (blue + magenta + yellow)

        pigmentsMixLinear(blue, magenta, yellow, white, color)

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
        pigmentsMixLinear(c0, c1, c2, 1.0f - (c0 + c1 + c2), color)

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
     * On a Google Pixel 6, using this method directly takes 6.3µs, while [latentColorsMix] only
     * takes 42ns (150x faster).
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

        val pigments = pigmentsBuffer

        val srcR = srcR.fastCoerceIn(0.0f, 1.0f)
        val srcG = srcG.fastCoerceIn(0.0f, 1.0f)
        val srcB = srcB.fastCoerceIn(0.0f, 1.0f)

        // Source
        model.predict(srcR, srcG, srcB, pigments)
        val srcPigment0 = pigments[0]
        val srcPigment1 = pigments[1]
        val srcPigment2 = pigments[2]
        val srcPigment3 = 1.0f - (srcPigment0 + srcPigment1 + srcPigment2)

        pigmentsMixLinear(srcPigment0, srcPigment1, srcPigment2, srcPigment3, color)
        val srcRemainderR = eotfSrgb(srcR) - color[0]
        val srcRemainderG = eotfSrgb(srcG) - color[1]
        val srcRemainderB = eotfSrgb(srcB) - color[2]

        // Destination
        val dstR = dstR.fastCoerceIn(0.0f, 1.0f)
        val dstG = dstG.fastCoerceIn(0.0f, 1.0f)
        val dstB = dstB.fastCoerceIn(0.0f, 1.0f)

        model.predict(dstR, dstG, dstB, pigments)
        val dstPigment0 = pigments[0]
        val dstPigment1 = pigments[1]
        val dstPigment2 = pigments[2]
        val dstPigment3 = 1.0f - (dstPigment0 + dstPigment1 + dstPigment2)

        pigmentsMixLinear(dstPigment0, dstPigment1, dstPigment2, dstPigment3, color)
        val dstRemainderR = eotfSrgb(dstR) - color[0]
        val dstRemainderG = eotfSrgb(dstG) - color[1]
        val dstRemainderB = eotfSrgb(dstB) - color[2]

        val c0 = lerp(srcPigment0, dstPigment0, amount)
        val c1 = lerp(srcPigment1, dstPigment1, amount)
        val c2 = lerp(srcPigment2, dstPigment2, amount)
        pigmentsMixLinear(c0, c1, c2, 1.0f - (c0 + c1 + c2), color)

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
     * *must* be 1. This function does not validate the inputs and the resulting color is
     * undefined if these conditions are not met.
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

        pigmentsMixLinear(blue, magenta, yellow, white, color)

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
        c3: Float,
        color: FloatArray
    ) {
        // This is a 2-degree polynomial fit of the original Kubelka-Munk pigments mixing implementation.
        // The list of coefficients was generated using the script fit.py, and the resulting polynomial
        // was optimized to avoid register spilling once compiled down to arm64 on Android.
        val r = -0.0127424f +
            c0 * (-0.7214596f + 1.2910803f * c0 - 0.5246562f * c1 - 0.6980598f * c2 - 0.7541017f * c3) +
            c1 * ( 0.2552209f + 0.1244411f * c1 + 0.2461797f * c2 + 0.4003199f * c3) +
            c2 * ( 0.2407041f + 0.1598632f * c2 + 0.4757960f * c3) +
            c3 * ( 0.2831582f + 0.1619617f * c3)

        val g = 0.2139478f +
            c0 * (-0.0582835f + 0.1025588f * c0 + 0.2028784f * c1 - 0.2009089f * c2 - 0.1457360f * c3) +
            c1 * (-0.2934236f + 0.2478245f * c1 - 0.3797242f * c2 - 0.3576353f * c3) +
            c2 * ( 0.1363333f + 0.2446950f * c2 + 0.4371069f * c3) +
            c3 * ( 0.2306976f + 0.2836096f * c3)

        val b = 0.2362212f +
            c0 * ( 0.2472500f + 0.1354136f * c0 + 0.1677672f * c1 - 0.5183831f * c2 + 0.4517108f * c3) +
            c1 * ( 0.2237250f + 0.0387428f * c1 - 0.3382904f * c2 + 0.3212758f * c3) +
            c2 * (-0.7321315f + 0.8194536f * c2 - 0.7598101f * c3) +
            c3 * ( 0.2784333f + 0.2584891f * c3)

        color[0] = r.fastCoerceIn(0.0f, 1.0f)
        color[1] = g.fastCoerceIn(0.0f, 1.0f)
        color[2] = b.fastCoerceIn(0.0f, 1.0f)
    }
}
