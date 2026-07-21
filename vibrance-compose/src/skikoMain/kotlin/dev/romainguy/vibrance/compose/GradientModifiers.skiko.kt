@file:Suppress("EXPECT_ACTUAL_CLASSIFIERS_ARE_IN_BETA_WARNING")

package dev.romainguy.vibrance.compose

import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.isSpecified
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ShaderBrush
import androidx.compose.ui.graphics.TileMode
import androidx.compose.ui.graphics.asComposeShader
import androidx.compose.ui.graphics.drawscope.ContentDrawScope
import androidx.compose.ui.node.DrawModifierNode
import androidx.compose.ui.util.fastIsFinite
import dev.romainguy.vibrance.Vibrance
import kotlin.math.PI

// TODO: We should track modifications that lead to new uniform values since Skiko
//       forces us to create a new Shader and ShaderBrush insteance every time.
internal actual class PigmentsGradientNode actual constructor(
    type: GradientType
) : DrawModifierNode, Modifier.Node() {
    val vibrance = Vibrance()
    val startLatentColor = FloatArray(6)
    val endLatentColor = FloatArray(6)
    var startOffset = Offset.Zero
    var endOffset = Offset.Zero
    var tileMode = TileMode.Clamp

    val type = type

    val shader = assembleGradientShader(type)

    actual override fun ContentDrawScope.draw() {
        // Update these uniforms on every draw in case the size has changed
        when (type) {
            GradientType.Directional -> {
                shader.uniform(UniformPosition1, startOffset.toShaderPosition(size))
                shader.uniform(UniformPosition2, endOffset.toShaderPosition(size))
            }

            GradientType.Radial -> {
                val gradientCenter =
                    if (startOffset.isSpecified) startOffset.toShaderPosition(size) else center
                shader.uniform(UniformCenterRadius,
                    gradientCenter.x,
                    gradientCenter.y,
                    1.0f / if (endOffset.x.fastIsFinite()) endOffset.x else size.minDimension * 0.5f
                )
            }

            GradientType.Sweep -> {
                val gradientCenter =
                    if (startOffset.isSpecified) startOffset.toShaderPosition(size) else center
                shader.uniform(
                    UniformCenterAngle,
                    gradientCenter.x,
                    gradientCenter.y,
                    endOffset.x * (PI / 180.0).toFloat()
                )
            }
        }

        val composeShader = shader.makeShader().asComposeShader()
        drawRect(ShaderBrush(composeShader))
    }

    actual fun updateLinearOffsets(startOffset: Offset, endOffset: Offset) {
        this.startOffset = startOffset
        this.endOffset = endOffset
    }

    actual fun updateRadialGeometry(centerOffset: Offset, radiusOrAngle: Float) {
        startOffset = centerOffset
        endOffset = Offset(radiusOrAngle, Float.NaN)
    }

    actual fun updateColors(startColor: Color, endColor: Color) {
        shader.uniforms(vibrance, startColor, endColor, startLatentColor, endLatentColor)
    }

    actual fun updateTileMode(tileMode: TileMode) {
        this.tileMode = tileMode
        if (type == GradientType.Directional || type == GradientType.Radial) {
            shader.uniform(UniformTileMode, tileMode.toInt())
        }
    }
}
