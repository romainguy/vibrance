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

float3 mixPigments(float3 c) {
    float3 rgb = float3(0.1539308, -0.2101102, 0.1130296)
        + c.x * float3(-1.5850313, -0.4561743, -0.2899411)
        + c.y * float3(0.0049084, -0.1060945, 0.0961012)
        + c.z * float3(-0.1980831, 0.0193492, -2.8117707);

    float h0 = max(0.0, -0.5732316 + dot(c, float3(2.4183984, 0.1553283, 0.0646514)));
    rgb += h0 * float3(-0.4831170, -0.1122841, -0.0794509);

    float h1 = max(0.0, 0.5309075 + dot(c, float3(-6.0326037, -0.0136373, 0.0213502)));
    rgb += h1 * float3(0.4891092, -0.0002045, -0.0159511);

    float h2 = max(0.0, -0.1674344 + dot(c, float3(0.1293259, 0.3301166, 2.7173810)));
    rgb += h2 * float3(-0.1710407, 0.1534036, 0.8488734);

    float h3 = max(0.0, 0.0287062 + dot(c, float3(2.4400022, 0.0027635, -0.1977382)));
    rgb += h3 * float3(-0.5497317, 0.0361791, 0.1983951);

    float h4 = max(0.0, 0.4402751 + dot(c, float3(-3.0921938, -4.6753030, 0.0586780)));
    rgb += h4 * float3(0.1203473, 0.4039454, -0.0212024);

    float h5 = max(0.0, 0.0922123 + dot(c, float3(-8.8464346, -0.1681033, 0.1123352)));
    rgb += h5 * float3(1.0090508, 0.1335829, -0.0686533);

    float h6 = max(0.0, -0.6625637 + dot(c, float3(0.1312097, 0.1675960, 1.9033443)));
    rgb += h6 * float3(-0.0016896, 0.0071924, 0.4053358);

    float h7 = max(0.0, 0.4586772 + dot(c, float3(-0.0998359, -0.2719077, -3.2554240)));
    rgb += h7 * float3(-0.0355527, 0.0159359, 0.5412980);

    float h8 = max(0.0, 1.4274933 + dot(c, float3(-1.3719325, -1.0621926, -1.4547547)));
    rgb += h8 * float3(0.1521681, 0.3157202, 0.4113466);

    float h9 = max(0.0, 0.6399117 + dot(c, float3(-1.0160148, -2.0311494, -0.0227793)));
    rgb += h9 * float3(-0.0102088, 0.3475253, -0.0262109);

    float h10 = max(0.0, 0.8562082 + dot(c, float3(3.4214549, -0.8499470, -0.8562099)));
    rgb += h10 * float3(-0.5840385, 0.2226422, -0.0267674);

    float h11 = max(0.0, 0.9718049 + dot(c, float3(-4.0978346, -0.2651648, -0.1055439)));
    rgb += h11 * float3(0.5092035, 0.0837957, 0.0589227);

    float h12 = max(0.0, -0.1236852 + dot(c, float3(2.4840240, -0.0313387, 0.6974269)));
    rgb += h12 * float3(0.3274774, 0.0674772, 0.0903832);

    float h13 = max(0.0, 0.2737229 + dot(c, float3(-7.6579404, -0.0605621, 0.0917635)));
    rgb += h13 * float3(0.8107759, 0.0513009, -0.0689548);

    float h14 = max(0.0, -0.5286661 + dot(c, float3(1.7521223, 2.2740808, 0.6740580)));
    rgb += h14 * float3(-0.0910447, 0.0987217, -0.0352297);

    float h15 = max(0.0, -0.0743285 + dot(c, float3(4.1528034, -0.0005895, 0.0491316)));
    rgb += h15 * float3(1.3558975, -0.0600876, 0.0716275);

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

    half3 color = half3(mixPigments(l0) + r0);
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
