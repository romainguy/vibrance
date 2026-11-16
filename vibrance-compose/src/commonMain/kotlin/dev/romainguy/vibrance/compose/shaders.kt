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
    float3 q = c / (c + float3(0.0310625, 0.2191592, 0.2235622));

    float3 rgb = float3(0.1138980, 0.4692093, -0.0526232)
        + c.x * float3(0.2006903, -0.7184518, -0.0812431)
        + c.y * float3(0.2850100, -0.2503175, -0.0933264)
        + c.z * float3(-0.0223676, -0.0802540, -0.0236277)
        + q.x * float3(0.0238455, -0.1460137, 0.1413919)
        + q.y * float3(0.0664495, -0.1413032, 0.2800236)
        + q.z * float3(0.2763469, 0.2349665, -0.4376401);

    float z0 = 0.1252622
        + dot(c, float3(-0.5201054, -0.0911538, 0.0355596))
        + dot(q, float3(0.7600455, -0.1648567, -0.3392197));
    float h0 = z0 + sqrt(z0 * z0 + 0.0436730);
    rgb += h0 * float3(-0.2077531, 0.0062347, 0.0287711);

    float z1 = -0.7411862
        + dot(c, float3(0.0598556, 0.1075494, 0.2917133))
        + dot(q, float3(0.9456514, 0.0729930, -0.0130125));
    float h1 = z1 + sqrt(z1 * z1 + 0.0590666);
    rgb += h1 * float3(-0.2129128, -0.0746477, -0.0058008);

    float z2 = 0.9973216
        + dot(c, float3(0.0789433, 0.1078733, -0.2276448))
        + dot(q, float3(-0.2921924, -0.1357998, -0.9091156));
    float h2 = z2 + sqrt(z2 * z2 + 0.0331533);
    rgb += h2 * float3(0.0314042, 0.1260467, 0.0422853);

    float z3 = 0.6439704
        + dot(c, float3(-0.6540558, 0.0730592, 0.0715327))
        + dot(q, float3(-0.7354834, -0.1441095, -0.0072820));
    float h3 = z3 + sqrt(z3 * z3 + 0.0334003);
    rgb += h3 * float3(0.2236924, 0.1058263, -0.0406763);

    float z4 = 0.1893468
        + dot(c, float3(-0.5930237, -0.3712729, 0.1466069))
        + dot(q, float3(-0.6289639, -0.3043411, 0.0276856));
    float h4 = z4 + sqrt(z4 * z4 + 0.1507604);
    rgb += h4 * float3(0.2617889, 0.0970473, -0.0667461);

    float z5 = -0.2695661
        + dot(c, float3(-0.1543189, -0.1793479, -0.7816104))
        + dot(q, float3(0.0079201, 0.5748301, 0.0511160));
    float h5 = z5 + sqrt(z5 * z5 + 0.0313477);
    rgb += h5 * float3(-0.0675984, -0.0622383, 0.5946171);

    float z6 = -0.1413133
        + dot(c, float3(-0.3384386, 0.6572116, 0.6559677))
        + dot(q, float3(-0.1352028, 0.0270607, 0.0650126));
    float h6 = z6 + sqrt(z6 * z6 + 0.0809649);
    rgb += h6 * float3(-0.1797305, -0.0379612, -0.0192549);

    float z7 = 0.0664226
        + dot(c, float3(-0.8321691, -0.1382556, 0.0467029))
        + dot(q, float3(-0.0279708, -0.5254193, -0.0967015));
    float h7 = z7 + sqrt(z7 * z7 + 0.0459607);
    rgb += h7 * float3(0.3974901, 0.6498399, -0.0226307);

    float z8 = -0.0930780
        + dot(c, float3(0.6784285, 0.1864256, -0.1777748))
        + dot(q, float3(-0.1015761, 0.2995041, 0.6110286));
    float h8 = z8 + sqrt(z8 * z8 + 0.0200648);
    rgb += h8 * float3(-0.1684434, 0.0378935, 0.0907279);

    float z9 = -0.5700094
        + dot(c, float3(-0.5162911, -0.4263113, 0.2584335))
        + dot(q, float3(0.4651760, 0.4406392, -0.2726952));
    float h9 = z9 + sqrt(z9 * z9 + 0.0358842);
    rgb += h9 * float3(0.1909737, 0.0818795, 0.1061134);

    float z10 = 0.0675779
        + dot(c, float3(-0.1526181, -0.6554778, -0.6075161))
        + dot(q, float3(-0.0318142, 0.0631102, -0.4159156));
    float h10 = z10 + sqrt(z10 * z10 + 0.0400651);
    rgb += h10 * float3(-0.0182861, 0.0797339, 1.1742090);

    float z11 = 0.7879340
        + dot(c, float3(0.3684239, -0.3804242, 0.2696600))
        + dot(q, float3(-0.6801093, 0.2272401, -0.3641951));
    float h11 = z11 + sqrt(z11 * z11 + 0.0435383);
    rgb += h11 * float3(0.1932286, 0.0285034, 0.1023031);

    float z12 = 0.3761955
        + dot(c, float3(-0.2408114, -0.1430061, -0.0045201))
        + dot(q, float3(-0.6616894, 0.6942975, 0.0407043));
    float h12 = z12 + sqrt(z12 * z12 + 0.0724127);
    rgb += h12 * float3(0.0548208, -0.2967174, 0.0503791);

    float z13 = 0.1706484
        + dot(c, float3(-0.5852262, -0.4396836, -0.5350166))
        + dot(q, float3(-0.2896418, 0.2779544, 0.1295947));
    float h13 = z13 + sqrt(z13 * z13 + 0.0505045);
    rgb += h13 * float3(0.0398552, -0.0631965, -0.0983731);

    float z14 = -0.6701704
        + dot(c, float3(0.8382769, 0.1520069, -0.0277287))
        + dot(q, float3(0.3990448, 0.3300152, 0.0725957));
    float h14 = z14 + sqrt(z14 * z14 + 0.0893828);
    rgb += h14 * float3(-0.1088641, 0.2620537, -0.0669322);

    float z15 = 0.8076255
        + dot(c, float3(-0.1050001, -0.5659755, 0.1408194))
        + dot(q, float3(-0.0762831, -0.5195585, -0.6107836));
    float h15 = z15 + sqrt(z15 * z15 + 0.1055795);
    rgb += h15 * float3(-0.0088492, 0.0342153, 0.2892738);

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
