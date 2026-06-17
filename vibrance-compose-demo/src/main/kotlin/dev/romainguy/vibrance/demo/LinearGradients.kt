package dev.romainguy.vibrance.demo

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import dev.romainguy.vibrance.compose.horizontalPigmentsGradient
import dev.romainguy.vibrance.compose.linearPigmentsGradient
import dev.romainguy.vibrance.compose.verticalPigmentsGradient

@Composable
fun LinearGradients(modifier: Modifier) {
    Column(
        modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp)
    ) {
        Text(
            "Linear Gradients",
            style = MaterialTheme.typography.titleLarge
        )
        // Yellow to Red
        Row(
            Modifier
                .fillMaxWidth()
                .weight(1.0f)
                .padding(0.dp, 16.dp, 0.dp, 8.dp)
        ) {
            GradientPatch(
                "Vibrance",
                Modifier.verticalPigmentsGradient(Color.Yellow, Color.Red)
            )
            Spacer(Modifier.width(16.dp))
            GradientPatch(
                "Compose",
                Modifier.background(Brush.verticalGradient(listOf(Color.Yellow, Color.Red)))
            )
        }
        // Yellow to Blue
        Column(
            Modifier
                .fillMaxWidth()
                .weight(1.0f)
                .padding(0.dp, 8.dp, 0.dp, 8.dp)
        ) {
            GradientPatch(
                "Vibrance",
                Modifier.horizontalPigmentsGradient(Color.Yellow, Color.Blue)
            )
            Spacer(Modifier.height(16.dp))
            GradientPatch(
                "Compose",
                Modifier.background(Brush.horizontalGradient(listOf(Color.Yellow, Color.Blue)))
            )
        }
        // White to Blue
        Row(
            Modifier
                .fillMaxWidth()
                .weight(1.0f)
                .padding(0.dp, 8.dp, 0.dp, 16.dp)
        ) {
            GradientPatch(
                "Vibrance",
                Modifier.linearPigmentsGradient(
                    Offset.Zero,
                    Color.White,
                    Offset.Infinite,
                    Color.Blue
                )
            )
            Spacer(Modifier.width(16.dp))
            GradientPatch(
                "Compose",
                Modifier.background(
                    brush = Brush.linearGradient(
                        listOf(Color.White, Color.Blue),
                        start = Offset.Zero,
                        end = Offset.Infinite
                    )
                )
            )
        }
    }
}