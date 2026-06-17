package dev.romainguy.vibrance.compose

import androidx.annotation.RequiresApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
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
        GradientType.Vertical,
        Offset.Unspecified,
        startColor,
        Offset.Unspecified,
        endColor
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
    this then LinearPigmentsGradientElement( // TODO: RTL?
        GradientType.Horizontal,
        Offset.Unspecified,
        startColor,
        Offset.Unspecified,
        endColor
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
 */
@RequiresApi(33)
fun Modifier.linearPigmentsGradient(
    startOffset: Offset,
    startColor: Color,
    endOffset: Offset,
    endColor: Color
) =
    this then LinearPigmentsGradientElement(
        GradientType.Directional,
        startOffset,
        startColor,
        endOffset,
        endColor
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
 */
@RequiresApi(33)
fun Modifier.radialPigmentsGradient(
    startColor: Color,
    endColor: Color,
    centerOffset: Offset = Offset.Unspecified,
    radius: Float = Float.POSITIVE_INFINITY
) =
    this then RadialPigmentsGradientElement(
        GradientType.Radial,
        startColor,
        endColor,
        centerOffset,
        radius
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
    Horizontal,
    Vertical,
    Radial,
    Sweep
}

private data class LinearPigmentsGradientElement(
    val type: GradientType,
    val startOffset: Offset,
    val startColor: Color,
    val endOffset: Offset,
    val endColor: Color
) : ModifierNodeElement<PigmentsGradientNode>() {
    override fun create(): PigmentsGradientNode {
        val node = PigmentsGradientNode(type)

        node.updateColors(startColor, endColor)
        node.updateLinearOffsets(startOffset, endOffset)

        return node
    }

    override fun update(node: PigmentsGradientNode) {
        node.updateColors(startColor, endColor)
        node.updateLinearOffsets(startOffset, endOffset)
    }
}

private data class RadialPigmentsGradientElement(
    val type: GradientType,
    val startColor: Color,
    val endColor: Color,
    val centerOffset: Offset,
    val radius: Float
) : ModifierNodeElement<PigmentsGradientNode>() {
    override fun create(): PigmentsGradientNode {
        val node = PigmentsGradientNode(type)

        node.updateColors(startColor, endColor)
        node.updateRadialGeometry(centerOffset, radius)

        return node
    }

    override fun update(node: PigmentsGradientNode) {
        node.updateColors(startColor, endColor)
        node.updateRadialGeometry(centerOffset, radius)
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
}
