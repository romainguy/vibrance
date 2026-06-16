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
 */
@RequiresApi(33)
fun Modifier.verticalPaintGradient(startColor: Color, endColor: Color) =
    this then LinearPaintGradientElement(
        GradientType.Vertical,
        Offset.Unspecified,
        startColor,
        Offset.Unspecified,
        endColor
    )

/**
 * Add a horizontal gradient covering the entire size of the modifier's element.
 * The gradient goes from [startColor] on the left, to [endColor] on the right.
 */
@RequiresApi(33)
fun Modifier.horizontalPaintGradient(startColor: Color, endColor: Color) =
    this then LinearPaintGradientElement( // TODO: RTL?
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
 */
@RequiresApi(33)
fun Modifier.linearPaintGradient(
    startOffset: Offset,
    startColor: Color,
    endOffset: Offset,
    endColor: Color
) =
    this then LinearPaintGradientElement(
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
 */
@RequiresApi(33)
fun Modifier.radialPaintGradient(
    startColor: Color,
    endColor: Color,
    centerOffset: Offset = Offset.Unspecified,
    radius: Float = Float.POSITIVE_INFINITY
) =
    this then RadialPaintGradientElement(
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
 */
@RequiresApi(33)
fun Modifier.sweepPaintGradient(
    startColor: Color,
    endColor: Color,
    centerOffset: Offset = Offset.Unspecified
) =
    this then SweepPaintGradientElement(
        GradientType.Sweep,
        startColor,
        endColor,
        centerOffset
    )

internal enum class GradientType {
    Directional,
    Horizontal,
    Vertical,
    Radial,
    Sweep
}

private data class LinearPaintGradientElement(
    val type: GradientType,
    val startOffset: Offset,
    val startColor: Color,
    val endOffset: Offset,
    val endColor: Color
) : ModifierNodeElement<PaintGradientNode>() {
    override fun create(): PaintGradientNode {
        val node = PaintGradientNode(type)

        node.updateColors(startColor, endColor)
        node.updateLinearOffsets(startOffset, endOffset)

        return node
    }

    override fun update(node: PaintGradientNode) {
        node.updateColors(startColor, endColor)
        node.updateLinearOffsets(startOffset, endOffset)
    }
}

private data class RadialPaintGradientElement(
    val type: GradientType,
    val startColor: Color,
    val endColor: Color,
    val centerOffset: Offset,
    val radius: Float
) : ModifierNodeElement<PaintGradientNode>() {
    override fun create(): PaintGradientNode {
        val node = PaintGradientNode(type)

        node.updateColors(startColor, endColor)
        node.updateCircle(centerOffset, radius)

        return node
    }

    override fun update(node: PaintGradientNode) {
        node.updateColors(startColor, endColor)
        node.updateCircle(centerOffset, radius)
    }
}

private data class SweepPaintGradientElement(
    val type: GradientType,
    val startColor: Color,
    val endColor: Color,
    val centerOffset: Offset
) : ModifierNodeElement<PaintGradientNode>() {
    override fun create(): PaintGradientNode {
        val node = PaintGradientNode(type)

        node.updateColors(startColor, endColor)
        node.updateCircle(centerOffset, Float.NaN)

        return node
    }

    override fun update(node: PaintGradientNode) {
        node.updateColors(startColor, endColor)
        node.updateCircle(centerOffset, Float.NaN)
    }
}

@Suppress("EXPECT_ACTUAL_CLASSIFIERS_ARE_IN_BETA_WARNING")
internal expect class PaintGradientNode(
    type: GradientType
) : DrawModifierNode, Modifier.Node {
    override fun ContentDrawScope.draw()

    fun updateLinearOffsets(startOffset: Offset, endOffset: Offset)
    fun updateCircle(centerOffset: Offset, radius: Float)
    fun updateColors(startColor: Color, endColor: Color)
}
