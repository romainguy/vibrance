package dev.romainguy.vibrance.compose

import androidx.annotation.RequiresApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.colorspace.ColorSpaces
import androidx.compose.ui.graphics.drawscope.ContentDrawScope
import androidx.compose.ui.node.DrawModifierNode
import androidx.compose.ui.node.ModifierNodeElement
import dev.romainguy.vibrance.Vibrance

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
    val vibrance = Vibrance()

    override fun create(): PaintGradientNode {
        val startSrgb = startColor.convert(ColorSpaces.Srgb)
        val endSrgb = endColor.convert(ColorSpaces.Srgb)
        val node = PaintGradientNode(orientation, startOffset, endOffset)

        vibrance.colorToLatentColor(startSrgb.red, startSrgb.green, startSrgb.blue, node.startLatentColor)
        vibrance.colorToLatentColor(endSrgb.red, endSrgb.green, endSrgb.blue, node.endLatentColor)

        return node
    }

    override fun update(node: PaintGradientNode) {
        val startSrgb = startColor.convert(ColorSpaces.Srgb)
        val endSrgb = endColor.convert(ColorSpaces.Srgb)

        vibrance.colorToLatentColor(startSrgb.red, startSrgb.green, startSrgb.blue, node.startLatentColor)
        vibrance.colorToLatentColor(endSrgb.red, endSrgb.green, endSrgb.blue, node.endLatentColor)
    }
}

@Suppress("EXPECT_ACTUAL_CLASSIFIERS_ARE_IN_BETA_WARNING")
internal expect class PaintGradientNode(
    type: GradientType,
    startOffset: Offset,
    endOffset: Offset
) : DrawModifierNode, Modifier.Node {
    val startLatentColor: FloatArray
    val endLatentColor: FloatArray

    override fun ContentDrawScope.draw()
}
