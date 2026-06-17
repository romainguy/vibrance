package dev.romainguy.vibrance.compose

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.colorspace.ColorSpaces
import dev.romainguy.vibrance.Vibrance

internal val ColorBuffer = FloatArray(3)

/**
 * Converts a color to a latent color. A latent color is a representation of a
 * color that uses a series of paint pigments concentrations and an RGB remainder.
 * Latent colors are represented as arrays of 6 floats. All the values in the arrays
 * are between 0 and 1.
 *
 * @param color The color to convert.
 * @param latentColor An array of at least 6 floats that will store the resulting latent color.
 * @return The [latentColor] array if specified, otherwise a newly allocated array of 6 floats.
 */
fun Vibrance.colorToLatentColor(color: Color, latentColor: FloatArray = FloatArray(6)): FloatArray {
    val (r, g, b) = color.convert(ColorSpaces.Srgb)
    return colorToLatentColor(r, g, b, latentColor)
}

/**
 * Converts a latent color to an sRGB color. A latent color is a representation of a
 * color that uses a series of paint pigments concentrations and an RGB remainder.
 * Latent colors are represented as arrays of 6 floats and can be computed using
 * [colorToLatentColor]. All the values in the arrays must be between 0 and 1. The
 * sum of the first 3 elements of [latentColor] must be <= 1.0.
 *
 * @return The sRGB color converted from the specified latent color.
 */
fun Vibrance.latentColorToColor(latentColor: FloatArray): Color =
    latentColorToColor(latentColor, ColorBuffer).run {
        Color(this[0], this[1], this[2])
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
 * @return The sRGB color resulting from the interpolation of [src] and [dst].
 */
fun Vibrance.latentColorsMix(src: FloatArray, dst: FloatArray, amount: Float): Color =
    latentColorsMix(src, dst, amount, ColorBuffer).run {
        Color(this[0], this[1], this[2])
    }

/**
 * Mixes two sRGB colors as concentrations of paint pigments. This method exists for convenience
 * but requires to upscale the input sRGB colors to a series of pigment concentrations every
 * time it is invoked. If the same input colors will be mixed multiple times using a different
 * mix amount, it is recommended to instead precompute the latent colors for each input using
 * [colorToLatentColor], and mixing them using [latentColorsMix].
 *
 * @param src The source color.
 * @param dst The destination color.
 * @param amount The mix (or interpolation) amount between the source and destination
 *     colors, between 0 and 1.
 * @return The sRGB color resulting from the interpolation of [src] and [dst].
 */
fun Vibrance.colorsMix(src: Color, dst: Color, amount: Float): Color {
    val (sr, sg, sb) = src.convert(ColorSpaces.Srgb)
    val (dr, dg, db) = dst.convert(ColorSpaces.Srgb)
    return colorsMix(sr, sg, sb, dr, dg, db, amount, ColorBuffer).run {
        Color(this[0], this[1], this[2])
    }
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
 * @param blue The concentration between 0 and 1 of Phthalo Blue to mix in.
 * @param magenta The concentration between 0 and 1 of Quinacridone Magenta to mix in.
 * @param yellow The concentration between 0 and 1 of Hansa Yellow to mix in.
 * @param white The concentration between 0 and 1 of Titanium White to mix in.
 * @return The sRGB color computed from the specified pigment mix.
 */
fun Vibrance.pigmentsMix(blue: Float, magenta: Float, yellow: Float, white: Float): Color =
    pigmentsMix(blue, magenta, yellow, white, ColorBuffer).run {
        Color(this[0], this[1], this[2])
    }
