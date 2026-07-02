package dev.romainguy.vibrance.compose

import androidx.annotation.RequiresApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TileMode
import androidx.compose.ui.graphics.drawscope.ContentDrawScope
import androidx.compose.ui.node.DrawModifierNode
import androidx.compose.ui.node.ModifierNodeElement

/**
 * Add a vertical gradient covering the entire size of the modifier's element.
 * The gradient goes from [startColor] at the top, to [endColor] at the bottom.
 * @param startColor The color to interpolate from.
 * @param endColor The color to interpolate to.
 */
@RequiresApi(33)
fun Modifier.verticalPigmentsGradient(startColor: Color, endColor: Color) =
    this then LinearPigmentsGradientElement(
        GradientType.Directional,
        startColor,
        endColor,
        Offset.Zero,
        Offset(0.0f, Float.POSITIVE_INFINITY),
        TileMode.Clamp
    )

/**
 * Add a vertical gradient from the starting position [startY] to the ending
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
fun Modifier.verticalPigmentsGradient(
    startColor: Color,
    endColor: Color,
    startY: Float = 0.0f,
    endY: Float = Float.POSITIVE_INFINITY,
    tileMode: TileMode = TileMode.Clamp
) =
    this then LinearPigmentsGradientElement(
        GradientType.Directional,
        startColor,
        endColor,
        Offset(0.0f, startY),
        Offset(0.0f, endY),
        tileMode
    )

/**
 * Add a horizontal gradient covering the entire size of the modifier's element.
 * The gradient goes from [startColor] on the left, to [endColor] on the right.
 *
 * @param startColor The color to interpolate from.
 * @param endColor The color to interpolate to.
 */
@RequiresApi(33)
fun Modifier.horizontalPigmentsGradient(startColor: Color, endColor: Color) =
    this then LinearPigmentsGradientElement(
        GradientType.Directional,
        startColor,
        endColor,
        Offset.Zero,
        Offset(Float.POSITIVE_INFINITY, 0.0f),
        TileMode.Clamp
    )

/**
 * Add a horizontal gradient from the starting position [startX] to the ending
 * position [endX].
 *
 * @param startColor The color to interpolate from.
 * @param endColor The color to interpolate to.
 * @param startX The starting horizontal position of the gradient.
 * @param endX The ending horizontal position of the gradient. Use
 *     [Float.POSITIVE_INFINITY] to indicate the right of the drawing area.
 * @param tileMode Determines the behavior for how the gradient behaves outside
 *     its start and end positions. Defaults to `TileMode.Clamp` to repeat the
 *     edge pixels.
 */
@RequiresApi(33)
fun Modifier.horizontalPigmentsGradient(
    startColor: Color,
    endColor: Color,
    startX: Float = 0.0f,
    endX: Float = Float.POSITIVE_INFINITY,
    tileMode: TileMode = TileMode.Clamp
) =
    this then LinearPigmentsGradientElement(
        GradientType.Directional,
        startColor,
        endColor,
        Offset(startX, 0.0f),
        Offset(endX, 0.0f),
        tileMode
    )

/**
 * Add a gradient from the specified start position ([startOffset]), to the
 * specified end position ([endOffset]). Use [Float.POSITIVE_INFINITY] for
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
fun Modifier.linearPigmentsGradient(
    startColor: Color,
    endColor: Color,
    startOffset: Offset,
    endOffset: Offset,
    tileMode: TileMode = TileMode.Clamp
) =
    this then LinearPigmentsGradientElement(
        GradientType.Directional,
        startColor,
        endColor,
        startOffset,
        endOffset,
        tileMode
    )

/**
 * Add a circular gradient centered around the specified position ([centerOffset]),
 * with the specified [radius]. If [Offset.Unspecified] is used, the center of
 * the circular gradient will be the center of the drawing area. You can also
 * use [Float.POSITIVE_INFINITY] for x and y to indicate the far right and far
 * bottom of the drawing area respectively. [radius] can be set to
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
fun Modifier.radialPigmentsGradient(
    startColor: Color,
    endColor: Color,
    centerOffset: Offset = Offset.Unspecified,
    radius: Float = Float.POSITIVE_INFINITY,
    tileMode: TileMode = TileMode.Clamp
) =
    this then RadialPigmentsGradientElement(
        GradientType.Radial,
        startColor,
        endColor,
        centerOffset,
        radius,
        tileMode
    )

/**
 * Add a sweep gradient centered around the specified position ([centerOffset]).
 * If [Offset.Unspecified] is used, the center of  the sweep gradient will be
 * the center of the drawing area. You can also use [Float.POSITIVE_INFINITY]
 * for x and y to indicate the far right and far bottom of the drawing area
 * respectively.
 *
 * @param startColor The color to interpolate from.
 * @param endColor The color to interpolate to.
 * @param centerOffset The focal point of the gradient sweep.
 * @param angle The starting angle in degrees of the sweep, counter-clockwise.
 *     0 corresponds to the positive X axis.
 */
@RequiresApi(33)
fun Modifier.sweepPigmentsGradient(
    startColor: Color,
    endColor: Color,
    centerOffset: Offset = Offset.Unspecified,
    angle: Float = 0.0f
) =
    this then SweepPigmentsGradientElement(
        GradientType.Sweep,
        startColor,
        endColor,
        centerOffset,
        angle
    )

internal enum class GradientType {
    Directional,
    Radial,
    Sweep
}

private data class LinearPigmentsGradientElement(
    val type: GradientType,
    val startColor: Color,
    val endColor: Color,
    val startOffset: Offset,
    val endOffset: Offset,
    val tileMode: TileMode
) : ModifierNodeElement<PigmentsGradientNode>() {
    override fun create(): PigmentsGradientNode {
        val node = PigmentsGradientNode(type)

        node.updateColors(startColor, endColor)
        node.updateLinearOffsets(startOffset, endOffset)
        node.updateTileMode(tileMode)

        return node
    }

    override fun update(node: PigmentsGradientNode) {
        node.updateColors(startColor, endColor)
        node.updateLinearOffsets(startOffset, endOffset)
        node.updateTileMode(tileMode)
    }
}

private data class RadialPigmentsGradientElement(
    val type: GradientType,
    val startColor: Color,
    val endColor: Color,
    val centerOffset: Offset,
    val radius: Float,
    val tileMode: TileMode
) : ModifierNodeElement<PigmentsGradientNode>() {
    override fun create(): PigmentsGradientNode {
        val node = PigmentsGradientNode(type)

        node.updateColors(startColor, endColor)
        node.updateRadialGeometry(centerOffset, radius)
        node.updateTileMode(tileMode)

        return node
    }

    override fun update(node: PigmentsGradientNode) {
        node.updateColors(startColor, endColor)
        node.updateRadialGeometry(centerOffset, radius)
        node.updateTileMode(tileMode)
    }
}

private data class SweepPigmentsGradientElement(
    val type: GradientType,
    val startColor: Color,
    val endColor: Color,
    val centerOffset: Offset,
    val angle: Float
) : ModifierNodeElement<PigmentsGradientNode>() {
    override fun create(): PigmentsGradientNode {
        val node = PigmentsGradientNode(type)

        node.updateColors(startColor, endColor)
        node.updateRadialGeometry(centerOffset, angle)

        return node
    }

    override fun update(node: PigmentsGradientNode) {
        node.updateColors(startColor, endColor)
        node.updateRadialGeometry(centerOffset, angle)
    }
}

@Suppress("EXPECT_ACTUAL_CLASSIFIERS_ARE_IN_BETA_WARNING")
internal expect class PigmentsGradientNode(
    type: GradientType
) : DrawModifierNode, Modifier.Node {
    override fun ContentDrawScope.draw()

    fun updateColors(startColor: Color, endColor: Color)
    fun updateLinearOffsets(startOffset: Offset, endOffset: Offset)
    fun updateRadialGeometry(centerOffset: Offset, radiusOrAngle: Float)
    fun updateTileMode(tileMode: TileMode)
}
