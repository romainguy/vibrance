package dev.romainguy.vibrance.demo

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.toSize
import dev.romainguy.vibrance.compose.radialPigmentsGradient

@Composable
fun RadialGradients(modifier: Modifier) {
    Column(
        modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp)
    ) {
        Text(
            "Radial Gradients",
            style = MaterialTheme.typography.titleLarge
        )
        // Yellow to Green
        var patch0Size by remember { mutableFloatStateOf(0f) }
        Row(
            Modifier
                .fillMaxWidth()
                .weight(1.0f)
                .padding(0.dp, 16.dp, 0.dp, 8.dp)
                .onSizeChanged { size -> patch0Size = size.toSize().maxDimension }
        ) {
            GradientPatch(
                "Vibrance",
                Modifier.radialPigmentsGradient(
                    Color.Yellow,
                    Color.Green,
                    radius = patch0Size * 0.5f
                )
            )
            Spacer(Modifier.width(16.dp))
            GradientPatch(
                "Compose",
                Modifier.background(
                    Brush.radialGradient(
                        listOf(Color.Yellow, Color.Green),
                        radius = patch0Size
                    )
                )
            )
        }
        // Magenta to Red
        var patch1Size by remember { mutableFloatStateOf(0f) }
        Row(
            Modifier
                .fillMaxWidth()
                .weight(1.0f)
                .padding(0.dp, 8.dp, 0.dp, 8.dp)
                .onSizeChanged { size -> patch1Size = size.toSize().maxDimension }
        ) {
            GradientPatch(
                "Vibrance",
                Modifier.radialPigmentsGradient(
                    Color.Magenta,
                    Color.Red,
                    radius = patch1Size * 0.5f
                )
            )
            Spacer(Modifier.width(16.dp))
            GradientPatch(
                "Compose",
                Modifier.background(
                    Brush.radialGradient(
                        listOf(Color.Magenta, Color.Red),
                        radius = patch1Size * 0.5f
                    )
                )
            )
        }
        // White to Blue
        var patch2Size by remember { mutableFloatStateOf(0f) }
        Row(
            Modifier
                .fillMaxWidth()
                .weight(1.0f)
                .padding(0.dp, 8.dp, 0.dp, 16.dp)
                .onSizeChanged { size -> patch2Size = size.toSize().maxDimension }
        ) {
            GradientPatch(
                "Vibrance",
                Modifier.radialPigmentsGradient(Color.White, Color.Blue, radius = patch1Size * 0.5f)
            )
            Spacer(Modifier.width(16.dp))
            GradientPatch(
                "Compose",
                Modifier.background(
                    Brush.radialGradient(
                        listOf(Color.White, Color.Blue),
                        radius = patch1Size * 0.5f
                    )
                )
            )
        }
    }
}