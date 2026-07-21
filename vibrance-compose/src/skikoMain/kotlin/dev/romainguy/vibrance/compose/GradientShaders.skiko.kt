@file:Suppress("FunctionName")

package dev.romainguy.vibrance.compose

import androidx.annotation.RequiresApi
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shader
import androidx.compose.ui.graphics.TileMode
import androidx.compose.ui.graphics.asComposeShader
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

    shader.uniform(UniformPosition1, startOffset)
    shader.uniform(UniformPosition2, endOffset)
    shader.uniform(UniformTileMode, tileMode.toInt())
    shader.uniforms(
        Vibrance(),
        startColor,
        endColor,
        FloatArray(6),
        FloatArray(6)
    )

    return shader.makeShader().asComposeShader()
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

    shader.uniform(UniformCenterRadius,
        centerOffset.x,
        centerOffset.y,
        1.0f / radius
    )
    shader.uniform(UniformTileMode, tileMode.toInt())
    shader.uniforms(
        Vibrance(),
        startColor,
        endColor,
        FloatArray(6),
        FloatArray(6)
    )

    return shader.makeShader().asComposeShader()
}

@RequiresApi(33)
actual fun SweepPigmentsGradientShader(
    startColor: Color,
    endColor: Color,
    centerOffset: Offset,
    angle: Float
): Shader {
    val shader = assembleGradientShader(GradientType.Sweep)

    shader.uniform(
        UniformCenterAngle,
        centerOffset.x,
        centerOffset.y,
        angle * (PI / 180.0).toFloat()
    )
    shader.uniforms(
        Vibrance(),
        startColor,
        endColor,
        FloatArray(6),
        FloatArray(6)
    )

    return shader.makeShader().asComposeShader()
}
