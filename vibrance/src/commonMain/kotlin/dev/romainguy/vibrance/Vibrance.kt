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

        val buffer = pigmentsBuffer
        model.predict(
            r.fastCoerceIn(0.0f, 1.0f),
            g.fastCoerceIn(0.0f, 1.0f),
            b.fastCoerceIn(0.0f, 1.0f),
            buffer
        )

        val latent0 = buffer[0]
        val latent1 = buffer[1]
        val latent2 = buffer[2]
        val latent3 = 1.0f - (buffer[0] + buffer[1] + buffer[2])

        pigmentsMix(latent0, latent1, latent2, latent3, buffer)

        latentColor[0] = latent0
        latentColor[1] = latent1
        latentColor[2] = latent2
        latentColor[3] = r - buffer[0]
        latentColor[4] = g - buffer[1]
        latentColor[5] = b - buffer[2]

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

        pigmentsMix(blue, magenta, yellow, white, color)

        color[0] = (color[0] + latentColor[3]).fastCoerceIn(0.0f, 1.0f)
        color[1] = (color[1] + latentColor[4]).fastCoerceIn(0.0f, 1.0f)
        color[2] = (color[2] + latentColor[5]).fastCoerceIn(0.0f, 1.0f)

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
        pigmentsMix(c0, c1, c2, 1.0f - (c0 + c1 + c2), color)

        color[0] = (color[0] + lerp(src[3], dst[3], amount)).fastCoerceIn(0.0f, 1.0f)
        color[1] = (color[1] + lerp(src[4], dst[4], amount)).fastCoerceIn(0.0f, 1.0f)
        color[2] = (color[2] + lerp(src[5], dst[5], amount)).fastCoerceIn(0.0f, 1.0f)

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

        // Source
        model.predict(srcR, srcG, srcB, pigments)
        val srcPigment0 = pigments[0]
        val srcPigment1 = pigments[1]
        val srcPigment2 = pigments[2]
        val srcPigment3 = 1.0f - (srcPigment0 + srcPigment1 + srcPigment2)

        pigmentsMix(srcPigment0, srcPigment1, srcPigment2, srcPigment3, color)
        val srcRemainderR = srcR - color[0]
        val srcRemainderG = srcG - color[1]
        val srcRemainderB = srcB - color[2]

        // Destination
        model.predict(dstR, dstG, dstB, pigments)
        val dstPigment0 = pigments[0]
        val dstPigment1 = pigments[1]
        val dstPigment2 = pigments[2]
        val dstPigment3 = 1.0f - (dstPigment0 + dstPigment1 + dstPigment2)

        pigmentsMix(dstPigment0, dstPigment1, dstPigment2, dstPigment3, color)
        val dstRemainderR = dstR - color[0]
        val dstRemainderG = dstG - color[1]
        val dstRemainderB = dstB - color[2]

        val c0 = lerp(srcPigment0, dstPigment0, amount)
        val c1 = lerp(srcPigment1, dstPigment1, amount)
        val c2 = lerp(srcPigment2, dstPigment2, amount)
        pigmentsMix(c0, c1, c2, 1.0f - (c0 + c1 + c2), color)

        color[0] = (color[0] + lerp(srcRemainderR, dstRemainderR, amount)).fastCoerceIn(0.0f, 1.0f)
        color[1] = (color[1] + lerp(srcRemainderG, dstRemainderG, amount)).fastCoerceIn(0.0f, 1.0f)
        color[2] = (color[2] + lerp(srcRemainderB, dstRemainderB, amount)).fastCoerceIn(0.0f, 1.0f)

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

        // This is a 3-degree polynomial fit of the original Kubelka-Munk pigments mixing implementation.
        // The list of coefficients was generated using the script fit.py, and the resulting polynomial
        // was optimized to avoid register spilling once compiled down to arm64 on Android.
        // The register spilling optimization takes this function from 542 instructions down to 450.
        // Once optimized, this function runs in 33ns on a Google Pixel 6 running Android 16.
        val c0 = blue
        val c1 = magenta
        val c2 = yellow
        val c3 = white

        val rBase = -0.0764922f + c0 * -0.9848551f + c1 *  0.2688409f + c2 *  0.2505113f + c3 * 0.4655028f
        val gBase =  0.5588972f + c0 * -0.0246556f + c1 * -0.2149197f + c2 *  0.0963543f + c3 * 0.1432210f
        val bBase =  0.4739987f + c0 *  0.1752986f + c1 *  0.2137695f + c2 * -0.6037832f + c3 * 0.2147151f

        val c00 = c0 * c0
        val c11 = c1 * c1
        val c22 = c2 * c2
        val c33 = c3 * c3

        val r00 = c00 * (3.6627880f + c0 * -2.7887055f + c1 *  2.1974499f + c2 *  2.1899440f + c3 * 2.0640995f)
        val g00 = c00 * (0.1065450f + c0 * -0.0996047f + c1 * -0.3440396f + c2 *  0.2878509f + c3 * 0.2623384f)
        val b00 = c00 * (0.1363397f + c0 *  0.0994831f + c1 *  0.0284383f + c2 * -0.2724627f + c3 * 0.2808811f)

        val r11 = c11 * (0.6266825f + c0 * -0.2382657f + c1 * -0.0775305f + c2 *  0.7247073f + c3 * 0.2177714f)
        val g11 = c11 * (0.1530382f + c0 * -0.1400642f + c1 * -0.2052836f + c2 *  0.1801682f + c3 * 0.3182179f)
        val b11 = c11 * (0.0146905f + c0 *  0.0770428f + c1 *  0.0766514f + c2 * -0.2476222f + c3 * 0.1086185f)

        val r22 = c22 * (0.6661905f + c0 * -0.4351550f + c1 *  0.6401300f + c2 * -0.0014626f + c3 * 0.4626781f)
        val g22 = c22 * (0.0685675f + c0 * -0.3029612f + c1 * -0.2314235f + c2 *  0.1992108f + c3 * 0.4037414f)
        val b22 = c22 * (0.7820943f + c0 *  0.4039833f + c1 *  0.2873597f + c2 * -0.5889300f + c3 * 0.6796814f)

        val r33 = c33 * (0.3811558f + c0 * -0.5224034f + c1 *  0.5272038f + c2 *  0.3447338f + c3 * 0.0316216f)
        val g33 = c33 * (0.0605810f + c0 * -0.2416826f + c1 * -0.3395025f + c2 *  0.4516886f + c3 * 0.1900775f)
        val b33 = c33 * (0.1411155f + c0 *  0.2557500f + c1 *  0.1462857f + c2 * -0.3824981f + c3 * 0.1215779f)

        val c12 = c1 * c2
        val c13 = c1 * c3
        val c23 = c2 * c3
        val c01 = c0 * c1
        val c02 = c0 * c2
        val c03 = c0 * c3

        val rCross =
            c12 * ( 0.5938269f + c0 * -2.1088993f) +
            c13 * ( 0.6404553f + c0 * -1.4424087f) +
            c23 * ( 0.7449525f + c0 * -1.4003482f + c1 *  1.3378888f) +
            c01 * -1.5921237f + c02 * -1.7544585f + c03 * -1.3010608f

        val gCross =
            c12 * (-0.1713186f + c0 *  0.2198481f) +
            c13 * (-0.1467898f + c0 *  0.2144062f) +
            c23 * ( 0.2549431f + c0 * -0.2605754f + c1 * -0.3399114f) +
            c01 * -0.0498495f + c02 * -0.0558377f + c03 * -0.0255134f

        val bCross =
            c12 * (-0.3537513f + c0 * -0.1266331f) +
            c13 * ( 0.2810154f + c0 *  0.2929669f) +
            c23 * (-0.5033431f + c0 * -0.5336707f + c1 * -0.2668557f) +
            c01 * 0.2718149f + c02 * -0.5287832f + c03 * 0.2959273f

        color[0] = (rBase + r00 + r11 + r22 + r33 + rCross).fastCoerceIn(0.0f, 1.0f)
        color[1] = (gBase + g00 + g11 + g22 + g33 + gCross).fastCoerceIn(0.0f, 1.0f)
        color[2] = (bBase + b00 + b11 + b22 + b33 + bCross).fastCoerceIn(0.0f, 1.0f)

        return color
    }
}
