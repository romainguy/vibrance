@file:Suppress("NOTHING_TO_INLINE")

package dev.romainguy.vibrance

internal inline fun Float.fastCoerceIn(minimumValue: Float, maximumValue: Float) =
    this.fastCoerceAtLeast(minimumValue).fastCoerceAtMost(maximumValue)

internal inline fun Float.fastCoerceAtLeast(minimumValue: Float): Float {
    return if (this < minimumValue) minimumValue else this
}

internal inline fun Float.fastCoerceAtMost(maximumValue: Float): Float {
    return if (this > maximumValue) maximumValue else this
}
