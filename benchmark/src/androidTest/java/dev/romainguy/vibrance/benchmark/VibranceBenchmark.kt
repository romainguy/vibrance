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
            BlackHole.consume(vibrance.mix(0.50841904f, 0.258023f, 0.00149352f, 0.23206444f, color, 0))
        }
    }

    @Test
    fun lerp() {
        val vibrance = Vibrance()
        val color = FloatArray(3)
        benchmarkRule.measureRepeated {
            BlackHole.consume(
                vibrance.lerp(0.0f, 0.0f, 1.0f, 0.0f, 1.0f, 1.0f, 0.5f, color, 0)
            )
        }
    }
}
