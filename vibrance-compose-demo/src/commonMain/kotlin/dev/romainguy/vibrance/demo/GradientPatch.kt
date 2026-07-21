package dev.romainguy.vibrance.demo

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier

@Composable
internal fun RowScope.GradientPatch(
    label: String,
    modifier: Modifier
) {
    Column(
        Modifier
            .fillMaxHeight()
            .weight(1.0f)
    ) {
        Box(
            Modifier
                .fillMaxWidth()
                .weight(1.0f) then modifier
        )
        Text(label, style = MaterialTheme.typography.labelLarge)
    }
}

@Composable
internal fun ColumnScope.GradientPatch(
    label: String,
    modifier: Modifier
) {
    Column(
        Modifier
            .fillMaxWidth()
            .weight(1.0f)
    ) {
        Box(
            Modifier
                .fillMaxWidth()
                .weight(1.0f) then modifier
        )
        Text(label, style = MaterialTheme.typography.labelLarge)
    }
}
