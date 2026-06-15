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
    this then PaintGradientElement(
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
    this then PaintGradientElement( // TODO: RTL?
        GradientType.Horizontal,
        Offset.Unspecified,
        startColor,
        Offset.Unspecified,
        endColor
    )

/**
 * Add a gradient from the specified start position ([startOffset]), to the
 * specified end position ([endOffset]).
 */
@RequiresApi(33)
fun Modifier.paintGradient(
    startOffset: Offset,
    startColor: Color,
    endOffset: Offset,
    endColor: Color
) =
    this then PaintGradientElement(
        GradientType.Directional,
        startOffset,
        startColor,
        endOffset,
        endColor
    )

internal enum class GradientType {
    Directional,
    Horizontal,
    Vertical
}

private data class PaintGradientElement(
    val orientation: GradientType,
    val startOffset: Offset,
    val startColor: Color,
    val endOffset: Offset,
    val endColor: Color
) : ModifierNodeElement<PaintGradientNode>() {
    override fun create(): PaintGradientNode {
        val node = PaintGradientNode(orientation)

        node.updateColors(startColor, endColor)
        node.updateOffsets(startOffset, endOffset)

        return node
    }

    override fun update(node: PaintGradientNode) {
        node.updateColors(startColor, endColor)
        node.updateOffsets(startOffset, endOffset)
    }
}

@Suppress("EXPECT_ACTUAL_CLASSIFIERS_ARE_IN_BETA_WARNING")
internal expect class PaintGradientNode(
    type: GradientType
) : DrawModifierNode, Modifier.Node {
    override fun ContentDrawScope.draw()

    fun updateOffsets(startOffset: Offset, endOffset: Offset)
    fun updateColors(startColor: Color, endColor: Color)
}
