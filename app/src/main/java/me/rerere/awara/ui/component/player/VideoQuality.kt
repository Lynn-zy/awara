package me.rerere.awara.ui.component.player

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import java.util.Locale
import me.rerere.awara.R
import me.rerere.awara.data.entity.VideoFile
import me.rerere.awara.data.entity.qualityKey
import me.rerere.awara.data.entity.videoQualityRank

@Composable
fun videoQualityDisplayName(quality: String): String {
    val key = quality.trim().lowercase(Locale.ROOT)
    return when {
        key.isEmpty() -> stringResource(R.string.video_quality_unavailable)
        key == "source" -> stringResource(R.string.video_quality_original)
        key.removeSuffix("p").toIntOrNull() != null -> "${key.removeSuffix("p")}p"
        else -> quality.trim()
    }
}

fun matchVideoQuality(qualityA: String, qualityB: String): Boolean {
    val keyA = qualityA.trim().lowercase(Locale.ROOT)
    val keyB = qualityB.trim().lowercase(Locale.ROOT)
    if (keyA.isEmpty() || keyB.isEmpty()) return false
    return keyA == keyB
}

fun selectTargetPlayerItem(
    items: List<PlayerState.PlayerItem>,
    preferredQuality: String,
): PlayerState.PlayerItem? {
    if (items.isEmpty()) return null
    val matched = items.firstOrNull { matchVideoQuality(it.quality, preferredQuality) }
    return matched ?: items.maxByOrNull { videoQualityRank(it.quality) } ?: items.firstOrNull()
}

@Composable
fun VideoQualityDownloadDialog(
    sources: List<VideoFile>,
    initialSelectedQualityKey: String?,
    onDismissRequest: () -> Unit,
    onConfirm: (VideoFile) -> Unit,
) {
    if (sources.isEmpty()) return

    // 默认选中最高可用清晰度或匹配当前偏好的清晰度
    val defaultSource =
        remember(sources, initialSelectedQualityKey) {
            val preferred = initialSelectedQualityKey?.trim()?.lowercase(Locale.ROOT).orEmpty()
            sources.firstOrNull { matchVideoQuality(it.qualityKey, preferred) } ?: sources.first()
        }
    var selectedQualityKey by
        remember(sources, initialSelectedQualityKey) { mutableStateOf(defaultSource.qualityKey) }

    AlertDialog(
        onDismissRequest = onDismissRequest,
        title = { Text(text = stringResource(R.string.video_quality_choose_download)) },
        text = {
            val scrollState = rememberScrollState()
            Column(modifier = Modifier.fillMaxWidth().verticalScroll(scrollState)) {
                sources.forEach { source ->
                    val isSelected = matchVideoQuality(source.qualityKey, selectedQualityKey)
                    Row(
                        modifier =
                            Modifier.fillMaxWidth()
                                .selectable(
                                    selected = isSelected,
                                    onClick = { selectedQualityKey = source.qualityKey },
                                    role = Role.RadioButton,
                                )
                                .padding(horizontal = 8.dp, vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        RadioButton(selected = isSelected, onClick = null)
                        Spacer(modifier = Modifier.width(12.dp))
                        Text(
                            text = videoQualityDisplayName(source.name),
                            style = MaterialTheme.typography.bodyLarge,
                        )
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    val chosen =
                        sources.firstOrNull { matchVideoQuality(it.qualityKey, selectedQualityKey) }
                            ?: defaultSource
                    onConfirm(chosen)
                }
            ) {
                Text(text = stringResource(R.string.video_quality_confirm))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismissRequest) { Text(text = stringResource(R.string.cancel)) }
        },
    )
}
