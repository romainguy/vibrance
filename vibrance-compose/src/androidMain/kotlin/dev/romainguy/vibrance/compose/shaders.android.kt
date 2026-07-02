package dev.romainguy.vibrance.compose

import android.graphics.RuntimeShader
import androidx.annotation.RequiresApi
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TileMode
import androidx.compose.ui.graphics.colorspace.ColorSpaces
import dev.romainguy.vibrance.Vibrance

internal fun uniformsSource(type: GradientType) = when (type) {
    GradientType.Directional -> """
        uniform float2 $UniformPosition1;
        uniform float2 $UniformPosition2;
        uniform int $UniformTileMode;
    """.trimIndent()
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
    $interpolator
    $tileMode

    float3 l0 = mix($UniformLatent1, $UniformLatent2, t);
    float3 r0 = mix($UniformRemainders1, $UniformRemainders2, t);

    half3 color = half3(mixPigments(vec4(l0, 1.0 - (l0.x + l0.y + l0.z))) + r0);
    color = fromLinearSrgb(color);

    return Dither_TriangleNoise(fragCoord * (1.0 / 1080.0), color).rgb1;
}
""".trimIndent()

internal fun TileMode.toInt() = when (this) {
    TileMode.Clamp -> 0
    TileMode.Repeated -> 1
    TileMode.Mirror -> 2
    TileMode.Decal -> 3
    else -> -1
}

@RequiresApi(33)
internal fun RuntimeShader.setPigmentsUniforms(
    vibrance: Vibrance,
    startColor: Color,
    endColor: Color,
    startLatentColor: FloatArray,
    endLatentColor: FloatArray
) {
    val startSrgb = startColor.convert(ColorSpaces.Srgb)
    vibrance.colorToLatentColor(
        startSrgb.red,
        startSrgb.green,
        startSrgb.blue,
        startLatentColor
    )
    setFloatUniform(
        UniformLatent1,
        startLatentColor[0],
        startLatentColor[1],
        startLatentColor[2]
    )
    setFloatUniform(
        UniformRemainders1,
        startLatentColor[3],
        startLatentColor[4],
        startLatentColor[5]
    )

    val endSrgb = endColor.convert(ColorSpaces.Srgb)
    vibrance.colorToLatentColor(endSrgb.red, endSrgb.green, endSrgb.blue, endLatentColor)
    setFloatUniform(
        UniformLatent2,
        endLatentColor[0],
        endLatentColor[1],
        endLatentColor[2]
    )
    setFloatUniform(
        UniformRemainders2,
        endLatentColor[3],
        endLatentColor[4],
        endLatentColor[5]
    )
}
