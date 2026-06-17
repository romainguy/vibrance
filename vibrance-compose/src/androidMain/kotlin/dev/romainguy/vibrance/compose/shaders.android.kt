package dev.romainguy.vibrance.compose

import android.graphics.RuntimeShader
import androidx.annotation.RequiresApi
import androidx.compose.ui.graphics.drawscope.ContentDrawScope

internal fun uniformsSource(type: GradientType) = when (type) {
    GradientType.Directional -> """
        uniform float2 $UniformPosition1;
        uniform float2 $UniformPosition2;
        uniform int $UniformTileMode;
    """.trimIndent()
    GradientType.Horizontal -> ""
    GradientType.Vertical -> ""
    GradientType.Radial -> """
        uniform float3 $UniformCenterRadius;
        uniform int $UniformTileMode;
    """.trimIndent()
    GradientType.Sweep -> """
        uniform float3 $UniformCenterAngle;
    """.trimIndent()
}

internal fun interpolator(type: GradientType) = when (type) {
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

internal fun tileMode(type: GradientType) = when (type) {
    GradientType.Directional, GradientType.Radial -> """
        switch ($UniformTileMode) {
            case TileMode_Clamp:
                t = saturate(t);
                break;
            case TileMode_Repeated:
                t = t - floor(t);
                break;
            case TileMode_Mirror:
                t = 1.0 - abs(mod(t, 2.0) - 1.0);
                break;
            case TileMode_Decal:
                if (t < 0.0 || t > 1.0) return half4(0.0);
                break;
        }
    """
    else -> "t = saturate(t);"
}

internal fun mixSource(interpolator: String, tileMode: String) = """
half4 main(float2 fragCoord) {
    float2 uv = fragCoord * $UniformResolution.xy;

    $interpolator
    $tileMode

    float3 l0 = mix($UniformLatent1, $UniformLatent2, t);
    float3 r0 = mix($UniformRemainders1, $UniformRemainders2, t);

    half3 color = half3(mixPigments(vec4(l0, 1.0 - (l0.x + l0.y + l0.z))) + r0);
    color = fromLinearSrgb(color);

    return Dither_TriangleNoise(uv, color).rgb1;
}
""".trimIndent()

@RequiresApi(33)
internal fun ContentDrawScope.updatePigmentsMixUniform(shader: RuntimeShader) {
    shader.setFloatUniform(UniformResolution, 1.0f / size.width, 1.0f / size.height)
}
