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
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import dev.romainguy.vibrance.compose.sweepPigmentsGradient

@Composable
fun SweepGradients(modifier: Modifier) {
    Column(
        modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp)
    ) {
        Text(
            "Sweep Gradients",
            style = MaterialTheme.typography.titleLarge
        )
        // Yellow to Blue
        Row(
            Modifier
                .fillMaxWidth()
                .weight(1.0f)
                .padding(0.dp, 16.dp, 0.dp, 8.dp)
        ) {
            GradientPatch(
                "Vibrance",
                Modifier.sweepPigmentsGradient(Color.Yellow, Color.Blue)
            )
            Spacer(Modifier.width(16.dp))
            GradientPatch(
                "Compose",
                Modifier.background(Brush.sweepGradient(listOf(Color.Yellow, Color.Blue)))
            )
        }
        // Magenta to Red
        Row(
            Modifier
                .fillMaxWidth()
                .weight(1.0f)
                .padding(0.dp, 8.dp, 0.dp, 8.dp)
        ) {
            GradientPatch(
                "Vibrance",
                Modifier.sweepPigmentsGradient(Color.Magenta, Color.Yellow)
            )
            Spacer(Modifier.width(16.dp))
            GradientPatch(
                "Compose",
                Modifier.background(Brush.sweepGradient(listOf(Color.Magenta, Color.Yellow)))
            )
        }
        // Yellow to Green
        Row(
            Modifier
                .fillMaxWidth()
                .weight(1.0f)
                .padding(0.dp, 8.dp, 0.dp, 16.dp)
        ) {
            GradientPatch(
                "Vibrance",
                Modifier.sweepPigmentsGradient(Color.White, Color.Blue)
            )
            Spacer(Modifier.width(16.dp))
            GradientPatch(
                "Compose",
                Modifier.background(Brush.sweepGradient(listOf(Color.White, Color.Blue)))
            )
        }
    }
}