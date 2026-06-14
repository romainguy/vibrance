package dev.romainguy.vibrance.compose

import androidx.compose.ui.Modifier
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
fun Modifier.verticalPaintGradient(startColor: Color, endColor: Color) =
    this then PaintGradientElement(GradientOrientation.Vertical, startColor, endColor)

/**
 * Add a horizontal gradient covering the entire size of the modifier's element.
 * The gradient goes from [startColor] on the left, to [endColor] on the right.
 */
fun Modifier.horizontalPaintGradient(startColor: Color, endColor: Color) =
    this then PaintGradientElement(GradientOrientation.Horizontal, startColor, endColor) // TODO: RTL?

internal enum class GradientOrientation {
    Vertical,
    Horizontal
}

private data class PaintGradientElement(
    val orientation: GradientOrientation,
    val startColor: Color,
    val endColor: Color
) : ModifierNodeElement<PaintGradientNode>() {
    val vibrance = Vibrance()

    override fun create(): PaintGradientNode {
        val startSrgb = startColor.convert(ColorSpaces.Srgb)
        val endSrgb = endColor.convert(ColorSpaces.Srgb)
        val node = PaintGradientNode(orientation)

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
internal expect class PaintGradientNode(orientation: GradientOrientation) : DrawModifierNode, Modifier.Node {
    val startLatentColor: FloatArray
    val endLatentColor: FloatArray

    override fun ContentDrawScope.draw()
}
