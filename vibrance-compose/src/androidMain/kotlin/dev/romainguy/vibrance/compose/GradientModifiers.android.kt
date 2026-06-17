package dev.romainguy.vibrance.compose

import android.graphics.RuntimeShader
import androidx.annotation.RequiresApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.geometry.isSpecified
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ShaderBrush
import androidx.compose.ui.graphics.colorspace.ColorSpaces
import androidx.compose.ui.graphics.drawscope.ContentDrawScope
import androidx.compose.ui.node.DrawModifierNode
import dev.romainguy.vibrance.Vibrance
import kotlin.math.PI

@RequiresApi(33)
@Suppress("EXPECT_ACTUAL_CLASSIFIERS_ARE_IN_BETA_WARNING")
internal actual class PigmentsGradientNode actual constructor(
    type: GradientType
) : DrawModifierNode, Modifier.Node() {
    val vibrance = Vibrance()
    val startLatentColor = FloatArray(6)
    val endLatentColor = FloatArray(6)
    var startOffset = Offset.Zero
    var endOffset = Offset.Zero

    val type = type

    val pigmentsMixShader = RuntimeShader(
        uniformsSource(type) +
        PigmentsMixShaderSource +
        mixSource(interpolator(type))
    )
    val shaderBrush = ShaderBrush(pigmentsMixShader)

    actual override fun ContentDrawScope.draw() {
        updatePigmentsMixUniform(pigmentsMixShader)

        // Update these uniforms on every draw in case the size has changed
        when (type) {
            GradientType.Directional -> {
                val start = startOffset.toShaderPosition(size)
                pigmentsMixShader.setFloatUniform(UniformPosition1, start.x, start.y)

                val end = endOffset.toShaderPosition(size)
                pigmentsMixShader.setFloatUniform(UniformPosition2, end.x, end.y)
            }

            GradientType.Radial -> {
                val gradientCenter =
                    if (startOffset.isSpecified) startOffset.toShaderPosition(size) else center
                pigmentsMixShader.setFloatUniform(UniformCenterRadius,
                    gradientCenter.x,
                    gradientCenter.y,
                    1.0f / if (endOffset.x.isFinite()) endOffset.x else size.minDimension * 0.5f
                )
            }

            GradientType.Sweep -> {
                val gradientCenter =
                    if (startOffset.isSpecified) startOffset.toShaderPosition(size) else center
                pigmentsMixShader.setFloatUniform(
                    UniformCenterAngle,
                    gradientCenter.x,
                    gradientCenter.y,
                    endOffset.x * (PI / 180.0).toFloat()
                )
            }
            else -> { }
        }

        drawRect(shaderBrush)
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
        val shader = pigmentsMixShader

        val startSrgb = startColor.convert(ColorSpaces.Srgb)
        vibrance.colorToLatentColor(
            startSrgb.red,
            startSrgb.green,
            startSrgb.blue,
            startLatentColor
        )
        shader.setFloatUniform(
            UniformLatent1,
            startLatentColor[0],
            startLatentColor[1],
            startLatentColor[2]
        )
        shader.setFloatUniform(
            UniformRemainders1,
            startLatentColor[3],
            startLatentColor[4],
            startLatentColor[5]
        )

        val endSrgb = endColor.convert(ColorSpaces.Srgb)
        vibrance.colorToLatentColor(endSrgb.red, endSrgb.green, endSrgb.blue, endLatentColor)
        shader.setFloatUniform(
            UniformLatent2,
            endLatentColor[0],
            endLatentColor[1],
            endLatentColor[2]
        )
        shader.setFloatUniform(
            UniformRemainders2,
            endLatentColor[3],
            endLatentColor[4],
            endLatentColor[5]
        )
    }
}

internal fun Offset.toShaderPosition(size: Size) =
    Offset(if (x.isFinite()) x else size.width, if (y.isFinite()) y else size.height)
