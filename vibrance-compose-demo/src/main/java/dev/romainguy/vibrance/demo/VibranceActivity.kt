package dev.romainguy.vibrance.demo

import android.annotation.SuppressLint
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import dev.romainguy.vibrance.compose.horizontalPaintGradient
import dev.romainguy.vibrance.compose.paintGradient
import dev.romainguy.vibrance.compose.verticalPaintGradient
import dev.romainguy.vibrance.demo.ui.theme.VibranceTheme

class VibranceActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            VibranceTheme {
                Scaffold(modifier = Modifier.fillMaxSize()) { innerPadding ->
                    PaintMix(Modifier.padding(innerPadding))
                }
            }
        }
    }
}

@Composable
fun PaintMix(modifier: Modifier = Modifier) {
    Column(modifier.fillMaxSize()) {
        // Yellow to Red
        Row(Modifier
            .fillMaxWidth()
            .weight(1.0f)
            .padding(16.dp, 16.dp, 16.dp, 8.dp)
        ) {
            GradientPatch("Vibrance", Modifier.verticalPaintGradient(Color.Yellow, Color.Red))
            Spacer(Modifier.width(16.dp))
            GradientPatch(
                "Compose",
                Modifier.background(Brush.verticalGradient(listOf(Color.Yellow, Color.Red)))
            )
        }
        // Yellow to Blue
        Column(Modifier
            .fillMaxWidth()
            .weight(1.0f)
            .padding(16.dp, 8.dp, 16.dp, 8.dp)
        ) {
            GradientPatch("Vibrance", Modifier.horizontalPaintGradient(Color.Yellow, Color.Blue))
            Spacer(Modifier.height(16.dp))
            GradientPatch(
                "Compose",
                Modifier.background(Brush.horizontalGradient(listOf(Color.Yellow, Color.Blue)))
            )
        }
        // White to Blue
        Row(Modifier
            .fillMaxWidth()
            .weight(1.0f)
            .padding(16.dp, 8.dp, 16.dp, 16.dp)
        ) {
            GradientPatch(
                "Vibrance",
                Modifier.paintGradient(Offset.Zero, Color.White, Offset.Infinite, Color.Blue)
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

@Composable
private fun RowScope.GradientPatch(
    label: String,
    @SuppressLint("ModifierParameter") gradientModifier: Modifier
) {
    Column(Modifier
        .fillMaxHeight()
        .weight(1.0f)) {
        Box(Modifier
            .fillMaxWidth()
            .weight(1.0f) then gradientModifier
        )
        Text(label)
    }
}

@Composable
private fun ColumnScope.GradientPatch(
    label: String,
    @SuppressLint("ModifierParameter") gradientModifier: Modifier
) {
    Column(Modifier
        .fillMaxWidth()
        .weight(1.0f)) {
        Box(Modifier
            .fillMaxWidth()
            .weight(1.0f) then gradientModifier
        )
        Text(label)
    }
}
