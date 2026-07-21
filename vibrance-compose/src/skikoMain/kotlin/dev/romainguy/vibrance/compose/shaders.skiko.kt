@file:Suppress("NOTHING_TO_INLINE")

package dev.romainguy.vibrance.compose

import androidx.annotation.RequiresApi
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.colorspace.ColorSpaces
import dev.romainguy.vibrance.Vibrance
import org.jetbrains.skia.RuntimeEffect
import org.jetbrains.skia.RuntimeShaderBuilder

internal fun assembleGradientShader(type: GradientType): RuntimeShaderBuilder {
    val effect = RuntimeEffect.makeForShader(assembleGradientShaderSource(type))
    return RuntimeShaderBuilder(effect)
}

@RequiresApi(33)
internal inline fun RuntimeShaderBuilder.uniform(uniform: String, offset: Offset) {
    uniform(uniform, offset.x, offset.y)
}

@RequiresApi(33)
internal fun RuntimeShaderBuilder.uniforms(
    vibrance: Vibrance,
    startColor: Color,
    endColor: Color,
    startLatentColor: FloatArray,
    endLatentColor: FloatArray
) {
    val startSrgb = startColor.convert(ColorSpaces.Srgb)
    vibrance.colorToLatentColor(
        startSrgb.red,
        startSrgb.green,
        startSrgb.blue,
        startLatentColor
    )
    uniform(
        UniformLatent1,
        startLatentColor[0],
        startLatentColor[1],
        startLatentColor[2]
    )
    uniform(
        UniformRemainders1,
        startLatentColor[3],
        startLatentColor[4],
        startLatentColor[5]
    )

    val endSrgb = endColor.convert(ColorSpaces.Srgb)
    vibrance.colorToLatentColor(endSrgb.red, endSrgb.green, endSrgb.blue, endLatentColor)
    uniform(
        UniformLatent2,
        endLatentColor[0],
        endLatentColor[1],
        endLatentColor[2]
    )
    uniform(
        UniformRemainders2,
        endLatentColor[3],
        endLatentColor[4],
        endLatentColor[5]
    )
}
