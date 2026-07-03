@file:Suppress("FunctionName")

package dev.romainguy.vibrance.compose

import androidx.annotation.RequiresApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.geometry.center
import androidx.compose.ui.geometry.isFinite
import androidx.compose.ui.geometry.isSpecified
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shader
import androidx.compose.ui.graphics.ShaderBrush
import androidx.compose.ui.graphics.TileMode
import androidx.compose.ui.util.fastIsFinite
import kotlin.math.abs

/**
 * Create a vertical gradient brush covering the entire size of the target element.
 * The gradient goes from [startColor] at the top, to [endColor] at the bottom.
 * @param startColor The color to interpolate from.
 * @param endColor The color to interpolate to.
 */
@RequiresApi(33)
fun Brush.Companion.verticalPigmentsGradient(startColor: Color, endColor: Color): Brush =
    LinearPigmentsGradientBrush(
        startColor,
        endColor,
        Offset.Zero,
        Offset(0.0f, Float.POSITIVE_INFINITY),
        TileMode.Clamp
    )

/**
 * Create a vertical gradient from the starting position [startY] to the ending
 * position [endY].
 *
 * @param startColor The color to interpolate from.
 * @param endColor The color to interpolate to.
 * @param startY The starting vertical position of the gradient.
 * @param endY The ending vertical position of the gradient. Use
 *     [Float.POSITIVE_INFINITY] to indicate the bottom of the drawing area.
 * @param tileMode Determines the behavior for how the gradient behaves outside
 *     its start and end positions. Defaults to `TileMode.Clamp` to repeat the
 *     edge pixels.
 */
@RequiresApi(33)
fun Brush.Companion.verticalPigmentsGradient(
    startColor: Color,
    endColor: Color,
    startY: Float = 0.0f,
    endY: Float = Float.POSITIVE_INFINITY,
    tileMode: TileMode = TileMode.Clamp
): Brush =
    LinearPigmentsGradientBrush(
        startColor,
        endColor,
        Offset(0.0f, startY),
        Offset(0.0f, endY),
        tileMode
    )

/**
 * Create a horizontal gradient brush covering the entire size of the target element.
 * The gradient goes from [startColor] at the top, to [endColor] at the bottom.
 * @param startColor The color to interpolate from.
 * @param endColor The color to interpolate to.
 */
@RequiresApi(33)
fun Brush.Companion.horizontalPigmentsGradient(startColor: Color, endColor: Color): Brush =
    LinearPigmentsGradientBrush(
        startColor,
        endColor,
        Offset.Zero,
        Offset(Float.POSITIVE_INFINITY, 0.0f),
        TileMode.Clamp
    )

/**
 * Create a horizontal gradient from the starting position [startX] to the ending
 * position [endX].
 *
 * @param startColor The color to interpolate from.
 * @param endColor The color to interpolate to.
 * @param startX The starting horizontal position of the gradient.
 * @param endX The ending horizontal position of the gradient. Use
 *     [Float.POSITIVE_INFINITY] to indicate the bottom of the drawing area.
 * @param tileMode Determines the behavior for how the gradient behaves outside
 *     its start and end positions. Defaults to `TileMode.Clamp` to repeat the
 *     edge pixels.
 */
@RequiresApi(33)
fun Brush.Companion.horizontalPigmentsGradient(
    startColor: Color,
    endColor: Color,
    startX: Float = 0.0f,
    endX: Float = Float.POSITIVE_INFINITY,
    tileMode: TileMode = TileMode.Clamp
): Brush =
    LinearPigmentsGradientBrush(
        startColor,
        endColor,
        Offset(startX, 0.0f),
        Offset(endX, 0.0f),
        tileMode
    )

/**
 * Create a gradient brush from the specified start position ([startOffset]), to
 * the specified end position ([endOffset]). Use [Float.POSITIVE_INFINITY] for
 * x and y to indicate the far right and far bottom of the drawing area
 * respectively.
 *
 * @param startOffset The starting point of the gradient.
 * @param startColor The color to interpolate from.
 * @param endOffset The ending point of the gradient.
 * @param endColor The color to interpolate to.
 * @param tileMode Determines the behavior for how the gradient behaves outside
 *     its start and end positions. Defaults to `TileMode.Clamp` to repeat the
 *     edge pixels.
 */
@RequiresApi(33)
fun Brush.Companion.linearPigmentsGradient(
    startColor: Color,
    endColor: Color,
    startOffset: Offset,
    endOffset: Offset,
    tileMode: TileMode = TileMode.Clamp
): Brush =
    LinearPigmentsGradientBrush(
        startColor,
        endColor,
        startOffset,
        endOffset,
        tileMode
    )

/**
 * Create a circular gradient brush centered around the specified position
 * ([centerOffset]), with the specified [radius]. If [Offset.Unspecified] is used,
 * the center of the circular gradient will be the center of the drawing area.
 * You can also use [Float.POSITIVE_INFINITY] for x and y to indicate the far right
 * and far bottom of the drawing area respectively. [radius] can be set to
 * [Float.POSITIVE_INFINITY] to use a radius that fits within the drawing area.
 *
 * @param startColor The color to interpolate from.
 * @param endColor The color to interpolate to.
 * @param centerOffset The focal point of the circular gradient.
 * @param radius Radius, in pixels, of the circular gradient.
 * @param tileMode Determines the behavior for how the gradient behaves outside
 *     its start and end positions. Defaults to `TileMode.Clamp` to repeat the
 *     edge pixels.
 */
@RequiresApi(33)
fun Brush.Companion.radialPigmentsGradient(
    startColor: Color,
    endColor: Color,
    centerOffset: Offset = Offset.Unspecified,
    radius: Float = Float.POSITIVE_INFINITY,
    tileMode: TileMode = TileMode.Clamp
): Brush =
    RadialPigmentsGradientBrush(
        startColor,
        endColor,
        centerOffset,
        radius,
        tileMode
    )

/**
 * Create a sweep gradient brush centered around the specified position
 * ([centerOffset]). If [Offset.Unspecified] is used, the center of  the sweep
 * gradient will be the center of the drawing area. You can also use
 * [Float.POSITIVE_INFINITY] for x and y to indicate the far right and far bottom
 * of the drawing area respectively.
 *
 * @param startColor The color to interpolate from.
 * @param endColor The color to interpolate to.
 * @param centerOffset The focal point of the gradient sweep.
 * @param angle The starting angle in degrees of the sweep, counter-clockwise.
 *     0 corresponds to the positive X axis.
 */
@RequiresApi(33)
fun Brush.Companion.sweepPigmentsGradient(
    startColor: Color,
    endColor: Color,
    centerOffset: Offset = Offset.Unspecified,
    angle: Float = 0.0f
): Brush =
    SweepPigmentsGradientBrush(
        startColor,
        endColor,
        centerOffset,
        angle
    )

/**
 * Create a linear gradient shader from the specified start position ([startOffset]),
 * to the specified end position ([endOffset]). Use [Float.POSITIVE_INFINITY] for x
 * and y to indicate the far right and far bottom of the drawing area respectively.
 *
 * @param startOffset The starting point of the gradient.
 * @param startColor The color to interpolate from.
 * @param endOffset The ending point of the gradient.
 * @param endColor The color to interpolate to.
 * @param tileMode Determines the behavior for how the gradient behaves outside
 *     its start and end positions. Defaults to `TileMode.Clamp` to repeat the
 *     edge pixels.
 */
@RequiresApi(33)
expect fun LinearPigmentsGradientShader(
    startColor: Color,
    endColor: Color,
    startOffset: Offset,
    endOffset: Offset,
    tileMode: TileMode = TileMode.Clamp
): Shader

internal class LinearPigmentsGradientBrush(
    val startColor: Color,
    val endColor: Color,
    val startOffset: Offset,
    val endOffset: Offset,
    val tileMode: TileMode
) : ShaderBrush() {
    override val intrinsicSize: Size
        get() =
            Size(
                if (startOffset.x.fastIsFinite() && endOffset.x.fastIsFinite()) {
                    abs(startOffset.x - endOffset.x)
                } else {
                    Float.NaN
                },
                if (startOffset.y.fastIsFinite() && endOffset.y.fastIsFinite()) {
                    abs(startOffset.y - endOffset.y)
                } else {
                    Float.NaN
                }
            )

    override fun createShader(size: Size): Shader {
        return LinearPigmentsGradientShader(
            startColor,
            endColor,
            startOffset.toShaderPosition(size),
            endOffset.toShaderPosition(size),
            tileMode
        )
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other == null || this::class != other::class) return false

        other as LinearPigmentsGradientBrush

        if (startColor != other.startColor) return false
        if (endColor != other.endColor) return false
        if (startOffset != other.startOffset) return false
        if (endOffset != other.endOffset) return false
        if (tileMode != other.tileMode) return false

        return true
    }

    override fun hashCode(): Int {
        var result = startColor.hashCode()
        result = 31 * result + endColor.hashCode()
        result = 31 * result + startOffset.hashCode()
        result = 31 * result + endOffset.hashCode()
        result = 31 * result + tileMode.hashCode()
        return result
    }

    override fun toString(): String {
        return "LinearPigmentsBrush(" +
                "startColor=$startColor, " +
                "endColor=$endColor, " +
                if (startOffset.isFinite) "startOffset=$startOffset, " else "" +
                if (endOffset.isFinite) "endOffset=$endOffset, " else "" +
                "tileMode=$tileMode)"
    }
}

/**
 * Create a circular gradient shader centered around the specified position
 * ([centerOffset]), with the specified [radius]. If [Offset.Unspecified] is used,
 * the center of the circular gradient will be the center of the drawing area.
 * You can also use [Float.POSITIVE_INFINITY] for x and y to indicate the far right
 * and far bottom of the drawing area respectively. [radius] can be set to
 * [Float.POSITIVE_INFINITY] to use a radius that fits within the drawing area.
 *
 * @param startColor The color to interpolate from.
 * @param endColor The color to interpolate to.
 * @param centerOffset The focal point of the circular gradient.
 * @param radius Radius, in pixels, of the circular gradient.
 * @param tileMode Determines the behavior for how the gradient behaves outside
 *     its start and end positions. Defaults to `TileMode.Clamp` to repeat the
 *     edge pixels.
 */
@RequiresApi(33)
expect fun RadialPigmentsGradientShader(
    startColor: Color,
    endColor: Color,
    centerOffset: Offset = Offset.Unspecified,
    radius: Float = Float.POSITIVE_INFINITY,
    tileMode: TileMode = TileMode.Clamp
): Shader

internal class RadialPigmentsGradientBrush(
    val startColor: Color,
    val endColor: Color,
    val centerOffset: Offset,
    val radius: Float,
    val tileMode: TileMode
) : ShaderBrush() {
    override val intrinsicSize: Size
        get() =
            if (radius.fastIsFinite()) {
                Size(radius * 2.0f, radius * 2.0f)
            } else {
                Size.Unspecified
            }

    override fun createShader(size: Size): Shader {
        return RadialPigmentsGradientShader(
            startColor,
            endColor,
            if (centerOffset.isSpecified) centerOffset.toShaderPosition(size) else size.center,
            if (radius == Float.POSITIVE_INFINITY) size.minDimension * 0.5f else radius,
            tileMode
        )
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other == null || this::class != other::class) return false

        other as RadialPigmentsGradientBrush

        if (radius != other.radius) return false
        if (startColor != other.startColor) return false
        if (endColor != other.endColor) return false
        if (centerOffset != other.centerOffset) return false
        if (tileMode != other.tileMode) return false

        return true
    }

    override fun hashCode(): Int {
        var result = radius.hashCode()
        result = 31 * result + startColor.hashCode()
        result = 31 * result + endColor.hashCode()
        result = 31 * result + centerOffset.hashCode()
        result = 31 * result + tileMode.hashCode()
        return result
    }

    override fun toString(): String {
        return "RadialPigmentsGradientBrush(" +
                "startColor=$startColor, " +
                "endColor=$endColor, " +
                if (centerOffset.isSpecified) "centerOffset=$centerOffset, " else "" +
                if (radius.fastIsFinite()) "radius=$radius, " else "" +
                "tileMode=$tileMode)"
    }
}

/**
 * Create a sweep gradient shader centered around the specified position
 * ([centerOffset]). If [Offset.Unspecified] is used, the center of  the sweep
 * gradient will be the center of the drawing area. You can also use
 * [Float.POSITIVE_INFINITY] for x and y to indicate the far right and far bottom
 * of the drawing area respectively.
 *
 * @param startColor The color to interpolate from.
 * @param endColor The color to interpolate to.
 * @param centerOffset The focal point of the gradient sweep.
 * @param angle The starting angle in degrees of the sweep, counter-clockwise.
 *     0 corresponds to the positive X axis.
 */
@RequiresApi(33)
expect fun SweepPigmentsGradientShader(
    startColor: Color,
    endColor: Color,
    centerOffset: Offset = Offset.Unspecified,
    angle: Float = 0.0f
): Shader

internal class SweepPigmentsGradientBrush(
    val startColor: Color,
    val endColor: Color,
    val centerOffset: Offset,
    val angle: Float
) : ShaderBrush() {
    override fun createShader(size: Size): Shader {
        return SweepPigmentsGradientShader(
            startColor,
            endColor,
            if (centerOffset.isSpecified) centerOffset.toShaderPosition(size) else size.center,
            angle
        )
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other == null || this::class != other::class) return false

        other as SweepPigmentsGradientBrush

        if (angle != other.angle) return false
        if (startColor != other.startColor) return false
        if (endColor != other.endColor) return false
        if (centerOffset != other.centerOffset) return false

        return true
    }

    override fun hashCode(): Int {
        var result = angle.hashCode()
        result = 31 * result + startColor.hashCode()
        result = 31 * result + endColor.hashCode()
        result = 31 * result + centerOffset.hashCode()
        return result
    }

    override fun toString(): String {
        return "SweepPigmentsGradientBrush(" +
                "startColor=$startColor, " +
                "endColor=$endColor, " +
                if (centerOffset.isSpecified) "centerOffset=$centerOffset, " else "" +
                "angle=$angle)"
    }
}

internal fun Offset.toShaderPosition(size: Size) =
    Offset(
        if (x != Float.POSITIVE_INFINITY) x else size.width,
        if (y != Float.POSITIVE_INFINITY) y else size.height
    )
