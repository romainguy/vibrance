package dev.romainguy.vibrance.compose

import android.graphics.RuntimeShader
import androidx.annotation.RequiresApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.ShaderBrush
import androidx.compose.ui.graphics.drawscope.ContentDrawScope
import androidx.compose.ui.node.DrawModifierNode

private fun uniformsSource(type: GradientType) = when (type) {
    GradientType.Directional -> """
        uniform float2 $UniformPosition1;
        uniform float2 $UniformPosition2;
    """.trimIndent()
    GradientType.Horizontal -> ""
    GradientType.Vertical -> ""
}

private fun getInterpolator(type: GradientType) = when (type) {
    GradientType.Directional -> """
        float2 axis = $UniformPosition2 - $UniformPosition1;
        float axisLength = inversesqrt(dot(axis, axis));
        float2 direction = fragCoord - $UniformPosition1;
        float t = dot(direction, axis * axisLength) * axisLength;
    """
    GradientType.Horizontal -> "float t = uv.x;"
    GradientType.Vertical -> "float t = uv.y;"
}

private fun mixSource(interpolator: String) = """
half4 main(float2 fragCoord) {
    float2 uv = fragCoord * $UniformResolution.xy;

    $interpolator

    float3 l0 = mix($UniformLatent1, $UniformLatent2, t);
    float3 r0 = mix($UniformRemainders1, $UniformRemainders2, t);

    half3 color = half3(mixPigments(vec4(l0, 1.0 - (l0.x + l0.y + l0.z))) + r0);
    color = fromLinearSrgb(color);

    return Dither_TriangleNoise(uv, color).rgb1;
}
""".trimIndent()

@RequiresApi(33)
@Suppress("EXPECT_ACTUAL_CLASSIFIERS_ARE_IN_BETA_WARNING")
internal actual class PaintGradientNode actual constructor(
    type: GradientType,
    startOffset: Offset,
    endOffset: Offset
) : DrawModifierNode,
    Modifier.Node() {

    actual val startLatentColor = FloatArray(6)
    actual val endLatentColor = FloatArray(6)

    val pigmentsMixShader = RuntimeShader(
        uniformsSource(type) +
        PigmentsMixShaderSource +
        mixSource(getInterpolator(type))
    )
    val shaderBrush = ShaderBrush(pigmentsMixShader)

    val type = type
    val startOffset = startOffset
    val endOffset = endOffset

    actual override fun ContentDrawScope.draw() {
        updatePigmentsMixUniform(pigmentsMixShader, startLatentColor, endLatentColor)

        if (type == GradientType.Directional) {
            val start = startOffset.toShaderPosition(size)
            pigmentsMixShader.setFloatUniform(UniformPosition1, start.x, start.y)

            val end = endOffset.toShaderPosition(size)
            pigmentsMixShader.setFloatUniform(UniformPosition2, end.x, end.y)
        }

        drawRect(shaderBrush)
    }
}

internal fun Offset.toShaderPosition(size: Size) =
    Offset(if (x.isFinite()) x else size.width, if (y.isFinite()) y else size.height)
