@file:Suppress("NOTHING_TO_INLINE")

package dev.romainguy.vibrance

import kotlin.math.pow

internal const val RemainderPad = 0.01f
internal const val RemainderScale = 1.0f - 2.0f * RemainderPad

internal inline fun oetfSrgb(x: Float): Float {
    // Branchless
    val hi = 1.055f * x.pow(1.0f / 2.4f) - 0.055f
    val lo = 12.92f * x
    return if (x > 0.0031308f) hi else lo
}

internal inline fun eotfSrgb(x: Float): Float {
    // Branchless
    val hi = ((x + 0.055f) / 1.055f).pow(2.4f)
    val lo = (1.0f / 12.92f) * x
    return if (x > 0.04045f) hi else lo
}

internal inline fun encodeRemainder(targetLinear: Float, mixedLinear: Float): Float {
    val mp = RemainderPad + RemainderScale * mixedLinear
    val num = targetLinear * (1.0f - mp)
    return num / (num + (1.0f - targetLinear) * mp)
}

internal inline fun applyRemainder(mixedLinear: Float, remainder: Float): Float {
    val b = remainder.fastCoerceIn(0.0f, 1.0f)
    val mp = RemainderPad + RemainderScale * mixedLinear
    val num = mp * b
    return (num / (num + (1.0f - mp) * (1.0f - b))).fastCoerceIn(0.0f, 1.0f)
}
