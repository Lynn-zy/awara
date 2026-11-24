package me.rerere.awara.ui.component.player

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Repeat
import androidx.compose.material.icons.outlined.RepeatOne
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import me.rerere.awara.R

/** 全屏循环面板（自动重播与 A-B 循环）操作后的轻提示状态。 */
internal sealed interface PlayerLoopFeedback {
    val version: Int

    data class AutoReplay(val enabled: Boolean, override val version: Int) : PlayerLoopFeedback

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

/**
 * 全屏播放器顶部控制栏的循环入口与紧凑弹出面板（集成全局持久化自动重播与会话级 A-B 循环）。
 *
 * @param loopPlayEnabled 当前是否开启单曲自动重播
 * @param abLoop 当前视频的 A-B 区间状态
 * @param canSetAbLoop 当前视频是否就绪且支持随机定位（未就绪时禁用新设 A/B 点）
 * @param expanded 循环菜单是否展开
 * @param onExpandedChange 切换面板展开状态回调
 * @param onToggleLoopPlay 切换自动重播开关回调
 * @param onSetPointA 将当前播放进度设为 A 点回调
 * @param onTogglePointB 切换 B 点回调：未设 B 点时记录当前进度，已设 B 点时取消 B 点并保留 A 点
 * @param onClearAbLoop 清除当前 A-B 区间回调
 */
@Composable
internal fun PlayerLoopSelector(
    loopPlayEnabled: Boolean,
    abLoop: PlayerAbLoopState,
    canSetAbLoop: Boolean,
    expanded: Boolean,
    onExpandedChange: (Boolean) -> Unit,
    onToggleLoopPlay: () -> Unit,
    onSetPointA: () -> Unit,
    onTogglePointB: () -> Unit,
    onClearAbLoop: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val dimensions = LocalPlayerControlDimensions.current
    val isHighlighted = loopPlayEnabled || abLoop.hasStart
    val buttonDescription = stringResource(R.string.player_loop_menu_action)
    val badgeText =
        when {
            abLoop.isActive -> "AB"
            abLoop.hasStart -> "A"
            else -> null
        }

    Box(modifier = modifier) {
        Box(contentAlignment = Alignment.Center) {
            PlayerControlIconButton(
                icon =
                    if (loopPlayEnabled && !abLoop.hasStart) Icons.Outlined.RepeatOne
                    else Icons.Outlined.Repeat,
                contentDescription = buttonDescription,
                iconSize = dimensions.scaleIconSize,
                tint = if (isHighlighted) PLAYER_MENU_SELECTED_COLOR else Color.White,
                onClick = { onExpandedChange(!expanded) },
                modifier =
                    Modifier.testTag("player_loop_selector").semantics {
                        contentDescription = buttonDescription
                    },
            )
            if (badgeText != null) {
                Box(
                    modifier =
                        Modifier.align(Alignment.TopEnd)
                            .padding(top = 5.dp, end = 4.dp)
                            .background(
                                color = PLAYER_AB_LOOP_TRACK_COLOR,
                                shape = RoundedCornerShape(4.dp),
                            )
                            .padding(horizontal = 3.dp, vertical = 0.5.dp)
                            .testTag("player_loop_ab_badge"),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = badgeText,
                        color = Color(0xFF1A1C22),
                        fontSize = 8.sp,
                        lineHeight = 9.sp,
                        fontWeight = FontWeight.Bold,
                        maxLines = 1,
                    )
                }
            }
        }

        PlayerCompactDropdownMenu(
            expanded = expanded,
            onDismissRequest = { onExpandedChange(false) },
            preferAbove = false,
            modifier = Modifier.testTag("player_loop_menu"),
        ) {
            PlayerCompactDropdownMenuItem(
                text = stringResource(R.string.player_auto_replay),
                selected = loopPlayEnabled,
                minWidth = 136.dp,
                onClick = {
                    onToggleLoopPlay()
                    onExpandedChange(false)
                },
                modifier = Modifier.testTag("player_loop_auto_replay_item"),
            )

            Box(
                modifier =
                    Modifier.fillMaxWidth()
                        .padding(horizontal = PLAYER_MENU_HORIZONTAL_PADDING, vertical = 3.dp)
                        .height(1.dp)
                        .background(Color.White.copy(alpha = 0.12f))
            )

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

/** 全屏循环面板操作反馈浮层，样式与缩放档位浮层保持统一。 */
@Composable
internal fun PlayerLoopFeedbackOverlay(
    feedback: PlayerLoopFeedback,
    modifier: Modifier = Modifier,
) {
    val label =
        when (feedback) {
            is PlayerLoopFeedback.AutoReplay ->
                stringResource(
                    if (feedback.enabled) R.string.player_auto_replay_on
                    else R.string.player_auto_replay_off
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
