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
    float3 q = c / (c + float3(0.0332745, 0.1608746, 0.1454633));

    float3 rgb = float3(-0.0129390, 0.5305084, 0.2569463)
        + c.x * float3(-0.2120745, -0.2843616, -0.1744376)
        + c.y * float3(0.1423155, 0.0290159, 0.1079934)
        + c.z * float3(-0.0327026, 0.0523934, -0.1422383)
        + q.x * float3(-0.2147055, 0.0007384, 0.4219884)
        + q.y * float3(0.0014953, -0.0022861, 0.1455865)
        + q.z * float3(0.2047531, 0.1423233, -0.5431989);

    float h0 = max(
        0.0,
        0.6575376
            + dot(c, float3(-0.1624863, -0.6449268, -0.0052772))
            + dot(q, float3(-0.9485637, 0.8710425, 0.0061951))
    );
    rgb += h0 * float3(0.0004355, -0.3014401, 0.0294964);

    float h1 = max(
        0.0,
        -0.0042518
            + dot(c, float3(-0.7684307, -0.7733518, 0.0044685))
            + dot(q, float3(1.2524637, -0.4744847, -0.0076850))
    );
    rgb += h1 * float3(-0.0499180, -0.0992915, -0.0012425);

    float h2 = max(
        0.0,
        -0.1788690
            + dot(c, float3(-10.2090120, 0.3642335, 0.3557712))
            + dot(q, float3(0.9909798, -0.2126132, 0.0214562))
    );
    rgb += h2 * float3(0.1223768, -0.0077781, 0.0060544);

    float h3 = max(
        0.0,
        -0.4557841
            + dot(c, float3(1.7087454, -1.5985008, -0.0139364))
            + dot(q, float3(1.1168557, -0.8197804, 0.0737546))
    );
    rgb += h3 * float3(0.0173154, -0.1492429, 0.0216028);

    float h4 = max(
        0.0,
        0.4302132
            + dot(c, float3(-0.3540720, -0.3396386, -0.5612465))
            + dot(q, float3(0.3263996, -0.1404756, -0.3299613))
    );
    rgb += h4 * float3(-0.1025737, 0.0235529, 0.0308535);

    float h5 = max(
        0.0,
        0.3587933
            + dot(c, float3(-0.5608867, 0.2945967, 0.3569975))
            + dot(q, float3(-0.6758091, -0.7032198, -0.2544450))
    );
    rgb += h5 * float3(0.1576087, 0.0427019, 0.0064689);

    float h6 = max(
        0.0,
        -0.6312193
            + dot(c, float3(1.4375213, 1.9843206, -0.6810503))
            + dot(q, float3(0.1351266, -0.0018242, 1.5063683))
    );
    rgb += h6 * float3(-0.1429056, -0.2263351, -0.1760660);

    float h7 = max(
        0.0,
        1.6864694
            + dot(c, float3(0.8341602, 0.0076333, -0.7323461))
            + dot(q, float3(-1.2207905, -0.9399308, 0.2519864))
    );
    rgb += h7 * float3(0.3703996, 0.3478388, 0.2377814);

    float h8 = max(
        0.0,
        -0.3494144
            + dot(c, float3(0.1696419, 0.5067902, 0.9021403))
            + dot(q, float3(0.0778709, 0.3719376, -1.2122540))
    );
    rgb += h8 * float3(-0.0058903, 0.0035372, -0.1794676);

    float h9 = max(
        0.0,
        0.2770979
            + dot(c, float3(0.1162788, -1.1381509, 0.5613435))
            + dot(q, float3(0.3927891, 0.1347073, -1.0353149))
    );
    rgb += h9 * float3(-0.0514020, -0.0051886, 0.0303437);

    float h10 = max(
        0.0,
        -0.5527982
            + dot(c, float3(1.4122236, -2.0394118, 0.0352515))
            + dot(q, float3(-0.2328004, 1.5217073, 0.0426745))
    );
    rgb += h10 * float3(-0.0399628, 0.1283439, -0.0257132);

    float h11 = max(
        0.0,
        -1.0271721
            + dot(c, float3(1.2173704, 1.3166165, 0.8916770))
            + dot(q, float3(0.0843863, 0.0612218, 0.9171246))
    );
    rgb += h11 * float3(-0.0063686, 0.0218381, 0.0754636);

    float h12 = max(
        0.0,
        1.1078291
            + dot(c, float3(0.2253718, -0.1754768, 0.4863618))
            + dot(q, float3(-1.0067890, 1.2782205, -0.6253537))
    );
    rgb += h12 * float3(0.2591344, -0.1280557, 0.0653370);

    float h13 = max(
        0.0,
        0.0454005
            + dot(c, float3(-0.3138996, -0.2991522, -0.4458051))
            + dot(q, float3(-0.1513797, -0.6885449, 1.2212912))
    );
    rgb += h13 * float3(0.0546763, -0.0083258, -0.0980978);

    float h14 = max(
        0.0,
        -0.6238379
            + dot(c, float3(1.1830186, 0.0061143, -0.3207612))
            + dot(q, float3(0.3984092, 0.7408799, 0.2368786))
    );
    rgb += h14 * float3(0.1118720, 0.0648990, -0.0045212);

    float h15 = max(
        0.0,
        0.5407425
            + dot(c, float3(-1.2993785, -1.5102947, 0.4130842))
            + dot(q, float3(-0.0953034, -0.0718980, -1.2532613))
    );
    rgb += h15 * float3(0.1177613, 0.3038324, 0.3250189);

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
