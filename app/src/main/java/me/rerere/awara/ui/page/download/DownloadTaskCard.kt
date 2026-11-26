package me.rerere.awara.ui.page.download

import android.net.Uri
import androidx.annotation.StringRes
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.SystemUpdate
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import me.rerere.awara.R
import me.rerere.awara.data.diagnostics.CdnConnectionSnapshot
import me.rerere.awara.data.entity.DownloadTask
import me.rerere.awara.data.entity.DownloadTaskStatus
import me.rerere.awara.data.entity.DownloadTaskType
import me.rerere.awara.ui.LocalRouterProvider
import me.rerere.awara.ui.component.common.CdnConnectionDebugText
import me.rerere.awara.util.prettyFileSize

/** 下载任务进度条与百分比计算结果。 */
internal data class DownloadTaskProgressMetrics(
    val hasKnownTotal: Boolean,
    val fraction: Float,
    val percentage: Int,
)

/** 任务进度条的渲染模式。 */
internal sealed interface DownloadTaskProgressVisual {
    data object Indeterminate : DownloadTaskProgressVisual

    data class Determinate(val progress: Float, val isError: Boolean = false) :
        DownloadTaskProgressVisual
}

/** 任务卡片主操作按钮的语义状态。 */
internal sealed interface DownloadTaskPrimaryAction {
    data object Resuming : DownloadTaskPrimaryAction

    data object Pause : DownloadTaskPrimaryAction

    data object Resume : DownloadTaskPrimaryAction

    data object Saving : DownloadTaskPrimaryAction

    data object RetryCleanup : DownloadTaskPrimaryAction

    data object Cancelling : DownloadTaskPrimaryAction

    data class Completed(val canInstall: Boolean) : DownloadTaskPrimaryAction
}

internal fun computeTaskProgressMetrics(
    downloadedBytes: Long,
    totalBytes: Long,
): DownloadTaskProgressMetrics {
    val safeDownloaded = downloadedBytes.coerceAtLeast(0L)
    if (totalBytes <= 0L) {
        return DownloadTaskProgressMetrics(hasKnownTotal = false, fraction = 0f, percentage = 0)
    }
    val ratio = (safeDownloaded.toDouble() / totalBytes.toDouble()).coerceIn(0.0, 1.0)
    return DownloadTaskProgressMetrics(
        hasKnownTotal = true,
        fraction = ratio.toFloat(),
        percentage = (ratio * 100.0).toInt().coerceIn(0, 100),
    )
}

internal fun resolveDownloadTaskProgressVisual(
    status: DownloadTaskStatus,
    metrics: DownloadTaskProgressMetrics,
): DownloadTaskProgressVisual =
    when (status) {
        DownloadTaskStatus.RUNNING ->
            if (metrics.hasKnownTotal) {
                DownloadTaskProgressVisual.Determinate(metrics.fraction)
            } else {
                DownloadTaskProgressVisual.Indeterminate
            }
        DownloadTaskStatus.PAUSED ->
            DownloadTaskProgressVisual.Determinate(if (metrics.hasKnownTotal) metrics.fraction else 0f)
        DownloadTaskStatus.QUEUED,
        DownloadTaskStatus.SAVING,
        DownloadTaskStatus.RETRYING,
        DownloadTaskStatus.CANCELLING -> DownloadTaskProgressVisual.Indeterminate
        DownloadTaskStatus.FAILED ->
            DownloadTaskProgressVisual.Determinate(
                progress = if (metrics.hasKnownTotal) metrics.fraction else 0f,
                isError = true,
            )
        DownloadTaskStatus.COMPLETED -> DownloadTaskProgressVisual.Determinate(1f)
    }

/**
 * 解析任务卡片底部主操作区应呈现的交互形态。
 *
 * `isResuming` 需优先拦截 PAUSED/FAILED/RUNNING 等过渡态，直到实际字节前进才撤下加载态。
 */
internal fun resolveDownloadTaskPrimaryAction(
    status: DownloadTaskStatus,
    taskType: DownloadTaskType,
    error: String?,
    isResuming: Boolean,
): DownloadTaskPrimaryAction {
    if (
        isResuming &&
            status != DownloadTaskStatus.SAVING &&
            status != DownloadTaskStatus.CANCELLING &&
            status != DownloadTaskStatus.COMPLETED
    ) {
        return DownloadTaskPrimaryAction.Resuming
    }
    return when (status) {
        DownloadTaskStatus.QUEUED -> DownloadTaskPrimaryAction.Resuming
        DownloadTaskStatus.RUNNING,
        DownloadTaskStatus.RETRYING -> DownloadTaskPrimaryAction.Pause
        DownloadTaskStatus.PAUSED,
        DownloadTaskStatus.FAILED -> DownloadTaskPrimaryAction.Resume
        DownloadTaskStatus.SAVING -> DownloadTaskPrimaryAction.Saving
        DownloadTaskStatus.CANCELLING ->
            if (!error.isNullOrBlank()) {
                DownloadTaskPrimaryAction.RetryCleanup
            } else {
                DownloadTaskPrimaryAction.Cancelling
            }
        DownloadTaskStatus.COMPLETED ->
            DownloadTaskPrimaryAction.Completed(canInstall = taskType == DownloadTaskType.APP_UPDATE)
    }
}

internal fun canCancelDownloadTask(status: DownloadTaskStatus): Boolean =
    status != DownloadTaskStatus.CANCELLING && status != DownloadTaskStatus.COMPLETED

/**
 * 下载管理页中的单个任务卡片，展示封面、标题、状态与进度、CDN 调试信息及任务控制按钮。
 */
@Composable
internal fun DownloadTaskCard(
    task: DownloadTask,
    isOperating: Boolean,
    isResuming: Boolean = false,
    onPause: () -> Unit,
    onResume: () -> Unit,
    onCancel: () -> Unit,
    onDelete: () -> Unit = {},
    onInstall: () -> Unit = {},
    onRetryCleanup: () -> Unit,
    cdnSnapshot: CdnConnectionSnapshot? = null,
) {
    val router = LocalRouterProvider.current
    BoxWithConstraints(modifier = Modifier.fillMaxWidth()) {
        val thumbWidth = if (maxWidth >= 600.dp) 112.dp else 96.dp
        val isVideoTask = task.taskType == DownloadTaskType.VIDEO
        val metrics = computeTaskProgressMetrics(task.downloadedBytes, task.totalBytes)

        val cardContent: @Composable ColumnScope.() -> Unit = {
            Column(
                modifier = Modifier.fillMaxWidth().padding(12.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                DownloadTaskHeader(task = task, thumbWidth = thumbWidth)
                DownloadTaskStatusSection(
                    task = task,
                    metrics = metrics,
                    cdnSnapshot = cdnSnapshot,
                )
                DownloadTaskActionRow(
                    primaryAction =
                        resolveDownloadTaskPrimaryAction(
                            status = task.status,
                            taskType = task.taskType,
                            error = task.error,
                            isResuming = isResuming,
                        ),
                    showCancel = canCancelDownloadTask(task.status),
                    isOperating = isOperating,
                    onPause = onPause,
                    onResume = onResume,
                    onCancel = onCancel,
                    onDelete = onDelete,
                    onInstall = onInstall,
                    onRetryCleanup = onRetryCleanup,
                )
            }
        }

        if (isVideoTask && task.resourceId.isNotBlank()) {
            Card(
                modifier = Modifier.fillMaxWidth(),
                onClick = { router.navigate("video/${Uri.encode(task.resourceId)}") },
                content = cardContent,
            )
        } else {
            Card(modifier = Modifier.fillMaxWidth(), content = cardContent)
        }
    }
}

@Composable
private fun DownloadTaskHeader(task: DownloadTask, thumbWidth: Dp) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (task.taskType == DownloadTaskType.VIDEO) {
            AsyncImage(
                model = task.thumbnail,
                contentDescription = null,
                modifier =
                    Modifier.width(thumbWidth)
                        .aspectRatio(16f / 9f)
                        .clip(RoundedCornerShape(8.dp)),
                contentScale = ContentScale.Crop,
            )
        } else {
            Box(
                modifier =
                    Modifier.width(thumbWidth)
                        .aspectRatio(16f / 9f)
                        .clip(RoundedCornerShape(8.dp))
                        .background(MaterialTheme.colorScheme.primaryContainer),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = Icons.Outlined.SystemUpdate,
                    contentDescription = null,
                    modifier = Modifier.size(28.dp),
                    tint = MaterialTheme.colorScheme.onPrimaryContainer,
                )
            }
        }

        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text(
                text = task.title.ifBlank { task.resourceId },
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                style = MaterialTheme.typography.titleSmall,
            )

            if (task.authorName.isNotBlank()) {
                Text(
                    text = task.authorName.trim(),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun DownloadTaskStatusSection(
    task: DownloadTask,
    metrics: DownloadTaskProgressMetrics,
    cdnSnapshot: CdnConnectionSnapshot?,
) {
    val (statusLabel, statusColor) = resolveStatusLabelAndColor(task.status)
    val safeDownloaded = task.downloadedBytes.coerceAtLeast(0L)
    val progressText =
        if (metrics.hasKnownTotal) {
            stringResource(
                R.string.download_task_progress,
                metrics.percentage,
                prettyFileSize(safeDownloaded),
                prettyFileSize(task.totalBytes),
            )
        } else {
            stringResource(
                R.string.download_task_unknown_progress,
                prettyFileSize(safeDownloaded),
            )
        }

    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = statusLabel,
            style = MaterialTheme.typography.labelSmall,
            color = statusColor,
        )
        Text(
            text = progressText,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }

    cdnSnapshot?.let { snapshot ->
        CdnConnectionDebugText(snapshot, Modifier.fillMaxWidth())
    }

    if (!task.error.isNullOrBlank() && task.status != DownloadTaskStatus.RETRYING) {
        Text(
            text = task.error,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.error,
        )
    }

    // Card 背景同为 surfaceVariant，改用对比色让未下载部分的完整底轨可见。
    val progressTrackColor = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.22f)
    when (val visual = resolveDownloadTaskProgressVisual(task.status, metrics)) {
        DownloadTaskProgressVisual.Indeterminate -> {
            LinearProgressIndicator(
                modifier = Modifier.fillMaxWidth(),
                trackColor = progressTrackColor,
            )
        }
        is DownloadTaskProgressVisual.Determinate -> {
            LinearProgressIndicator(
                progress = visual.progress,
                modifier = Modifier.fillMaxWidth(),
                color =
                    if (visual.isError) MaterialTheme.colorScheme.error
                    else MaterialTheme.colorScheme.primary,
                trackColor = progressTrackColor,
            )
        }
    }
}

@Composable
private fun resolveStatusLabelAndColor(status: DownloadTaskStatus): Pair<String, Color> =
    when (status) {
        DownloadTaskStatus.QUEUED ->
            stringResource(R.string.download_task_queued) to MaterialTheme.colorScheme.secondary
        DownloadTaskStatus.RUNNING ->
            stringResource(R.string.download_task_running) to MaterialTheme.colorScheme.primary
        DownloadTaskStatus.PAUSED ->
            stringResource(R.string.download_task_paused) to MaterialTheme.colorScheme.outline
        DownloadTaskStatus.SAVING ->
            stringResource(R.string.download_task_saving) to MaterialTheme.colorScheme.tertiary
        DownloadTaskStatus.RETRYING ->
            stringResource(R.string.download_task_retrying) to MaterialTheme.colorScheme.secondary
        DownloadTaskStatus.FAILED ->
            stringResource(R.string.download_task_failed) to MaterialTheme.colorScheme.error
        DownloadTaskStatus.CANCELLING ->
            stringResource(R.string.download_task_cancelling) to MaterialTheme.colorScheme.error
        DownloadTaskStatus.COMPLETED ->
            stringResource(R.string.download_task_completed) to MaterialTheme.colorScheme.primary
    }

@Composable
private fun DownloadTaskActionRow(
    primaryAction: DownloadTaskPrimaryAction,
    showCancel: Boolean,
    isOperating: Boolean,
    onPause: () -> Unit,
    onResume: () -> Unit,
    onCancel: () -> Unit,
    onDelete: () -> Unit,
    onInstall: () -> Unit,
    onRetryCleanup: () -> Unit,
) {
    val destructiveTextColor =
        if (!isOperating) MaterialTheme.colorScheme.error
        else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f)

    Row(
        modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End),
    ) {
        when (primaryAction) {
            DownloadTaskPrimaryAction.Resuming ->
                SpinnerActionTextButton(R.string.download_task_resuming)
            DownloadTaskPrimaryAction.Pause -> {
                TextButton(
                    onClick = onPause,
                    enabled = !isOperating,
                    modifier = Modifier.heightIn(min = 48.dp),
                ) {
                    Text(stringResource(R.string.download_task_pause))
                }
            }
            DownloadTaskPrimaryAction.Resume -> {
                TextButton(
                    onClick = onResume,
                    enabled = !isOperating,
                    modifier = Modifier.heightIn(min = 48.dp),
                ) {
                    Text(stringResource(R.string.download_task_resume))
                }
            }
            DownloadTaskPrimaryAction.Saving ->
                SpinnerActionTextButton(R.string.download_task_saving)
            DownloadTaskPrimaryAction.RetryCleanup -> {
                TextButton(
                    onClick = onRetryCleanup,
                    enabled = !isOperating,
                    modifier = Modifier.heightIn(min = 48.dp),
                ) {
                    Text(stringResource(R.string.download_task_cleanup_retry))
                }
            }
            DownloadTaskPrimaryAction.Cancelling ->
                SpinnerActionTextButton(R.string.download_task_cancelling)
            is DownloadTaskPrimaryAction.Completed -> {
                TextButton(
                    onClick = onDelete,
                    enabled = !isOperating,
                    modifier = Modifier.heightIn(min = 48.dp),
                ) {
                    Text(
                        text = stringResource(R.string.download_task_delete),
                        color = destructiveTextColor,
                    )
                }
                if (primaryAction.canInstall) {
                    TextButton(
                        onClick = onInstall,
                        enabled = !isOperating,
                        modifier = Modifier.heightIn(min = 48.dp),
                    ) {
                        Text(stringResource(R.string.download_task_install))
                    }
                }
            }
        }

        if (showCancel) {
            TextButton(
                onClick = onCancel,
                enabled = !isOperating,
                modifier = Modifier.heightIn(min = 48.dp),
            ) {
                Text(
                    text = stringResource(R.string.download_task_cancel),
                    color = destructiveTextColor,
                )
            }
        }
    }
}

@Composable
private fun SpinnerActionTextButton(@StringRes labelRes: Int) {
    TextButton(
        onClick = {},
        enabled = false,
        modifier = Modifier.heightIn(min = 48.dp),
    ) {
        CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
        Spacer(modifier = Modifier.width(6.dp))
        Text(stringResource(labelRes))
    }
}
