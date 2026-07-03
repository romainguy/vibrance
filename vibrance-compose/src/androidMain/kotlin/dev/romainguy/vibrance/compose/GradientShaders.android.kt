@file:Suppress("FunctionName")

package dev.romainguy.vibrance.compose

import androidx.annotation.RequiresApi
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shader
import androidx.compose.ui.graphics.TileMode
import dev.romainguy.vibrance.Vibrance
import kotlin.math.PI

@RequiresApi(33)
actual fun LinearPigmentsGradientShader(
    startColor: Color,
    endColor: Color,
    startOffset: Offset,
    endOffset: Offset,
    tileMode: TileMode
): Shader {
    val shader = assembleGradientShader(GradientType.Directional)

    shader.setOffsetUniform(UniformPosition1, startOffset)
    shader.setOffsetUniform(UniformPosition2, endOffset)
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
    val shader = assembleGradientShader(GradientType.Radial)

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

@RequiresApi(33)
actual fun SweepPigmentsGradientShader(
    startColor: Color,
    endColor: Color,
    centerOffset: Offset,
    angle: Float
): Shader {
    val shader = assembleGradientShader(GradientType.Sweep)

    shader.setFloatUniform(
        UniformCenterAngle,
        centerOffset.x,
        centerOffset.y,
        angle * (PI / 180.0).toFloat()
    )
    shader.setPigmentsUniforms(
        Vibrance(),
        startColor,
        endColor,
        FloatArray(6),
        FloatArray(6)
    )

    return shader
}
