package dev.romainguy.vibrance.compose

import android.graphics.RuntimeShader
import androidx.annotation.RequiresApi
import androidx.compose.ui.graphics.drawscope.ContentDrawScope

@RequiresApi(33)
internal fun ContentDrawScope.updatePigmentsMixUniform(shader: RuntimeShader) {
    shader.setFloatUniform(UniformResolution, 1.0f / size.width, 1.0f / size.height)
}
