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

private fun uniformsSource(type: GradientType) = when (type) {
    GradientType.Directional -> """
        uniform float2 $UniformPosition1;
        uniform float2 $UniformPosition2;
    """.trimIndent()
    GradientType.Horizontal -> ""
    GradientType.Vertical -> ""
    GradientType.Radial -> """
        uniform float3 $UniformCenterRadius;
    """.trimIndent()
    GradientType.Sweep -> """
        uniform float3 $UniformCenterAngle;
    """.trimIndent()
}

private fun interpolator(type: GradientType) = when (type) {
    GradientType.Directional -> """
        float2 axis = $UniformPosition2 - $UniformPosition1;
        float axisLength = inversesqrt(dot(axis, axis));
        float2 direction = fragCoord - $UniformPosition1;
        float t = dot(direction, axis * axisLength) * axisLength;
    """
    GradientType.Horizontal -> "float t = uv.x;"
    GradientType.Vertical -> "float t = uv.y;"
    GradientType.Radial -> """
        float2 direction = fragCoord - $UniformCenterRadius.xy;
        float t = length(direction) * $UniformCenterRadius.z;
    """
    GradientType.Sweep -> """
        float2 direction = fragCoord - $UniformCenterAngle.xy;
        // 1 / (2 * PI)
        float t = (atan(direction.y, direction.x) + $UniformCenterAngle.z) * 0.15915494;
        t = t - floor(t);
    """
}

private fun mixSource(interpolator: String) = """
half4 main(float2 fragCoord) {
    float2 uv = fragCoord * $UniformResolution.xy;

    $interpolator
    t = saturate(t);

    float3 l0 = mix($UniformLatent1, $UniformLatent2, t);
    float3 r0 = mix($UniformRemainders1, $UniformRemainders2, t);

    half3 color = half3(mixPigments(vec4(l0, 1.0 - (l0.x + l0.y + l0.z))) + r0);
    color = fromLinearSrgb(color);

    return Dither_TriangleNoise(uv, color).rgb1;
}
""".trimIndent()

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
