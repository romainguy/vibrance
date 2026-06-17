@file:Suppress("EXPECT_ACTUAL_CLASSIFIERS_ARE_IN_BETA_WARNING")

package dev.romainguy.vibrance.compose

import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.ContentDrawScope
import androidx.compose.ui.node.DrawModifierNode

internal actual class PigmentsGradientNode actual constructor(
    type: GradientType
) : DrawModifierNode, Modifier.Node() {
    actual override fun ContentDrawScope.draw() {
    }

    actual fun updateLinearOffsets(startOffset: Offset, endOffset: Offset) {
    }

    actual fun updateColors(startColor: Color, endColor: Color) {
    }

    actual fun updateCircle(centerOffset: Offset, radius: Float) {
    }
}
