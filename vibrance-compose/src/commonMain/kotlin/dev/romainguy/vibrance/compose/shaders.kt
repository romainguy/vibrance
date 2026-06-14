package dev.romainguy.vibrance.compose

internal const val UniformResolution = "uniform_resolution"
internal const val UniformLatent1 = "uniform_l1"
internal const val UniformRemainders1 = "uniform_r1"
internal const val UniformLatent2 = "uniform_l2"
internal const val UniformRemainders2 = "uniform_r2"

// language=agsl
internal const val PigmentsMixShaderSource = """
uniform vec2 $UniformResolution;
uniform vec3 $UniformLatent1;
uniform vec3 $UniformRemainders1;
uniform vec3 $UniformLatent2;
uniform vec3 $UniformRemainders2;

// n must be normalized in [0..1] (e.g. texture coordinates)
float triangleNoise(vec2 n) {
    // triangle noise, in [-1.0..1.0[ range
    n  = fract(n * vec2(5.3987, 5.4421));
    n += dot(n.yx, n.xy + vec2(21.5351, 14.3137));

    float xy = n.x * n.y;
    // compute in [0..2[ and remap to [-1.0..1.0[
    return fract(xy * 95.4307) + fract(xy * 75.04961) - 1.0;
}

half3 Dither_TriangleNoise(vec2 uv, half3 rgb) {
    // Gjøl 2016, "Banding in Games: A Noisy Rant"
    float noise = triangleNoise(uv);
    // noise is in [-1..1[
    return rgb + half3(noise / 255.0);
}

vec3 mixPigments(vec4 c) {
    vec3 rgb = vec3(-0.0249582, 0.3584879, 0.0244421);

    float h1 = max(0.0, 0.0098267 + dot(c, vec4(0.1174974, 0.5078091, -0.2443916, -0.3047154)));
    rgb += h1 * vec3(-0.0341629, 0.5760666, -0.0325691);

    float h2 = max(0.0, -0.6286458 + dot(c, vec4(-2.8839049, 0.7584528, 0.7916569, 0.7818422)));
    rgb += h2 * vec3(2.7300307, 0.1375640, -0.0742932);

    float h3 = max(0.0, 0.1541230 + dot(c, vec4(0.2867913, 0.0568774, -0.5446743, 0.1930918)));
    rgb += h3 * vec3(-0.1012921, 0.0580865, 0.5416284);

    float h5 = max(0.0, 0.0747424 + dot(c, vec4(-1.7088713, 0.1860320, 0.2180080, 0.2195648)));
    rgb += h5 * vec3(1.1088474, 0.0263522, -0.0335531);

    float h6 = max(0.0, -0.2055153 + dot(c, vec4(-0.4361817, -0.8967318, 0.4045247, 0.4025162)));
    rgb += h6 * vec3(0.1815542, 0.9910546, -0.1118827);

    float h9 = max(0.0, 0.3792505 + dot(c, vec4(0.0286469, 0.4794231, -0.3951822, 0.0329820)));
    rgb += h9 * vec3(0.1625491, -0.6810130, 0.2960019);

    float h10 = max(0.0, -0.1452454 + dot(c, vec4(0.2560401, 0.1701091, -1.2982900, 0.3221915)));
    rgb += h10 * vec3(-0.1341101, 0.0278039, 1.2155828);

    float h11 = max(0.0, 0.2256926 + dot(c, vec4(-0.1793450, -0.3194858, -0.0576552, 0.5597214)));
    rgb += h11 * vec3(0.0951412, 0.6306976, 0.1533901);

    float h12 = max(0.0, 0.0271525 + dot(c, vec4(0.2065663, 0.1657660, -0.9326340, 0.2456872)));
    rgb += h12 * vec3(0.0214799, 0.0282143, 0.8276884);

    return clamp(rgb, 0.0, 1.0);
}
"""
