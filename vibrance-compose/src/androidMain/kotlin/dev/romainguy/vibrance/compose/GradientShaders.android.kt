@file:Suppress("FunctionName")

package dev.romainguy.vibrance.compose

import android.graphics.RuntimeShader
import androidx.annotation.RequiresApi
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shader
import androidx.compose.ui.graphics.TileMode
import dev.romainguy.vibrance.Vibrance

@RequiresApi(33)
actual fun LinearPigmentsGradientShader(
    startColor: Color,
    endColor: Color,
    startOffset: Offset,
    endOffset: Offset,
    tileMode: TileMode
): Shader {
    val shader = RuntimeShader(
        uniformsSource(GradientType.Directional) +
        PigmentsMixShaderSource +
        mixSource(interpolator(GradientType.Directional), tileMode(GradientType.Directional))
    )

    shader.setFloatUniform(UniformPosition1, startOffset.x, startOffset.y)
    shader.setFloatUniform(UniformPosition2, endOffset.x, endOffset.y)
    shader.setIntUniform(UniformTileMode, tileMode.toInt())
    shader.setPigmentsUniforms(
        Vibrance(),
        startColor,
        endColor,
        FloatArray(6),
        FloatArray(6)
    )

    return shader
}

@RequiresApi(33)
actual fun RadialPigmentsGradientShader(
    startColor: Color,
    endColor: Color,
    centerOffset: Offset,
    radius: Float,
    tileMode: TileMode
): Shader {
    val shader = RuntimeShader(
        uniformsSource(GradientType.Radial) +
        PigmentsMixShaderSource +
        mixSource(interpolator(GradientType.Radial), tileMode(GradientType.Radial))
    )

    shader.setFloatUniform(UniformCenterRadius,
        centerOffset.x,
        centerOffset.y,
        1.0f / radius
    )
    shader.setIntUniform(UniformTileMode, tileMode.toInt())
    shader.setPigmentsUniforms(
        Vibrance(),
        startColor,
        endColor,
        FloatArray(6),
        FloatArray(6)
    )

    return shader
}
