package dev.romainguy.vibrance.demo

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.plus
import androidx.compose.material3.Scaffold
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import dev.romainguy.vibrance.demo.ui.theme.VibranceTheme

fun main() = application {
    Window(
        onCloseRequest = ::exitApplication,
        title = "Vibrance Demo",
    ) {
        VibranceTheme {
            Scaffold(modifier = Modifier.fillMaxSize()) { innerPadding ->
                VibranceApplication(
                    Modifier
                        .padding(innerPadding + PaddingValues(top = 16.dp))
                        .fillMaxSize()
                )
            }
        }
    }
}
