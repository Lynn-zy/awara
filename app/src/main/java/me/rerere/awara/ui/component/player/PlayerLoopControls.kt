package me.rerere.awara.ui.component.player

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.QueuePlayNext
import androidx.compose.material.icons.outlined.RepeatOne
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import me.rerere.awara.R
import me.rerere.awara.ui.page.setting.PlaybackEndBehavior

/** 播完模式切换与 A-B 循环操作后的轻提示状态。 */
internal sealed interface PlayerLoopFeedback {
    val version: Int

    data class EndBehavior(
        val behavior: PlaybackEndBehavior,
        override val version: Int,
    ) : PlayerLoopFeedback

    data class AbPointASet(val startMs: Long, override val version: Int) : PlayerLoopFeedback

    data class AbLoopActivated(
        val startMs: Long,
        val endMs: Long,
        override val version: Int,
    ) : PlayerLoopFeedback

    data class AbPointBCleared(override val version: Int) : PlayerLoopFeedback

    data class AbLoopCleared(override val version: Int) : PlayerLoopFeedback

    data class AbInvalidEnd(override val version: Int) : PlayerLoopFeedback
}

/** A-B 循环区间在进度条上的高亮标识颜色（在主色进度条与缓冲层上保持清晰对比）。 */
internal val PLAYER_AB_LOOP_TRACK_COLOR = Color(0xFFFFCA28)

/** 关闭循环（斜杠循环）矢量图标，用于明确表示「播完暂停」而非暂停当前播放。 */
internal val RepeatOffIcon: ImageVector by lazy {
    ImageVector.Builder(
            name = "Outlined.RepeatOff",
            defaultWidth = 24.dp,
            defaultHeight = 24.dp,
            viewportWidth = 24f,
            viewportHeight = 24f,
        )
        .addPath(
            pathData =
                PathParser()
                    .parsePathString(
                        "M2.81 2.81L1.39 4.22 5 7.83V13h2V9.83l8.17 8.17H7v-3l-4 4 4 4v-3h10.17l2.61 2.61 1.41-1.41L2.81 2.81zM17 7v3l4-4-4-4v3H7.83l2 2H17zm0 7.17l2 2V11h-2v3.17z"
                    )
                    .toNodes(),
            fill = SolidColor(Color.White),
        )
        .build()
}

/** 根据播完行为返回对应的纯白状态图标。 */
internal fun resolveEndBehaviorIcon(behavior: PlaybackEndBehavior): ImageVector =
    when (behavior) {
        PlaybackEndBehavior.PAUSE -> RepeatOffIcon
        PlaybackEndBehavior.AUTO_REPLAY -> Icons.Outlined.RepeatOne
        PlaybackEndBehavior.PLAY_NEXT -> Icons.Outlined.QueuePlayNext
    }

/**
 * 播放器右上角的播完行为切换按钮（半屏与全屏均展示），单击循环切换：
 * - 已下载列表：播完暂停 -> 自动重播 -> 播放下一个 -> 播完暂停
 * - 视频详情页：播完暂停 -> 自动重播 -> 播完暂停
 *
 * @param behavior 当前页面实际生效的播完行为
 * @param onCycle 单击切换至下一模式回调
 */
@Composable
internal fun PlayerEndBehaviorButton(
    behavior: PlaybackEndBehavior,
    onCycle: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val dimensions = LocalPlayerControlDimensions.current
    val icon = resolveEndBehaviorIcon(behavior)
    val description =
        stringResource(
            when (behavior) {
                PlaybackEndBehavior.PAUSE -> R.string.player_end_behavior_pause
                PlaybackEndBehavior.AUTO_REPLAY -> R.string.player_auto_replay
                PlaybackEndBehavior.PLAY_NEXT -> R.string.player_end_behavior_play_next
            }
        )
    PlayerControlIconButton(
        icon = icon,
        contentDescription = description,
        iconSize = dimensions.scaleIconSize,
        tint = Color.White,
        onClick = onCycle,
        modifier =
            modifier.testTag("player_end_behavior_button").semantics {
                contentDescription = description
            },
    )
}

/** 返回全屏 A-B 循环按钮内部展示的紧凑字标：仅设 A 点时为 `A-`，其余状态为 `A-B`。 */
internal fun resolveAbLoopButtonLabel(abLoop: PlayerAbLoopState): String =
    if (abLoop.hasStart && !abLoop.isActive) "A-" else "A-B"

/**
 * 全屏播放器顶部控制栏的独立 A-B 循环入口与紧凑弹出面板（仅管理当前会话的 A-B 区间）。
 *
 * 采用独立的 `[A-B]` 圆角框标，与右侧播完模式（斜杠循环 / 单曲循环 / 播放下一个）彻底区分：
 * - 未设置：纯白细描边 + 白色 `A-B`
 * - 仅设 A 点：黄色描边 + 淡黄底 + 黄色 `A-`
 * - 已激活：黄色实底 + 深色 `A-B`，与进度条高亮区间保持同色呼应
 *
 * @param abLoop 当前视频的 A-B 区间状态
 * @param canSetAbLoop 当前视频是否就绪且支持随机定位（未就绪时禁用新设 A/B 点）
 * @param expanded 面板是否展开
 * @param onExpandedChange 切换面板展开状态回调
 * @param onSetPointA 将当前播放进度设为 A 点回调
 * @param onTogglePointB 切换 B 点回调：未设 B 点时记录当前进度，已设 B 点时取消 B 点并保留 A 点
 * @param onClearAbLoop 清除当前 A-B 区间回调
 */
@Composable
internal fun PlayerAbLoopSelector(
    abLoop: PlayerAbLoopState,
    canSetAbLoop: Boolean,
    expanded: Boolean,
    onExpandedChange: (Boolean) -> Unit,
    onSetPointA: () -> Unit,
    onTogglePointB: () -> Unit,
    onClearAbLoop: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val dimensions = LocalPlayerControlDimensions.current
    val buttonDescription = stringResource(R.string.player_ab_loop_action)
    val badgeLabel = resolveAbLoopButtonLabel(abLoop)
    val badgeShape = RoundedCornerShape(4.dp)
    val badgeBackground =
        when {
            abLoop.isActive -> PLAYER_AB_LOOP_TRACK_COLOR
            abLoop.hasStart -> PLAYER_AB_LOOP_TRACK_COLOR.copy(alpha = 0.16f)
            else -> Color.Transparent
        }
    val badgeBorderColor =
        if (abLoop.hasStart) PLAYER_AB_LOOP_TRACK_COLOR else Color.White
    val badgeContentColor =
        when {
            abLoop.isActive -> Color(0xFF1A1C22)
            abLoop.hasStart -> PLAYER_AB_LOOP_TRACK_COLOR
            else -> Color.White
        }

    Box(modifier = modifier) {
        Box(
            modifier =
                Modifier.size(dimensions.slotSize)
                    .clip(RoundedCornerShape(percent = 50))
                    .clickable(role = Role.Button) { onExpandedChange(!expanded) }
                    .testTag("player_ab_loop_selector")
                    .semantics { contentDescription = buttonDescription },
            contentAlignment = Alignment.Center,
        ) {
            Box(
                modifier =
                    Modifier.background(color = badgeBackground, shape = badgeShape)
                        .border(width = 1.5.dp, color = badgeBorderColor, shape = badgeShape)
                        .padding(horizontal = 4.dp, vertical = 2.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = badgeLabel,
                    color = badgeContentColor,
                    fontSize = 10.sp,
                    lineHeight = 11.sp,
                    letterSpacing = (-0.4).sp,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                )
            }
        }

        PlayerCompactDropdownMenu(
            expanded = expanded,
            onDismissRequest = { onExpandedChange(false) },
            preferAbove = false,
            modifier = Modifier.testTag("player_ab_loop_menu"),
        ) {
            val pointALabel =
                abLoop.startMs?.let {
                    stringResource(R.string.player_ab_point_a_value, formatTime(it))
                } ?: stringResource(R.string.player_ab_set_a)
            PlayerCompactDropdownMenuItem(
                text = pointALabel,
                selected = abLoop.hasStart,
                enabled = canSetAbLoop,
                minWidth = 136.dp,
                onClick = {
                    onSetPointA()
                    onExpandedChange(false)
                },
                modifier = Modifier.testTag("player_loop_set_a_item"),
            )

            val pointBLabel =
                abLoop.endMs?.let {
                    stringResource(R.string.player_ab_point_b_value, formatTime(it))
                } ?: stringResource(R.string.player_ab_set_b)
            PlayerCompactDropdownMenuItem(
                text = pointBLabel,
                selected = abLoop.hasEnd,
                enabled = abLoop.hasEnd || (canSetAbLoop && abLoop.hasStart),
                minWidth = 136.dp,
                onClick = {
                    onTogglePointB()
                    onExpandedChange(false)
                },
                modifier = Modifier.testTag("player_loop_set_b_item"),
            )

            PlayerCompactDropdownMenuItem(
                text = stringResource(R.string.player_ab_clear),
                selected = false,
                enabled = abLoop.hasStart,
                minWidth = 136.dp,
                onClick = {
                    onClearAbLoop()
                    onExpandedChange(false)
                },
                modifier = Modifier.testTag("player_loop_clear_ab_item"),
            )
        }
    }
}

/** 播完模式切换与 A-B 循环操作反馈浮层，样式与缩放档位浮层保持统一。 */
@Composable
internal fun PlayerLoopFeedbackOverlay(
    feedback: PlayerLoopFeedback,
    modifier: Modifier = Modifier,
) {
    val label =
        when (feedback) {
            is PlayerLoopFeedback.EndBehavior ->
                stringResource(
                    when (feedback.behavior) {
                        PlaybackEndBehavior.PAUSE -> R.string.player_end_behavior_pause
                        PlaybackEndBehavior.AUTO_REPLAY -> R.string.player_auto_replay
                        PlaybackEndBehavior.PLAY_NEXT -> R.string.player_end_behavior_play_next
                    }
                )
            is PlayerLoopFeedback.AbPointASet ->
                stringResource(R.string.player_ab_point_a_set, formatTime(feedback.startMs))
            is PlayerLoopFeedback.AbLoopActivated ->
                stringResource(
                    R.string.player_ab_loop_active,
                    formatTime(feedback.startMs),
                    formatTime(feedback.endMs),
                )
            is PlayerLoopFeedback.AbPointBCleared ->
                stringResource(R.string.player_ab_point_b_cleared)
            is PlayerLoopFeedback.AbLoopCleared ->
                stringResource(R.string.player_ab_loop_cleared)
            is PlayerLoopFeedback.AbInvalidEnd ->
                stringResource(R.string.player_ab_invalid_end)
        }
    Row(
        modifier =
            modifier
                .background(
                    PLAYER_OVERLAY_BACKGROUND,
                    RoundedCornerShape(PLAYER_OVERLAY_CORNER_RADIUS),
                )
                .padding(horizontal = 14.dp, vertical = 8.dp)
                .testTag("player_loop_feedback"),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Text(
            text = label,
            color = PLAYER_OVERLAY_FOREGROUND,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.Medium,
            maxLines = 1,
        )
    }
}

/** 重新播放历史视频自动定位后的左下角轻提示胶囊。 */
@Composable
internal fun PlayerResumedPositionNotice(
    notice: PlayerState.ResumedPositionNotice,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier =
            modifier
                .background(
                    PLAYER_OVERLAY_BACKGROUND,
                    RoundedCornerShape(PLAYER_OVERLAY_CORNER_RADIUS),
                )
                .padding(horizontal = 12.dp, vertical = 6.dp)
                .testTag("player_resumed_position_notice"),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = stringResource(R.string.player_resumed_position, formatTime(notice.positionMs)),
            color = PLAYER_OVERLAY_FOREGROUND,
            style =
                MaterialTheme.typography.bodySmall.copy(
                    fontWeight = FontWeight.Medium,
                    fontFeatureSettings = "tnum",
                ),
            maxLines = 1,
        )
    }
}
