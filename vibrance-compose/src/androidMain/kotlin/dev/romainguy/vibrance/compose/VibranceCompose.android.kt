package dev.romainguy.vibrance.compose

import android.graphics.RuntimeShader
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ShaderBrush
import androidx.compose.ui.graphics.drawscope.ContentDrawScope
import androidx.compose.ui.node.DrawModifierNode

private fun getInterpolator(orientation: GradientOrientation) = when (orientation) {
    GradientOrientation.Vertical -> "float t = uv.y;"
    GradientOrientation.Horizontal -> "float t = uv.x;"
}

private fun mixSource(interpolator: String) = """
half4 main(float2 fragCoord) {
    float2 uv = fragCoord * uniform_resolution.xy;

    $interpolator
    vec3 l0 = mix(uniform_l1, uniform_l2, t);
    vec3 r0 = mix(uniform_r1, uniform_r2, t);

    half3 color = half3(mixPigments(vec4(l0, 1.0 - (l0.x + l0.y + l0.z))) + r0);
    color = fromLinearSrgb(color);

    return Dither_TriangleNoise(uv, color).rgb1;
}
"""

@Suppress("EXPECT_ACTUAL_CLASSIFIERS_ARE_IN_BETA_WARNING")
internal actual class PaintGradientNode actual constructor(orientation: GradientOrientation) : DrawModifierNode,
    Modifier.Node() {

    actual val startLatentColor = FloatArray(6)
    actual val endLatentColor = FloatArray(6)

    val pigmentsMixShader = RuntimeShader(PigmentsMixShaderSource + mixSource(getInterpolator(orientation)))
    val shaderBrush = ShaderBrush(pigmentsMixShader)

    actual override fun ContentDrawScope.draw() {
        updatePigmentsMixUniform(pigmentsMixShader, startLatentColor, endLatentColor)
        drawRect(shaderBrush)
    }
}
