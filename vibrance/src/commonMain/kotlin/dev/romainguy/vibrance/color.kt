@file:Suppress("NOTHING_TO_INLINE")

package dev.romainguy.vibrance

import kotlin.math.pow

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
