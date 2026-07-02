package dev.romainguy.vibrance.demo

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Matrix
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import dev.romainguy.vibrance.compose.horizontalPigmentsGradient
import dev.romainguy.vibrance.compose.linearPigmentsGradient
import dev.romainguy.vibrance.compose.verticalPigmentsGradient

private const val Heart = "M0 200 v-200 h200 a100,100 90 0,1 0,200 a100,100 90 0,1 -200,0 z"

@Composable
fun BrushGradients(modifier: Modifier) {
    Column(
        modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp)
    ) {
        Text(
            "Brushes",
            style = MaterialTheme.typography.titleLarge
        )

        Spacer(Modifier.height(16.dp))

        Column(Modifier
            .fillMaxWidth()
            .weight(1.0f)
            .background(Color.Black)
            .padding(16.dp)
        ) {
            Text(
                "Mixing colors as pigments generates natural and vibrant looking gradients.",
                style = MaterialTheme.typography.titleLarge.copy(
                    brush = Brush.horizontalPigmentsGradient(Color.Yellow, Color.Blue)
                )
            )

            Spacer(Modifier.height(16.dp))

            Text(
                "Mixing colors as pigments generates natural and vibrant looking gradients.",
                style = MaterialTheme.typography.titleLarge.copy(
                    brush = Brush.verticalPigmentsGradient(Color.Cyan, Color.Magenta)
                )
            )

            Spacer(Modifier.height(16.dp))

            val density = LocalDensity.current
            val shapeSize = 120.dp
            val path = remember {
                val shape = PathParser().parsePathString(Heart).toPath()
                with(density) {
                    val bounds = shape.getBounds()
                    val transform = Matrix().apply {
                        scale(shapeSize.toPx() / bounds.width, shapeSize.toPx() / bounds.height)
                    }
                    shape.transform(transform)
                }
                shape
            }
            Canvas(Modifier.size(shapeSize)) {
                drawPath(
                    path,
                    Brush.linearPigmentsGradient(
                        Color.Yellow,
                        Color.Red,
                        Offset(Float.POSITIVE_INFINITY, 0.0f),
                        Offset(0.0f, Float.POSITIVE_INFINITY)
                    )
                )
            }
        }

        Spacer(Modifier.height(24.dp))
    }
}
