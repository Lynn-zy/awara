package me.rerere.awara.ui.component.player

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ArrowBack
import androidx.compose.material.icons.outlined.AspectRatio
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.FitScreen
import androidx.compose.material.icons.outlined.Fullscreen
import androidx.compose.material.icons.outlined.FullscreenExit
import androidx.compose.material.icons.outlined.RestartAlt
import androidx.compose.material.icons.outlined.ZoomOutMap
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.LocalMinimumInteractiveComponentEnforcement
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupPositionProvider
import androidx.compose.ui.window.PopupProperties
import me.rerere.awara.R

/**
 * 播放器控制栏与下拉选择面板尺寸规格，统一各 Material 图标的光学留白补偿并区分半屏紧凑态与全屏等比放大态。
 */
internal data class PlayerControlDimensions(
    val slotSize: Dp,
    val backIconSize: Dp,
    val scaleIconSize: Dp,
    val rotateIconSize: Dp,
    val playIconSize: Dp,
    val fullscreenIconSize: Dp,
    val actionTextSize: TextUnit,
    val timeTextSize: TextUnit,
    val trackHeight: Dp,
    val thumbSize: Dp,
    val menuItemHeight: Dp,
    val menuItemTextSize: TextUnit,
    val menuCheckIconSize: Dp,
    val menuItemMinWidth: Dp,
)

private val HalfscreenPlayerControlDimensions =
    PlayerControlDimensions(
        slotSize = 38.dp,
        backIconSize = 20.dp,
        scaleIconSize = 19.dp,
        rotateIconSize = 16.dp,
        playIconSize = 21.dp,
        fullscreenIconSize = 24.dp,
        actionTextSize = 13.5.sp,
        timeTextSize = 12.sp,
        trackHeight = 2.dp,
        thumbSize = PlayerSeekThumbDiameter,
        menuItemHeight = 32.dp,
        menuItemTextSize = 13.5.sp,
        menuCheckIconSize = 15.dp,
        menuItemMinWidth = 76.dp,
    )

private val FullscreenPlayerControlDimensions =
    PlayerControlDimensions(
        slotSize = 46.dp,
        backIconSize = 24.dp,
        scaleIconSize = 23.dp,
        rotateIconSize = 19.dp,
        playIconSize = 25.dp,
        fullscreenIconSize = 28.dp,
        actionTextSize = 16.sp,
        timeTextSize = 13.sp,
        trackHeight = 3.dp,
        thumbSize = 13.dp,
        menuItemHeight = 38.dp,
        menuItemTextSize = 16.sp,
        menuCheckIconSize = 17.dp,
        menuItemMinWidth = 88.dp,
    )

internal val LocalPlayerControlDimensions =
    staticCompositionLocalOf { HalfscreenPlayerControlDimensions }

/** 根据是否全屏解析控制栏、进度条及选择面板尺寸规格。 */
internal fun resolvePlayerControlDimensions(fullscreen: Boolean): PlayerControlDimensions =
    if (fullscreen) FullscreenPlayerControlDimensions else HalfscreenPlayerControlDimensions

/** 播放器纯平提示浮层统一底色、前景色、圆角及长按倍速距顶部安全区间距（无毛玻璃、无描边、无重阴影）。 */
internal val PLAYER_OVERLAY_BACKGROUND = Color.Black.copy(alpha = 0.30f)
internal val PLAYER_OVERLAY_FOREGROUND = Color(0xFFF2F4F8)
internal val PLAYER_OVERLAY_CORNER_RADIUS = 6.dp
internal val PLAYER_SPEED_FEEDBACK_TOP_PADDING_HALF = 12.dp
internal val PLAYER_SPEED_FEEDBACK_TOP_PADDING_FULLSCREEN = 18.dp

/** 播放器画质与倍速选择面板公共容器外观（始终采用 M3 简约深色表面）。 */
internal val PLAYER_MENU_VERTICAL_PADDING = 4.dp
private val PLAYER_MENU_HORIZONTAL_PADDING = 10.dp
private val PLAYER_MENU_MAX_WIDTH = 240.dp
private val PLAYER_MENU_CHECK_GAP = 10.dp
private val PLAYER_MENU_CORNER_RADIUS = 8.dp
private val PLAYER_MENU_SURFACE_COLOR = Color(0xFF252830)
private val PLAYER_MENU_CONTENT_COLOR = Color(0xFFEBEDF2)
private val PLAYER_MENU_SELECTED_COLOR = Color(0xFF8AB4F8)

/**
 * 播放器控制栏统一图标按钮，依据当前控制栏规格适配热区槽位与光学图标尺寸。
 *
 * @param icon 图标矢量图形。
 * @param contentDescription 无障碍语义描述。
 * @param iconSize 图标光学尺寸。
 * @param onClick 点击回调。
 * @param modifier 外部修饰符。
 * @param slotSize 热区槽尺寸，默认读取当前 [LocalPlayerControlDimensions] 规格。
 */
@Composable
internal fun PlayerControlIconButton(
    icon: ImageVector,
    contentDescription: String?,
    iconSize: Dp,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    slotSize: Dp = LocalPlayerControlDimensions.current.slotSize,
    slotWidth: Dp = slotSize,
    tint: Color = LocalContentColor.current,
    enabled: Boolean = true,
) {
    // 关闭 M3 默认 48dp 隐式扩张，确保半屏 38dp / 全屏 46dp 槽位严格对齐设计规格。
    CompositionLocalProvider(LocalMinimumInteractiveComponentEnforcement provides false) {
        IconButton(
            onClick = onClick,
            enabled = enabled,
            modifier = modifier.width(slotWidth).height(slotSize),
        ) {
            Icon(
                imageVector = icon,
                contentDescription = contentDescription,
                tint = if (enabled) tint else tint.copy(alpha = 0.38f),
                modifier = Modifier.size(iconSize),
            )
        }
    }
}

/**
 * 播放器左上角返回图标按钮（半屏 20dp / 全屏 24dp 光学尺寸）。
 *
 * @param onClick 点击返回回调。
 * @param modifier 外部修饰符。
 */
@Composable
internal fun PlayerBackIconButton(onClick: () -> Unit, modifier: Modifier = Modifier) {
    val dimensions = LocalPlayerControlDimensions.current
    PlayerControlIconButton(
        icon = Icons.Outlined.ArrowBack,
        contentDescription = "Back",
        iconSize = dimensions.backIconSize,
        onClick = onClick,
        modifier = modifier,
        slotSize = dimensions.slotSize,
    )
}

/**
 * 播放器右下角全屏切换图标按钮（半屏 24dp / 全屏 28dp 光学尺寸）。
 *
 * @param fullscreen 当前是否处于全屏状态。
 * @param onClick 点击切换全屏回调。
 * @param modifier 外部修饰符。
 */
@Composable
internal fun PlayerFullscreenIconButton(
    fullscreen: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val dimensions = LocalPlayerControlDimensions.current
    PlayerControlIconButton(
        icon = if (!fullscreen) Icons.Outlined.Fullscreen else Icons.Outlined.FullscreenExit,
        contentDescription = "Fullscreen",
        iconSize = dimensions.fullscreenIconSize,
        onClick = onClick,
        modifier = modifier,
        slotSize = dimensions.slotSize,
    )
}

/**
 * 播放器控制栏统一文字动作按钮（半屏 38dp / 13.5sp，全屏 46dp / 16sp），用于清晰度与倍速等切换入口。
 *
 * @param text 按钮显示文案。
 * @param onClick 点击回调。
 * @param modifier 外部修饰符。
 * @param enabled 是否允许交互。
 */
@Composable
internal fun PlayerActionTextButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    val dimensions = LocalPlayerControlDimensions.current
    // 避开 Material3 TextButton 内置 58dp×40dp 最小尺寸限制，按当前规格紧凑布局避免挤占进度条。
    CompositionLocalProvider(LocalMinimumInteractiveComponentEnforcement provides false) {
        Box(
            modifier =
                modifier
                    .height(dimensions.slotSize)
                    .widthIn(min = dimensions.slotSize)
                    .clip(RoundedCornerShape(6.dp))
                    .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
                    .padding(horizontal = 8.dp),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = text,
                color = if (enabled) Color.White else Color.White.copy(alpha = 0.38f),
                fontSize = dimensions.actionTextSize,
                fontWeight = FontWeight.Medium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/** 根据当前全屏画面缩放状态返回对应的标准 Material 图标。 */
internal fun resolveScaleModeIcon(
    scaleMode: PlayerScaleMode,
    isCustomTransform: Boolean,
): ImageVector =
    if (isCustomTransform) {
        Icons.Outlined.RestartAlt
    } else {
        when (scaleMode) {
            PlayerScaleMode.FIT -> Icons.Outlined.FitScreen
            PlayerScaleMode.FILL -> Icons.Outlined.ZoomOutMap
            PlayerScaleMode.ORIGINAL -> Icons.Outlined.AspectRatio
        }
    }

/** 仅在媒体已就绪可交互且未出错时展示右上角播完模式按钮，避免初始 loading 期间提前露空。 */
internal fun shouldShowEndBehaviorButton(
    hasMediaItem: Boolean,
    blockLoadingInteraction: Boolean,
    playbackError: PlayerState.PlaybackError?,
): Boolean = hasMediaItem && !blockLoadingInteraction && playbackError == null

/** 数值标签保持四档约定，常驻菜单不展示或持久化长按中的临时速度。 */
internal fun playbackSpeedLabel(speed: Float): String = if (speed == 2f) "2x" else "${speed}x"

/**
 * 紧凑下拉菜单定位器：优先贴合锚点右边缘（RTL 时左边缘），按 [preferAbove] 优先在锚点上方或下方展开，
 * 空间不足时自动翻转并夹紧至窗口安全边距内。
 */
internal class PlayerCompactDropdownPositionProvider(
    private val verticalOffsetPx: Int,
    private val windowMarginPx: Int,
    private val preferAbove: Boolean,
) : PopupPositionProvider {
    override fun calculatePosition(
        anchorBounds: IntRect,
        windowSize: IntSize,
        layoutDirection: LayoutDirection,
        popupContentSize: IntSize,
    ): IntOffset {
        val preferredX =
            if (layoutDirection == LayoutDirection.Rtl) {
                anchorBounds.left
            } else {
                anchorBounds.right - popupContentSize.width
            }
        val maxX =
            (windowSize.width - popupContentSize.width - windowMarginPx)
                .coerceAtLeast(windowMarginPx)
        val x = preferredX.coerceIn(windowMarginPx, maxX)

        val belowY = anchorBounds.bottom + verticalOffsetPx
        val aboveY = anchorBounds.top - verticalOffsetPx - popupContentSize.height
        val fitsBelow = belowY + popupContentSize.height <= windowSize.height - windowMarginPx
        val fitsAbove = aboveY >= windowMarginPx
        val rawY =
            if (preferAbove) {
                when {
                    fitsAbove -> aboveY
                    fitsBelow -> belowY
                    anchorBounds.top >= windowSize.height - anchorBounds.bottom -> aboveY
                    else -> belowY
                }
            } else {
                when {
                    fitsBelow -> belowY
                    fitsAbove -> aboveY
                    windowSize.height - anchorBounds.bottom >= anchorBounds.top -> belowY
                    else -> aboveY
                }
            }
        val maxY =
            (windowSize.height - popupContentSize.height - windowMarginPx)
                .coerceAtLeast(windowMarginPx)
        val y = rawY.coerceIn(windowMarginPx, maxY)
        return IntOffset(x, y)
    }
}

/**
 * 播放器紧凑选择面板（画质/倍速共用），始终采用 M3 简约深色表面与 4dp 竖向内边距。
 *
 * @param expanded 是否展开面板。
 * @param onDismissRequest 点击外部或返回键收起时的回调。
 * @param modifier 作用于面板容器 [Surface] 的修饰符（可用于挂载测试标签）。
 * @param preferAbove 是否优先在锚点按钮上方展开（底部倍速栏传 `true`，顶部画质栏传 `false`）。
 * @param content 面板列表项内容。
 */
@Composable
internal fun PlayerCompactDropdownMenu(
    expanded: Boolean,
    onDismissRequest: () -> Unit,
    modifier: Modifier = Modifier,
    preferAbove: Boolean = false,
    content: @Composable ColumnScope.() -> Unit,
) {
    if (!expanded) return
    val dimensions = LocalPlayerControlDimensions.current
    val density = LocalDensity.current
    val positionProvider =
        remember(density, preferAbove) {
            PlayerCompactDropdownPositionProvider(
                verticalOffsetPx = with(density) { 2.dp.roundToPx() },
                windowMarginPx = with(density) { 8.dp.roundToPx() },
                preferAbove = preferAbove,
            )
        }
    Popup(
        popupPositionProvider = positionProvider,
        onDismissRequest = onDismissRequest,
        properties = PopupProperties(focusable = true),
    ) {
        // Popup 使用独立子窗口组合，显式透传当前控制栏尺寸规格确保半屏与全屏面板各自适配。
        CompositionLocalProvider(LocalPlayerControlDimensions provides dimensions) {
            Surface(
                modifier = modifier,
                shape = RoundedCornerShape(PLAYER_MENU_CORNER_RADIUS),
                color = PLAYER_MENU_SURFACE_COLOR,
                contentColor = PLAYER_MENU_CONTENT_COLOR,
                shadowElevation = 2.dp,
            ) {
                Column(
                    modifier =
                        Modifier.width(IntrinsicSize.Max)
                            .widthIn(max = PLAYER_MENU_MAX_WIDTH)
                            .padding(vertical = PLAYER_MENU_VERTICAL_PADDING)
                            .verticalScroll(rememberScrollState()),
                    content = content,
                )
            }
        }
    }
}

/**
 * 播放器紧凑选择面板单行选项，行高与字号跟随当前 [LocalPlayerControlDimensions]（半屏 32dp/13.5sp，全屏 38dp/16sp）。
 *
 * @param text 选项显示文案。
 * @param onClick 点击选项回调。
 * @param modifier 外部修饰符。
 * @param selected 是否为当前选中项（选中时文字高亮并展示右侧对勾）。
 * @param enabled 是否允许点击交互。
 * @param minWidth 最小行宽，保证短标签（如 `2x`）具备舒适点击宽度。
 */
@Composable
internal fun PlayerCompactDropdownMenuItem(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    selected: Boolean = false,
    enabled: Boolean = true,
    minWidth: Dp = LocalPlayerControlDimensions.current.menuItemMinWidth,
) {
    val dimensions = LocalPlayerControlDimensions.current
    val textColor =
        when {
            !enabled -> PLAYER_MENU_CONTENT_COLOR.copy(alpha = 0.48f)
            selected -> PLAYER_MENU_SELECTED_COLOR
            else -> PLAYER_MENU_CONTENT_COLOR
        }
    val textMaxWidth =
        PLAYER_MENU_MAX_WIDTH -
            PLAYER_MENU_HORIZONTAL_PADDING * 2 -
            PLAYER_MENU_CHECK_GAP -
            dimensions.menuCheckIconSize
    Row(
        modifier =
            modifier
                .fillMaxWidth()
                .height(dimensions.menuItemHeight)
                .widthIn(min = minWidth)
                .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
                .padding(horizontal = PLAYER_MENU_HORIZONTAL_PADDING),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(
            text = text,
            color = textColor,
            fontSize = dimensions.menuItemTextSize,
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.widthIn(max = textMaxWidth),
        )
        Spacer(modifier = Modifier.width(PLAYER_MENU_CHECK_GAP))
        Box(
            modifier = Modifier.size(dimensions.menuCheckIconSize),
            contentAlignment = Alignment.Center,
        ) {
            if (selected) {
                Icon(
                    imageVector = Icons.Outlined.Check,
                    contentDescription = null,
                    tint = PLAYER_MENU_SELECTED_COLOR,
                    modifier = Modifier.size(dimensions.menuCheckIconSize),
                )
            }
        }
    }
}

@Composable
internal fun PlayerSpeedSelector(
    currentSpeed: Float,
    expanded: Boolean,
    enabled: Boolean,
    onExpandedChange: (Boolean) -> Unit,
    onSpeedSelected: (Float) -> Boolean,
) {
    var failed by remember(expanded) { mutableStateOf(false) }
    val description = stringResource(R.string.player_playback_speed)
    Box {
        PlayerActionTextButton(
            text = playbackSpeedLabel(currentSpeed),
            enabled = enabled,
            onClick = { onExpandedChange(true) },
            modifier =
                Modifier.testTag("player_speed_selector").semantics {
                    contentDescription = description
                },
        )
        PlayerCompactDropdownMenu(
            expanded = expanded,
            onDismissRequest = { onExpandedChange(false) },
            preferAbove = true,
            modifier = Modifier.testTag("player_speed_menu"),
        ) {
            PlayerPlaybackSpeeds.forEach { speed ->
                PlayerCompactDropdownMenuItem(
                    text = playbackSpeedLabel(speed),
                    selected = speed == currentSpeed,
                    onClick = {
                        if (onSpeedSelected(speed)) onExpandedChange(false) else failed = true
                    },
                    modifier = Modifier.testTag("player_speed_option_${speed}"),
                )
            }
            if (failed) {
                PlayerCompactDropdownMenuItem(
                    text = stringResource(R.string.player_speed_change_failed),
                    enabled = false,
                    onClick = {},
                )
            }
        }
    }
}
