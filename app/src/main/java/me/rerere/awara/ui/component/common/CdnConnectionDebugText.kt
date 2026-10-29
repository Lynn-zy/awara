package me.rerere.awara.ui.component.common

import androidx.compose.foundation.layout.Column
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import me.rerere.awara.R
import me.rerere.awara.data.diagnostics.CdnConnectionSnapshot

/** 下载横排、播放竖排；已验证可用数量与实际连接名单分别展示，避免混淆。 */
@Composable
internal fun CdnConnectionDebugText(
    snapshot: CdnConnectionSnapshot,
    modifier: Modifier = Modifier,
    stacked: Boolean = false,
) {
    val availableText =
        stringResource(R.string.cdn_connection_debug_available, snapshot.availableCdnCount)
    val connectedText =
        stringResource(
            R.string.cdn_connection_debug_connected,
            snapshot.connectedCdnNames.joinToString(", ").ifEmpty {
                stringResource(R.string.cdn_connection_debug_idle)
            },
        )
    if (stacked) {
        Column(modifier) {
            Text(
                availableText,
                style = MaterialTheme.typography.labelSmall,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                connectedText,
                style = MaterialTheme.typography.labelSmall,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    } else {
        Text(
            text = "$availableText · $connectedText",
            style = MaterialTheme.typography.labelSmall,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = modifier,
        )
    }
}
