@file:Suppress("LocalVariableName", "DuplicatedCode")

package dev.romainguy.vibrance

import kotlin.math.cosh
import kotlin.math.max
import kotlin.math.pow
import kotlin.math.sinh
import kotlin.math.sqrt

class Vibrance {
    internal val model = PigmentsModel()
    internal val pigmentsBuffer = FloatArray(3)

    fun colorToLatentColor(
        r: Float,
        g: Float,
        b: Float,
        latentColor: FloatArray = FloatArray(6)
    ): FloatArray {
        val buffer = pigmentsBuffer
        model.predict(r, g, b, buffer)

        val latent0 = buffer[0]
        val latent1 = buffer[1]
        val latent2 = buffer[2]
        val latent3 = 1.0f - (buffer[0] + buffer[1] + buffer[2])

        pigmentsMix(latent0, latent1, latent2, latent3, buffer)

        latentColor[0] = latent0
        latentColor[1] = latent1
        latentColor[2] = latent2
        latentColor[3] = r - buffer[0]
        latentColor[4] = g - buffer[1]
        latentColor[5] = b - buffer[2]

        return latentColor
    }

    fun latentColorToColor(
        latentColor: FloatArray,
        color: FloatArray = FloatArray(3)
    ): FloatArray {
        val blue = latentColor[0]
        val magenta = latentColor[1]
        val yellow = latentColor[2]
        val white = 1.0f - (blue + magenta + yellow)

        pigmentsMix(blue, magenta, yellow, white, color)

        color[0] = (color[0] + latentColor[3]).fastCoerceIn(0.0f, 1.0f)
        color[1] = (color[1] + latentColor[4]).fastCoerceIn(0.0f, 1.0f)
        color[2] = (color[2] + latentColor[5]).fastCoerceIn(0.0f, 1.0f)

        return color
    }

    fun latentColorsMix(
        src: FloatArray,
        dst: FloatArray,
        t: Float,
        color: FloatArray = FloatArray(3)
    ): FloatArray {

        pigmentsMix(
            lerp(src[0], dst[0], t),
            lerp(src[1], dst[1], t),
            lerp(src[2], dst[2], t),
            lerp(1.0f - (src[0] + src[1] + src[2]), 1.0f - (dst[0] + dst[1] + dst[2]), t),
            color
        )

        color[0] = (color[0] + lerp(src[3], dst[3], t)).fastCoerceIn(0.0f, 1.0f)
        color[1] = (color[1] + lerp(src[4], dst[4], t)).fastCoerceIn(0.0f, 1.0f)
        color[2] = (color[2] + lerp(src[5], dst[5], t)).fastCoerceIn(0.0f, 1.0f)

        return color
    }

    fun colorsMix(
        srcR: Float,
        srcG: Float,
        srcB: Float,
        dstR: Float,
        dstG: Float,
        dstB: Float,
        t: Float,
        color: FloatArray = FloatArray(3)
    ): FloatArray {
        val pigments = pigmentsBuffer

        // Source
        model.predict(srcR, srcG, srcB, pigments)
        val srcPigment0 = pigments[0]
        val srcPigment1 = pigments[1]
        val srcPigment2 = pigments[2]
        val srcPigment3 = 1.0f - (srcPigment0 + srcPigment1 + srcPigment2)

        pigmentsMix(srcPigment0, srcPigment1, srcPigment2, srcPigment3, color)
        val srcRemainderR = srcR - color[0]
        val srcRemainderG = srcG - color[1]
        val srcRemainderB = srcB - color[2]

        // Destination
        model.predict(dstR, dstG, dstB, pigments)
        val dstPigment0 = pigments[0]
        val dstPigment1 = pigments[1]
        val dstPigment2 = pigments[2]
        val dstPigment3 = 1.0f - (dstPigment0 + dstPigment1 + dstPigment2)

        pigmentsMix(dstPigment0, dstPigment1, dstPigment2, dstPigment3, color)
        val dstRemainderR = dstR - color[0]
        val dstRemainderG = dstG - color[1]
        val dstRemainderB = dstB - color[2]

        pigmentsMix(
            lerp(srcPigment0, dstPigment0, t),
            lerp(srcPigment1, dstPigment1, t),
            lerp(srcPigment2, dstPigment2, t),
            lerp(srcPigment3, dstPigment3, t),
            color
        )

        color[0] = (color[0] + lerp(srcRemainderR, dstRemainderR, t)).fastCoerceIn(0.0f, 1.0f)
        color[1] = (color[1] + lerp(srcRemainderG, dstRemainderG, t)).fastCoerceIn(0.0f, 1.0f)
        color[2] = (color[2] + lerp(srcRemainderB, dstRemainderB, t)).fastCoerceIn(0.0f, 1.0f)

        return color
    }

    fun pigmentsMix(
        blue: Float,
        magenta: Float,
        yellow: Float,
        white: Float,
        color: FloatArray = FloatArray(3)
    ): FloatArray {
        val K0 = K[0]
        val K1 = K[1]
        val K2 = K[2]
        val K3 = K[3]

        val S0 = S[0]
        val S1 = S[1]
        val S2 = S[2]
        val S3 = S[3]

        val observer0 = NormalizedObserverD65[0]
        val observer1 = NormalizedObserverD65[1]
        val observer2 = NormalizedObserverD65[2]

        var X = 0.0f
        var Y = 0.0f
        var Z = 0.0f

        val kMix = blue * K0[0] + magenta * K1[0] + yellow * K2[0] + white * K3[0]
        // Apply a max to keep the division below safe
        val sMix = max(blue * S0[0] + magenta * S1[0] + yellow * S2[0] + white * S3[0], 1e-7f)
        val reflectance0 = reflectance(kMix, sMix)

        var previousReflectance0 = observer0[0] * reflectance0
        var previousReflectance1 = observer1[0] * reflectance0
        var previousReflectance2 = observer2[0] * reflectance0

        for (i in 1 until WaveLengthCount) {
            // Compute the Kubelka-Munk reflectance
            val kMix = blue * K0[i] + magenta * K1[i] + yellow * K2[i] + white * K3[i]
            val sMix = max(blue * S0[i] + magenta * S1[i] + yellow * S2[i] + white * S3[i], 1e-7f)
            val reflectance = reflectance(kMix, sMix)

            val reflectance0 = observer0[i] * reflectance
            val reflectance1 = observer1[i] * reflectance
            val reflectance2 = observer2[i] * reflectance

            // Integrate the reflectance over the CMF
            X += (previousReflectance0 + reflectance0) * WaveLengthHalfInterval
            Y += (previousReflectance1 + reflectance1) * WaveLengthHalfInterval
            Z += (previousReflectance2 + reflectance2) * WaveLengthHalfInterval

            previousReflectance0 = reflectance0
            previousReflectance1 = reflectance1
            previousReflectance2 = reflectance2
        }

        // Conversion to sRGB
        val linearR =  3.2406f * X - 1.5372f * Y - 0.4986f * Z
        val linearG = -0.9689f * X + 1.8758f * Y + 0.0415f * Z
        val linearB =  0.0557f * X - 0.2040f * Y + 1.0570f * Z

        color[0] = eotfSrgb(linearR).fastCoerceIn(0.0f, 1.0f)
        color[1] = eotfSrgb(linearG).fastCoerceIn(0.0f, 1.0f)
        color[2] = eotfSrgb(linearB).fastCoerceIn(0.0f, 1.0f)

        return color
    }
}

@Suppress("NOTHING_TO_INLINE")
internal inline fun eotfSrgb(x: Float): Float {
    // Branchless for the common case
    val hi = 1.055f * x.pow(1.0f / 2.4f) - 0.055f
    return if (x > 0.0031308f) hi else (12.92f * x)
}

internal fun reflectance(kMix: Float, sMix: Float): Float {
    val a = 1.0f + (kMix / sMix)
    val b = sqrt(a * a - 1.0f)
    val bSX = b * sMix * PaintThickness
    val rMix = substrate(a, b, bSX)

    // Saunderson correction
    val corrected = ((1.0f - SaundersonK1) * (1.0f - SaundersonK2) * rMix) / (1.0f - SaundersonK2 * rMix)
    val reflectance0 = corrected.fastCoerceIn(0.0f, 1.0f)
    return reflectance0
}

internal fun substrate(a: Float, b: Float, bSX: Float): Float {
    // Branchless version of the original computation, the case bSX > 20 is uncommon
    val c = cosh(bSX) / sinh(bSX)
    val n = 1.0f - CanvasReflectance * (a - b * c)
    val d = a - CanvasReflectance + b * c
    val s = (n / d).fastCoerceIn(0.0f, 1.0f)
    return if (bSX <= 20.0f) s else (a - b)
}

// Saunderson correction coefficients
internal const val SaundersonK1 = 0.030f
internal const val SaundersonK2 = 0.650f

// Substrate
internal const val PaintThickness = 1.0f
internal const val CanvasReflectance = 1.0f

// 380nm to 750nm
// Full interval is 10nm, we use 5nm to save a division by 2 during integration
internal const val WaveLengthHalfInterval = 5.0f
internal const val WaveLengthCount = 36

// Color matching functions for the CIE 1964 Standard Observer 10 degrees
// The data is normalized by CIE D65 illuminant
internal val NormalizedObserverD65: Array<FloatArray> = arrayOf(
    floatArrayOf(
        6.8819645E-7f, 1.1109411E-5f, 1.361099E-4f, 6.6720287E-4f, 0.0016443958f, 0.0023476507f,
        0.0034633481f, 0.003733153f, 0.0030649556f, 0.001933823f, 8.032275E-4f, 1.5145089E-4f,
        3.5915204E-5f, 3.4760646E-4f, 0.0010619703f, 0.0021919026f, 0.0033855967f, 0.004744538f,
        0.0060696322f, 0.0072850776f, 0.008360897f, 0.008537528f, 0.008707033f, 0.007946548f,
        0.0064632786f, 0.004641288f, 0.0031088863f, 0.0018481549f, 0.0010533002f, 5.754401E-4f,
        2.7524037E-4f, 1.1965964E-4f, 5.9024595E-5f, 2.9134535E-5f, 1.1531969E-5f, 6.285492E-6f
    ),
    floatArrayOf(
        7.312087E-8f, 1.1899581E-6f, 1.4273377E-5f, 6.8943875E-5f, 1.7201294E-4f, 2.8854082E-4f,
        5.60269E-4f, 9.008663E-4f, 0.0012999189f, 0.0018307348f, 0.0025300863f, 0.003175983f,
        0.004336714f, 0.0056294436f, 0.006870235f, 0.008111841f, 0.008644229f, 0.008881119f,
        0.008583779f, 0.007922644f, 0.007163631f, 0.005933839f, 0.005099864f, 0.0040713875f,
        0.0030045104f, 0.0020321847f, 0.0012954299f, 7.413379E-4f, 4.1616848E-4f, 2.2518792E-4f,
        1.0716256E-4f, 4.6499303E-5f, 2.2914634E-5f, 1.1313388E-5f, 4.4855383E-6f, 2.4480337E-6f
    ),
    floatArrayOf(
        3.0323656E-6f, 4.9300954E-5f, 6.126085E-4f, 0.0030658292f, 0.00782057f, 0.011589679f,
        0.017755464f, 0.020088626f, 0.017697517f, 0.0130250165f, 0.0077035786f, 0.0038888566f,
        0.0020564843f, 0.0010395627f, 5.4753036E-4f, 2.8223326E-4f, 1.2288976E-4f, 3.5712135E-5f,
        0.0f, 0.0f, 0.0f, 0.0f, 0.0f, 0.0f,
        0.0f, 0.0f, 0.0f, 0.0f, 0.0f, 0.0f,
        0.0f, 0.0f, 0.0f, 0.0f, 0.0f, 0.0f
    )
)

// Reflectance and absorption values for our chosen pigments:
// - Phthalo Blue (Green Shade), PB 15:4, Copper Phthalocyanine
// - Quinacridone Magenta, PR 122, Quinacridone
// - Hansa Yellow, PY 74, Benzimidazolone Yellow
// - Titanium White, PW 6, Titanium Dioxide
internal val K: Array<FloatArray> = arrayOf(
    floatArrayOf(
        0.0019659363f, 0.0048770513f, 0.0057651713f, 0.0047177924f, 0.0076192375f, 0.008449691f,
        0.0037789664f, 0.003117622f, 0.01255631f, 0.25220764f, 1.7147465f, 3.3047051f,
        0.09254444f, 1.2600852f, 0.9965501f, 0.8476821f, 0.014088044f, 0.18432768f,
        0.17133576f, 1.8569661f, 1.735915f, 5.2738748f, 5.340888f, 5.0676703f,
        4.874906f, 4.8963413f, 4.924056f, 4.962061f, 4.996523f, 5.014181f,
        5.008892f, 4.990627f, 4.9660187f, 4.95567f, 4.9976273f, 5.1148553f
    ), floatArrayOf(
        15.533135f, 15.093025f, 0.54865277f, 0.48818272f, 0.33107767f, 0.16209376f,
        0.1175143f, 0.0743477f, 0.046749063f, 0.04110686f, 1.1244167f, 0.85231996f,
        0.36279255f, 0.9259289f, 0.98298794f, 1.0551807f, 0.6349471f, 0.88229674f,
        0.80462575f, 2.121417f, 0.60834634f, 0.32520705f, 0.17713094f, 0.05894937f,
        0.0040933695f, 0.0011830134f, 0.0005217633f, 0.00030394574f, 0.00021911396f, 0.00017553274f,
        0.00014852047f, 0.00012933816f, 0.000110391244f, 9.86885e-05f, 9.039383e-05f, 9.7950564e-05f
    ), floatArrayOf(
        0.05271012f, 0.23101838f, 2.0309842f, 1.9540792f, 1.84821f, 1.6539638f,
        1.4421291f, 1.2942213f, 1.1389803f, 1.226043f, 0.028488645f, 0.040391997f,
        0.42600614f, 0.0030010287f, 0.00056558463f, 0.00013242908f, 0.11918393f, 4.4780165e-05f,
        4.4770943e-05f, 4.3404947e-05f, 4.2664546e-05f, 4.2449035e-05f, 4.2183896e-05f, 4.1840736e-05f,
        4.1672803e-05f, 4.1691008e-05f, 4.155105e-05f, 4.1468e-05f, 4.1548436e-05f, 4.1745832e-05f,
        4.151944e-05f, 4.1647178e-05f, 4.1751806e-05f, 4.174476e-05f, 4.276671e-05f, 3.912446e-05f
    ), floatArrayOf(
        0.015569399f, 0.017047575f, 0.0012355325f, 0.00018317996f, 4.4369775e-05f, 2.1720789e-05f,
        6.0512693e-06f, 3.3716376e-06f, 3.499942e-06f, 5.7328225e-06f, 5.8363275e-06f, 7.894262e-06f,
        8.810937e-06f, 9.487988e-06f, 1.2008796e-05f, 1.1271638e-05f, 1.2068601e-05f, 1.4148552e-05f,
        1.7527622e-05f, 1.8552942e-05f, 2.0156627e-05f, 2.384693e-05f, 2.5622208e-05f, 2.9560308e-05f,
        2.9935407e-05f, 2.9207673e-05f, 2.8894137e-05f, 2.6410977e-05f, 2.4609802e-05f, 2.443485e-05f,
        2.602446e-05f, 2.8272381e-05f, 2.6721205e-05f, 2.7361117e-05f, 2.5204654e-05f, 2.309898e-05f
    )
)
internal val S: Array<FloatArray> = arrayOf(
    floatArrayOf(
        0.012499469f, 0.013949241f, 0.06284784f, 0.044135544f, 0.44064686f, 0.87215084f,
        0.055984594f, 0.011936391f, 0.003330351f, 0.00076315674f, 0.0004419315f, 0.0005232871f,
        0.0005404724f, 0.0005016399f, 0.0010822936f, 0.005655833f, 0.008253238f, 1.4944917f,
        1.3979745f, 7.2314963f, 0.07693028f, 0.18476552f, 0.26578465f, 0.4798841f,
        0.6206806f, 0.6418123f, 0.64859724f, 0.64319175f, 0.63182175f, 0.6196483f,
        0.61808926f, 0.6270253f, 0.6492962f, 0.68622994f, 0.7206482f, 0.7369716f
    ), floatArrayOf(
        0.014521918f, 0.002797022f, 0.0031638937f, 0.0023640525f, 0.0028233246f, 0.0027643922f,
        0.0024747793f, 0.002668241f, 0.0028914565f, 0.0046377606f, 0.10474872f, 0.06219622f,
        0.009694939f, 0.0030291153f, 0.0030120618f, 0.0032750359f, 0.0033046515f, 0.003105635f,
        0.0036489354f, 0.0032346032f, 0.0020083333f, 0.0011353465f, 0.00072179537f, 0.0005981335f,
        0.00066193263f, 0.0008195124f, 0.0010038526f, 0.0011781919f, 0.0013089584f, 0.0014251935f,
        0.0015220342f, 0.0016196731f, 0.001693704f, 0.0018026283f, 0.0018711246f, 0.0019837858f
    ), floatArrayOf(
        0.008162836f, 0.010610919f, 0.009368832f, 0.009371076f, 0.011263972f, 0.012733364f,
        0.012625232f, 0.013064038f, 0.013852373f, 0.017175924f, 0.04082779f, 0.5158921f,
        33.21736f, 0.037510026f, 0.018435005f, 0.016199615f, 1.9196539f, 0.016128372f,
        0.013848377f, 0.0041575055f, 0.0021870916f, 0.0016706976f, 0.0015092444f, 0.0014486845f,
        0.0014438895f, 0.001382061f, 0.0013591f, 0.0014612363f, 0.0013503174f, 0.0013604831f,
        0.0014349557f, 0.0014304407f, 0.0014084975f, 0.0013473466f, 0.0012845973f, 0.0012841644f
    ), floatArrayOf(
        0.1580973f, 199.27026f, 0.07346591f, 0.0656487f, 0.038088724f, 0.03738751f,
        0.04299333f, 0.04640103f, 0.051690243f, 0.14916392f, 10.422833f, 0.35595885f,
        148.82582f, 0.03339844f, 0.061703496f, 0.093221225f, 0.0716193f, 0.102307454f,
        0.46249068f, 0.35820556f, 9.107367f, 0.31323466f, 0.17420216f, 0.11436399f,
        0.078667305f, 0.06529318f, 0.058342364f, 0.056994583f, 0.061237007f, 0.061765656f,
        0.057227395f, 0.051694162f, 0.047576685f, 0.045789514f, 0.045142405f, 0.04810665f
    )
)

//x^3*p0 + y^3*p1 + z^3*p2 + w^3*p3 + x^2*y*p4 + y^2*x*p5 + x^2*z*p6 + z^2*x*p7 + x^2*w*p8 + w^2*x*p9 + y^2*z*p10 + z^2*y*p11 + y^2*w*p12 + w^2*y*p13 + z^2*w*p14 + w^2*z*p15 + x*y*z*p16 + x*y*w*p17 + x*z*w*p18 + y*z*w*p19