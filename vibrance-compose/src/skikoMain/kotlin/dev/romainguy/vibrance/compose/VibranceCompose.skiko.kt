@file:Suppress("EXPECT_ACTUAL_CLASSIFIERS_ARE_IN_BETA_WARNING")

package dev.romainguy.vibrance.compose

import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.drawscope.ContentDrawScope
import androidx.compose.ui.node.DrawModifierNode

internal actual class PaintGradientNode actual constructor(orientation: GradientOrientation) : DrawModifierNode, Modifier.Node() {
    actual val startLatentColor = FloatArray(6)
    actual val endLatentColor = FloatArray(6)

    actual override fun ContentDrawScope.draw() {
    }
}
