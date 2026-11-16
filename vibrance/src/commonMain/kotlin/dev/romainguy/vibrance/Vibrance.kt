@file:Suppress("DuplicatedCode", "UnnecessaryVariable", "FloatingPointLiteralPrecision")

package dev.romainguy.vibrance

import kotlin.math.abs
import kotlin.math.sqrt

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
        // layer, 16 neurons, neural network with rational tinting-strength features
        // and smooth Squareplus activations (0.5 factor pre-folded into output weights).
        // The loops have been unrolled and the code re-organized to minimize register
        // spilling and keep the instructions count low on arm64.
        val q0 = c0 / (c0 + 0.0310625f)
        val q1 = c1 / (c1 + 0.2191592f)
        val q2 = c2 / (c2 + 0.2235622f)

        var r =  0.1138980f + c0 * 0.2006903f + c1 * 0.2850100f - c2 * 0.0223676f +
            q0 * 0.0238455f + q1 * 0.0664495f + q2 * 0.2763469f
        var g =  0.4692093f - c0 * 0.7184518f - c1 * 0.2503175f - c2 * 0.0802540f -
            q0 * 0.1460137f - q1 * 0.1413032f + q2 * 0.2349665f
        var b = -0.0526232f - c0 * 0.0812431f - c1 * 0.0933264f - c2 * 0.0236277f +
            q0 * 0.1413919f + q1 * 0.2800236f - q2 * 0.4376401f

        val z0 = 0.1252622f - c0 * 0.5201054f - c1 * 0.0911538f + c2 * 0.0355596f +
            q0 * 0.7600455f - q1 * 0.1648567f - q2 * 0.3392197f
        val h0 = z0 + sqrt(z0 * z0 + 0.0436730f)
        r -= h0 * 0.2077531f
        g += h0 * 0.0062347f
        b += h0 * 0.0287711f

        val z1 = -0.7411862f + c0 * 0.0598556f + c1 * 0.1075494f + c2 * 0.2917133f +
            q0 * 0.9456514f + q1 * 0.0729930f - q2 * 0.0130125f
        val h1 = z1 + sqrt(z1 * z1 + 0.0590666f)
        r -= h1 * 0.2129128f
        g -= h1 * 0.0746477f
        b -= h1 * 0.0058008f

        val z2 = 0.9973216f + c0 * 0.0789433f + c1 * 0.1078733f - c2 * 0.2276448f -
            q0 * 0.2921924f - q1 * 0.1357998f - q2 * 0.9091156f
        val h2 = z2 + sqrt(z2 * z2 + 0.0331533f)
        r += h2 * 0.0314042f
        g += h2 * 0.1260467f
        b += h2 * 0.0422853f

        val z3 = 0.6439704f - c0 * 0.6540558f + c1 * 0.0730592f + c2 * 0.0715327f -
            q0 * 0.7354834f - q1 * 0.1441095f - q2 * 0.0072820f
        val h3 = z3 + sqrt(z3 * z3 + 0.0334003f)
        r += h3 * 0.2236924f
        g += h3 * 0.1058263f
        b -= h3 * 0.0406763f

        val z4 = 0.1893468f - c0 * 0.5930237f - c1 * 0.3712729f + c2 * 0.1466069f -
            q0 * 0.6289639f - q1 * 0.3043411f + q2 * 0.0276856f
        val h4 = z4 + sqrt(z4 * z4 + 0.1507604f)
        r += h4 * 0.2617889f
        g += h4 * 0.0970473f
        b -= h4 * 0.0667461f

        val z5 = -0.2695661f - c0 * 0.1543189f - c1 * 0.1793479f - c2 * 0.7816104f +
            q0 * 0.0079201f + q1 * 0.5748301f + q2 * 0.0511160f
        val h5 = z5 + sqrt(z5 * z5 + 0.0313477f)
        r -= h5 * 0.0675984f
        g -= h5 * 0.0622383f
        b += h5 * 0.5946171f

        val z6 = -0.1413133f - c0 * 0.3384386f + c1 * 0.6572116f + c2 * 0.6559677f -
            q0 * 0.1352028f + q1 * 0.0270607f + q2 * 0.0650126f
        val h6 = z6 + sqrt(z6 * z6 + 0.0809649f)
        r -= h6 * 0.1797305f
        g -= h6 * 0.0379612f
        b -= h6 * 0.0192549f

        val z7 = 0.0664226f - c0 * 0.8321691f - c1 * 0.1382556f + c2 * 0.0467029f -
            q0 * 0.0279708f - q1 * 0.5254193f - q2 * 0.0967015f
        val h7 = z7 + sqrt(z7 * z7 + 0.0459607f)
        r += h7 * 0.3974901f
        g += h7 * 0.6498399f
        b -= h7 * 0.0226307f

        val z8 = -0.0930780f + c0 * 0.6784285f + c1 * 0.1864256f - c2 * 0.1777748f -
            q0 * 0.1015761f + q1 * 0.2995041f + q2 * 0.6110286f
        val h8 = z8 + sqrt(z8 * z8 + 0.0200648f)
        r -= h8 * 0.1684434f
        g += h8 * 0.0378935f
        b += h8 * 0.0907279f

        val z9 = -0.5700094f - c0 * 0.5162911f - c1 * 0.4263113f + c2 * 0.2584335f +
            q0 * 0.4651760f + q1 * 0.4406392f - q2 * 0.2726952f
        val h9 = z9 + sqrt(z9 * z9 + 0.0358842f)
        r += h9 * 0.1909737f
        g += h9 * 0.0818795f
        b += h9 * 0.1061134f

        val z10 = 0.0675779f - c0 * 0.1526181f - c1 * 0.6554778f - c2 * 0.6075161f -
            q0 * 0.0318142f + q1 * 0.0631102f - q2 * 0.4159156f
        val h10 = z10 + sqrt(z10 * z10 + 0.0400651f)
        r -= h10 * 0.0182861f
        g += h10 * 0.0797339f
        b += h10 * 1.1742090f

        val z11 = 0.7879340f + c0 * 0.3684239f - c1 * 0.3804242f + c2 * 0.2696600f -
            q0 * 0.6801093f + q1 * 0.2272401f - q2 * 0.3641951f
        val h11 = z11 + sqrt(z11 * z11 + 0.0435383f)
        r += h11 * 0.1932286f
        g += h11 * 0.0285034f
        b += h11 * 0.1023031f

        val z12 = 0.3761955f - c0 * 0.2408114f - c1 * 0.1430061f - c2 * 0.0045201f -
            q0 * 0.6616894f + q1 * 0.6942975f + q2 * 0.0407043f
        val h12 = z12 + sqrt(z12 * z12 + 0.0724127f)
        r += h12 * 0.0548208f
        g -= h12 * 0.2967174f
        b += h12 * 0.0503791f

        val z13 = 0.1706484f - c0 * 0.5852262f - c1 * 0.4396836f - c2 * 0.5350166f -
            q0 * 0.2896418f + q1 * 0.2779544f + q2 * 0.1295947f
        val h13 = z13 + sqrt(z13 * z13 + 0.0505045f)
        r += h13 * 0.0398552f
        g -= h13 * 0.0631965f
        b -= h13 * 0.0983731f

        val z14 = -0.6701704f + c0 * 0.8382769f + c1 * 0.1520069f - c2 * 0.0277287f +
            q0 * 0.3990448f + q1 * 0.3300152f + q2 * 0.0725957f
        val h14 = z14 + sqrt(z14 * z14 + 0.0893828f)
        r -= h14 * 0.1088641f
        g += h14 * 0.2620537f
        b -= h14 * 0.0669322f

        val z15 = 0.8076255f - c0 * 0.1050001f - c1 * 0.5659755f + c2 * 0.1408194f -
            q0 * 0.0762831f - q1 * 0.5195585f - q2 * 0.6107836f
        val h15 = z15 + sqrt(z15 * z15 + 0.1055795f)
        r -= h15 * 0.0088492f
        g += h15 * 0.0342153f
        b += h15 * 0.2892738f

        color[0] = r.fastCoerceIn(0.0f, 1.0f)
        color[1] = g.fastCoerceIn(0.0f, 1.0f)
        color[2] = b.fastCoerceIn(0.0f, 1.0f)
    }
}
