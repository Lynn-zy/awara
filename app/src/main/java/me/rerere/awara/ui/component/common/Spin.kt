package me.rerere.awara.ui.component.common

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import me.rerere.awara.ui.component.common.pullrefresh.LocalRefreshContainerRefreshing

@Composable
fun Spin(modifier: Modifier = Modifier, show: Boolean, content: @Composable BoxScope.() -> Unit) {
    val shouldShow = show && !LocalRefreshContainerRefreshing.current
    Box(
        modifier = modifier,
        content = {
            content()
            AnimatedVisibility(
                visible = shouldShow,
                enter = fadeIn(),
                exit = fadeOut(),
                modifier = Modifier.matchParentSize(),
            ) {
                Box(
                    modifier =
                        Modifier.clip(MaterialTheme.shapes.small)
                            .background(
                                color = MaterialTheme.colorScheme.background.copy(alpha = 0.15f)
                            )
                            .fillMaxSize()
                ) {
                    CircularProgressIndicator(
                        modifier = Modifier.align(Alignment.Center).testTag("spin_loading")
                    )
                }
            }
        },
    )
}
