@file:OptIn(ExperimentalBlackHoleApi::class)

package dev.romainguy.vibrance.benchmark

import androidx.benchmark.BlackHole
import androidx.benchmark.ExperimentalBlackHoleApi
import androidx.benchmark.junit4.BenchmarkRule
import androidx.benchmark.junit4.measureRepeated
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.romainguy.vibrance.Vibrance
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class VibranceBenchmark {
    @get:Rule
    val benchmarkRule = BenchmarkRule()

    @Test
    fun pigmentsMixing() {
        val vibrance = Vibrance()
        val color = FloatArray(3)
        benchmarkRule.measureRepeated {
            BlackHole.consume(vibrance.pigmentsMix(0.5f, 0.25f, 0.02f, 0.23f, color))
        }
    }

    @Test
    fun latentColorToColor() {
        val vibrance = Vibrance()
        val color = FloatArray(3)
        val latentColor = FloatArray(6)
        vibrance.colorToLatentColor(0.1f, 0.7f, 1.0f, latentColor)
        benchmarkRule.measureRepeated {
            BlackHole.consume(vibrance.latentColorToColor(latentColor, color))
        }
    }

    @Test
    fun latentColorsMix() {
        val vibrance = Vibrance()
        val color = FloatArray(3)
        val latentColor0 = FloatArray(6)
        val latentColor1 = FloatArray(6)

        vibrance.colorToLatentColor(0.0f, 0.0f, 1.0f, latentColor0)
        vibrance.colorToLatentColor(0.0f, 1.0f, 1.0f, latentColor1)

        benchmarkRule.measureRepeated {
            BlackHole.consume(vibrance.latentColorsMix(latentColor0, latentColor1, 0.5f, color))
        }
    }

    @Test
    fun colorToLatentColor() {
        val vibrance = Vibrance()
        val latentColor = FloatArray(6)
        benchmarkRule.measureRepeated {
            BlackHole.consume(vibrance.colorToLatentColor(0.1f, 0.7f, 1.0f, latentColor))
        }
    }

    @Test
    fun colorsMix() {
        val vibrance = Vibrance()
        val color = FloatArray(3)
        benchmarkRule.measureRepeated {
            BlackHole.consume(vibrance.colorsMix(0.0f, 0.0f, 1.0f, 0.0f, 1.0f, 1.0f, 0.5f, color))
        }
    }
}
