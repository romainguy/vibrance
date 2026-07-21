package dev.romainguy.vibrance.demo

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import dev.romainguy.vibrance.demo.ui.theme.VibranceTheme

@Composable
fun VibranceApplication(modifier: Modifier) {
    Box(modifier) {
        val pagerState = rememberPagerState(pageCount = { 4 })
        HorizontalPager(state = pagerState) { page ->
            when (page) {
                0 -> LinearGradients(Modifier.padding(bottom = 8.dp))
                1 -> RadialGradients(Modifier.padding(bottom = 8.dp))
                2 -> SweepGradients(Modifier.padding(bottom = 8.dp))
                3 -> BrushGradients(Modifier.padding(bottom = 8.dp))
            }
        }
        PageIndicator(pagerState.pageCount, pagerState.currentPage)
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
