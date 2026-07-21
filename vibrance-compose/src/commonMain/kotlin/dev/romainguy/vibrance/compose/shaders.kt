package dev.romainguy.vibrance.compose

import androidx.compose.ui.graphics.TileMode

internal const val UniformLatent1 = "uniform_l1"
internal const val UniformRemainders1 = "uniform_r1"
internal const val UniformLatent2 = "uniform_l2"
internal const val UniformRemainders2 = "uniform_r2"
internal const val UniformPosition1 = "uniform_p1"
internal const val UniformPosition2 = "uniform_p2"
internal const val UniformCenterRadius = "uniform_centerRadius"
internal const val UniformCenterAngle = "uniform_centerAngle"
internal const val UniformTileMode = "uniform_tileMode"

// language=agsl
private const val PigmentsMixShaderSource = """
uniform float3 $UniformLatent1;
uniform float3 $UniformRemainders1;
uniform float3 $UniformLatent2;
uniform float3 $UniformRemainders2;

const int TileMode_Clamp  = 0;
const int TileMode_Repeated = 1;
const int TileMode_Mirror = 2;
const int TileMode_Decal = 3;

float triangleNoise(float2 n) {
    // triangle noise, in [-1.0..1.0[ range
    n  = fract(n * float2(5.3987, 5.4421));
    n += dot(n.yx, n.xy + float2(21.5351, 14.3137));

    float xy = n.x * n.y;
    // compute in [0..2[ and remap to [-1.0..1.0[
    return fract(xy * 95.4307) + fract(xy * 75.04961) - 1.0;
}

half3 Dither_TriangleNoise(float2 uv, half3 rgb) {
    // Gjøl 2016, "Banding in Games: A Noisy Rant"
    float noise = triangleNoise(uv);
    // noise is in [-1..1[
    return rgb + half3(noise / 255.0);
}

float3 mixPigments(vec4 c) {
    float3 rgb = float3(-0.0249582, 0.3584879, 0.0244421);

    float h1 = max(0.0, 0.0098267 + dot(c, vec4(0.1174974, 0.5078091, -0.2443916, -0.3047154)));
    rgb += h1 * float3(-0.0341629, 0.5760666, -0.0325691);

    float h2 = max(0.0, -0.6286458 + dot(c, vec4(-2.8839049, 0.7584528, 0.7916569, 0.7818422)));
    rgb += h2 * float3(2.7300307, 0.1375640, -0.0742932);

    float h3 = max(0.0, 0.1541230 + dot(c, vec4(0.2867913, 0.0568774, -0.5446743, 0.1930918)));
    rgb += h3 * float3(-0.1012921, 0.0580865, 0.5416284);

    float h5 = max(0.0, 0.0747424 + dot(c, vec4(-1.7088713, 0.1860320, 0.2180080, 0.2195648)));
    rgb += h5 * float3(1.1088474, 0.0263522, -0.0335531);

    float h6 = max(0.0, -0.2055153 + dot(c, vec4(-0.4361817, -0.8967318, 0.4045247, 0.4025162)));
    rgb += h6 * float3(0.1815542, 0.9910546, -0.1118827);

    float h9 = max(0.0, 0.3792505 + dot(c, vec4(0.0286469, 0.4794231, -0.3951822, 0.0329820)));
    rgb += h9 * float3(0.1625491, -0.6810130, 0.2960019);

    float h10 = max(0.0, -0.1452454 + dot(c, vec4(0.2560401, 0.1701091, -1.2982900, 0.3221915)));
    rgb += h10 * float3(-0.1341101, 0.0278039, 1.2155828);

    float h11 = max(0.0, 0.2256926 + dot(c, vec4(-0.1793450, -0.3194858, -0.0576552, 0.5597214)));
    rgb += h11 * float3(0.0951412, 0.6306976, 0.1533901);

    float h12 = max(0.0, 0.0271525 + dot(c, vec4(0.2065663, 0.1657660, -0.9326340, 0.2456872)));
    rgb += h12 * float3(0.0214799, 0.0282143, 0.8276884);

    return saturate(rgb);
}
"""

private fun uniformsSource(type: GradientType) = when (type) {
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

private fun interpolator(type: GradientType) = when (type) {
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

private fun tileMode(type: GradientType) = when (type) {
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

private fun mixSource(interpolator: String, tileMode: String) = """
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


internal fun assembleGradientShaderSource(type: GradientType): String =
    uniformsSource(type) + PigmentsMixShaderSource + mixSource(interpolator(type), tileMode(type))
