package dev.romainguy.vibrance.demo

import android.annotation.SuppressLint
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.toSize
import dev.romainguy.vibrance.compose.horizontalPaintGradient
import dev.romainguy.vibrance.compose.linearPaintGradient
import dev.romainguy.vibrance.compose.radialPaintGradient
import dev.romainguy.vibrance.compose.sweepPaintGradient
import dev.romainguy.vibrance.compose.verticalPaintGradient
import dev.romainguy.vibrance.demo.ui.theme.VibranceTheme

class VibranceActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            VibranceTheme {
                Scaffold(modifier = Modifier.fillMaxSize()) { innerPadding ->
                    Box(Modifier
                        .padding(innerPadding)
                        .fillMaxSize()
                    ) {
                        val pagerState = rememberPagerState(pageCount = { 3 })
                        HorizontalPager(state = pagerState) { page ->
                            when (page) {
                                0 -> LinearPaintMix(Modifier.padding(bottom = 8.dp))
                                1 -> RadialPaintMix(Modifier.padding(bottom = 8.dp))
                                2 -> SweepPaintMix(Modifier.padding(bottom = 8.dp))
                            }
                        }
                        PageIndicator(pagerState.pageCount, pagerState.currentPage)
                    }
                }
            }
        }
    }
}

@Composable
fun BoxScope.PageIndicator(pageCount: Int, currentPage: Int) {
    Row(
        Modifier
            .wrapContentHeight()
            .fillMaxWidth()
            .align(Alignment.BottomCenter)
            .padding(bottom = 8.dp),
        horizontalArrangement = Arrangement.Center
    ) {
        repeat(pageCount) { iteration ->
            val color = if (currentPage == iteration) Color.DarkGray else Color.LightGray
            Box(
                modifier = Modifier
                    .padding(2.dp)
                    .clip(CircleShape)
                    .background(color)
                    .size(8.dp)
            )
        }
    }
}

@Composable
fun LinearPaintMix(modifier: Modifier) {
    Column(modifier
        .fillMaxSize()
        .padding(horizontal = 16.dp)
    ) {
        Text(
            "Linear Gradients",
            style = MaterialTheme.typography.titleLarge
        )
        // Yellow to Red
        Row(Modifier
            .fillMaxWidth()
            .weight(1.0f)
            .padding(0.dp, 16.dp, 0.dp, 8.dp)
        ) {
            GradientPatch(
                "Vibrance",
                Modifier.verticalPaintGradient(Color.Yellow, Color.Red)
            )
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
            .padding(0.dp, 8.dp, 0.dp, 8.dp)
        ) {
            GradientPatch(
                "Vibrance",
                Modifier.horizontalPaintGradient(Color.Yellow, Color.Blue)
            )
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
            .padding(0.dp, 8.dp, 0.dp, 16.dp)
        ) {
            GradientPatch(
                "Vibrance",
                Modifier.linearPaintGradient(Offset.Zero, Color.White, Offset.Infinite, Color.Blue)
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
fun RadialPaintMix(modifier: Modifier) {
    Column(modifier
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
                Modifier.radialPaintGradient(Color.Yellow, Color.Green, radius = patch0Size * 0.5f)
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
                Modifier.radialPaintGradient(Color.Magenta, Color.Red, radius = patch1Size * 0.5f)
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
        // Yellow to Green
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
                Modifier.radialPaintGradient(Color.White, Color.Blue, radius = patch1Size * 0.5f)
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

@Composable
fun SweepPaintMix(modifier: Modifier) {
    Column(modifier
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
                Modifier.sweepPaintGradient(Color.Yellow, Color.Blue)
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
                Modifier.sweepPaintGradient(Color.Magenta, Color.Yellow)
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
                Modifier.sweepPaintGradient(Color.White, Color.Blue)
            )
            Spacer(Modifier.width(16.dp))
            GradientPatch(
                "Compose",
                Modifier.background(Brush.sweepGradient(listOf(Color.White, Color.Blue)))
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
        Text(label, style = MaterialTheme.typography.labelLarge)
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
        Text(label, style = MaterialTheme.typography.labelLarge)
    }
}
