package me.rerere.awara.ui.component.common

import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentSize
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ScrollableTabRow
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp

private const val INDICATOR_ANIM_DURATION_MS = 240

@Composable
fun BetterTabBar(
    modifier: Modifier = Modifier,
    selectedTabIndex: Int,
    content: @Composable () -> Unit,
) {
    ScrollableTabRow(
        selectedTabIndex = selectedTabIndex,
        modifier = modifier,
        indicator = { tabPositions ->
            val currentTab = tabPositions.getOrNull(selectedTabIndex)
            if (currentTab != null) {
                val indicatorOffset by
                    animateDpAsState(
                        targetValue = (currentTab.left + currentTab.right) / 2 - 16.dp,
                        animationSpec =
                            tween(
                                durationMillis = INDICATOR_ANIM_DURATION_MS,
                                easing = TabTransitionEasing,
                            ),
                        label = "tab_indicator_offset",
                    )
                Box(
                    Modifier.fillMaxWidth()
                        .wrapContentSize(Alignment.BottomStart)
                        .offset(x = indicatorOffset)
                        .width(32.dp)
                        .height(3.dp)
                        .background(
                            color = MaterialTheme.colorScheme.primary,
                            shape = RoundedCornerShape(topStart = 2.dp, topEnd = 2.dp),
                        )
                )
            }
        },
        edgePadding = 4.dp,
        divider = {},
        containerColor = Color.Transparent,
    ) {
        content()
    }
}

@Preview(showBackground = true)
@Composable
private fun TabBarPreview() {
    BetterTabBar(selectedTabIndex = 0) {
        Tab(selected = false, onClick = {}) { Text(text = "Tab 1") }

        Tab(selected = true, onClick = {}) { Text(text = "Tab 2") }

        Tab(selected = false, onClick = {}) { Text(text = "Tab 3") }
    }
}
