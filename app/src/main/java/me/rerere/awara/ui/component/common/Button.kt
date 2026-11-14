package me.rerere.awara.ui.component.common

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.takeOrElse
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.takeOrElse

/** 支持默认实心与描边两种样式及内嵌加载状态的通用按钮。 */
@Composable
fun Button(
    modifier: Modifier = Modifier,
    loading: Boolean = false,
    type: ButtonType = ButtonType.Default,
    onClick: () -> Unit,
    content: @Composable () -> Unit,
) {
    val buttonContent: @Composable RowScope.() -> Unit = {
        ButtonLoadingIndicator(loading = loading)
        content()
    }
    when (type) {
        ButtonType.Default -> {
            androidx.compose.material3.Button(
                modifier = modifier,
                onClick = onClick,
                content = buttonContent,
            )
        }

        ButtonType.Outlined -> {
            OutlinedButton(
                modifier = modifier,
                onClick = onClick,
                content = buttonContent,
            )
        }
    }
}

/** 按钮内嵌加载指示器，统一保持 2.dp 线宽与当前文本行高对齐。 */
@Composable
private fun RowScope.ButtonLoadingIndicator(loading: Boolean) {
    AnimatedVisibility(visible = loading) {
        val textStyle = LocalTextStyle.current
        CircularProgressIndicator(
            color = textStyle.color.takeOrElse { LocalContentColor.current },
            strokeWidth = 2.dp,
            modifier =
                Modifier.align(Alignment.CenterVertically)
                    .padding(horizontal = 6.dp)
                    .size(
                        textStyle.lineHeight
                            .takeOrElse { MaterialTheme.typography.labelLarge.lineHeight }
                            .value
                            .dp
                    ),
        )
    }
}

@Preview(showBackground = true, name = "Button Preview")
@Composable
fun ButtonPreview() {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        ButtonType.values().forEach { btnType ->
            Button(type = btnType, onClick = {}, loading = true) { Text(text = "Button") }
        }
    }
}

enum class ButtonType {
    Default,
    Outlined,
}
