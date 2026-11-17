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

const float RemainderPad = 0.01;
const float RemainderScale = 0.98;

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
    float3 q = c / (c + float3(0.0398001, 0.2109625, 0.1513699));

    float3 rgb = float3(0.0942057, 0.3998840, 0.0436599)
        + c.x * float3(0.1703877, -0.6139438, 0.1173228)
        + c.y * float3(0.2319316, -0.0244454, 0.0383668)
        + c.z * float3(-0.1716009, -0.0807311, -0.0129965)
        + q.x * float3(0.0394091, -0.1587674, 0.2922518)
        + q.y * float3(-0.0489831, -0.0588993, 0.3375300)
        + q.z * float3(0.1606205, 0.2137057, -0.6318032);

    float4 u0 = float4(0.7617934, -0.3669254, 1.3967189, 1.9257392)
        + c.x * float4(-2.0405555, 0.5272365, -0.9044641, 0.4095860)
        + c.y * float4(0.3112379, 0.0513481, -0.0164963, 0.2479234)
        + c.z * float4(-0.8018587, 0.1808509, -0.0648650, 0.4018383)
        + q.x * float4(2.3255994, 0.7708003, -0.4116434, -1.7728392)
        + q.y * float4(-0.4363129, 0.0586200, -0.2583996, -0.2889820)
        + q.z * float4(0.0858946, 0.2390250, -0.1407897, -0.3477753);
    float4 s0 = saturate(u0);
    float4 s0_2 = s0 * s0;
    float4 h0 = max(u0 - float4(1.0), float4(0.0)) + s0_2 * (s0 - 0.5 * s0_2);
    rgb += h0.x * float3(-0.0846060, -0.0050965, 0.0108343)
        + h0.y * float3(-0.7024046, -0.0563102, 0.0369187)
        + h0.z * float3(0.0530008, 0.2685580, 0.2063591)
        + h0.w * float3(0.2273123, 0.0223245, -0.0063244);

    float4 u1 = float4(0.5804944, -0.0325220, 0.1023628, 0.4741918)
        + c.x * float4(0.1197487, 0.3890758, -0.0347458, -1.0170648)
        + c.y * float4(-0.3406990, -0.4171871, 0.6043723, 0.1637024)
        + c.z * float4(0.1528339, 0.0243509, 0.0326402, -0.0065886)
        + q.x * float4(-0.7473584, 0.3468475, -0.0679549, -0.0713866)
        + q.y * float4(-0.1581982, 0.1799036, 0.2249385, -0.6906144)
        + q.z * float4(-0.0572037, -0.3129576, 0.2909658, -0.0780988);
    float4 s1 = saturate(u1);
    float4 s1_2 = s1 * s1;
    float4 h1 = max(u1 - float4(1.0), float4(0.0)) + s1_2 * (s1 - 0.5 * s1_2);
    rgb += h1.x * float3(0.9123240, 0.2034202, -0.1264191)
        + h1.y * float3(-0.3006504, -0.0019110, -0.5820184)
        + h1.z * float3(-0.3818003, -0.3901738, -0.0220673)
        + h1.w * float3(0.7496945, 1.4022825, 0.1176288);

    float4 u2 = float4(0.6980488, 0.0680660, 0.4834386, 3.3638372)
        + c.x * float4(0.4319992, 0.0858009, -0.5719878, 1.7292200)
        + c.y * float4(-0.0626281, 0.2646287, -1.1263524, -1.5350319)
        + c.z * float4(-0.6592367, 0.2600586, -0.2889980, -0.0993377)
        + q.x * float4(0.3713518, 0.0216613, -0.0203989, -1.8210709)
        + q.y * float4(-0.1318354, 0.3207402, -0.0477446, 0.7025958)
        + q.z * float4(-0.3573421, -0.4917397, -0.5009462, -1.3914435);
    float4 s2 = saturate(u2);
    float4 s2_2 = s2 * s2;
    float4 h2 = max(u2 - float4(1.0), float4(0.0)) + s2_2 * (s2 - 0.5 * s2_2);
    rgb += h2.x * float3(-0.3760870, 0.0907319, 0.0117416)
        + h2.y * float3(-0.0526490, 0.0008695, -1.1715037)
        + h2.z * float3(-0.4328068, 0.2370650, 0.9636616)
        + h2.w * float3(0.1149270, 0.0251914, 0.0850134);

    float4 u3 = float4(0.8389716, 1.1363839, -0.6866699, 1.1973850)
        + c.x * float4(-0.4684559, 1.8413706, 1.0747178, -0.0920046)
        + c.y * float4(-0.2579706, 1.1003418, 0.0307113, -0.8114011)
        + c.z * float4(0.0343448, -0.2236535, 0.0335598, 0.0703730)
        + q.x * float4(-0.6279241, -1.8478841, 0.9366120, -0.0336330)
        + q.y * float4(0.7005695, -0.3094627, 0.4623602, -0.4128532)
        + q.z * float4(-0.0209734, 1.6426561, 0.0181216, -0.3697207);
    float4 s3 = saturate(u3);
    float4 s3_2 = s3 * s3;
    float4 h3 = max(u3 - float4(1.0), float4(0.0)) + s3_2 * (s3 - 0.5 * s3_2);
    rgb += h3.x * float3(0.1540918, -0.5343750, 0.1404618)
        + h3.y * float3(0.0273948, -0.0074183, -0.0093523)
        + h3.z * float3(0.0695353, 0.3202189, -0.1638511)
        + h3.w * float3(0.0843518, 0.3336647, 0.5230504);

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
    float3 r0 = saturate(mix($UniformRemainders1, $UniformRemainders2, t));

    float3 mp = RemainderPad + RemainderScale * mixPigments(l0);
    float3 num = mp * r0;
    half3 color = half3(saturate(num / (num + (1.0 - mp) * (1.0 - r0))));
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
