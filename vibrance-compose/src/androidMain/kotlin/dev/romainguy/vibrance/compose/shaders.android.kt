package dev.romainguy.vibrance.compose

import android.graphics.RuntimeShader
import androidx.compose.ui.graphics.drawscope.ContentDrawScope

internal fun ContentDrawScope.updatePigmentsMixUniform(
    shader: RuntimeShader,
    startLatentColor: FloatArray,
    endLatentColor: FloatArray
) {
    shader.setFloatUniform(UniformResolution, 1.0f / size.width, 1.0f / size.height)
    shader.setFloatUniform(UniformLatent1, startLatentColor[0], startLatentColor[1], startLatentColor[2])
    shader.setFloatUniform(UniformRemainders1, startLatentColor[3], startLatentColor[4], startLatentColor[5])
    shader.setFloatUniform(UniformLatent2, endLatentColor[0], endLatentColor[1], endLatentColor[2])
    shader.setFloatUniform(UniformRemainders2, endLatentColor[3], endLatentColor[4], endLatentColor[5])
}
