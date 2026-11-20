package me.rerere.awara.ui.component.player

import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ImageNotSupported
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import kotlin.math.roundToInt
import kotlinx.coroutines.delay
import me.rerere.awara.R

/** 圆球保持紧凑，独立于 Material3 为滑块保留的最小布局尺寸。 */
internal val PlayerSeekThumbDiameter = 12.dp

/** 与当前 Material3 的最小滑块布局一致，供槽内居中和预览轨道内缩共同使用。 */
internal val PlayerSeekThumbLayoutSize = 20.dp

/** 缩略图开关与显示位置独立保存；关闭图片时仍保留时间定位反馈。 */
internal const val PREFERENCE_KEY_SEEK_THUMBNAIL_ENABLED = "setting.seek_thumbnail_enabled"
internal const val PREFERENCE_KEY_SEEK_PREVIEW_POSITION = "setting.seek_preview_position"

/** 预览位置使用稳定键持久化，缺失或无效值保持当前随滑块的默认体验。 */
internal enum class SeekPreviewPosition(val key: String) {
    Center("center"),
    FollowSlider("follow_slider");

    companion object {
        fun fromKey(key: String?): SeekPreviewPosition =
            entries.firstOrNull { it.key == key } ?: FollowSlider
    }
}

/** 缩略图加载圈延迟显示门控（毫秒），避免快速本地取帧期间出现转圈闪烁。 */
internal const val SEEK_THUMBNAIL_LOADING_DELAY_MS = 150L

/** 预览框长边最大尺寸（dp）。 */
internal const val SEEK_PREVIEW_MAX_LONG_SIDE_DP = 160f

/** 预览框短边最大尺寸（dp），兼顾横竖屏及近似方形视频的视觉面积均衡。 */
internal const val SEEK_PREVIEW_MAX_SHORT_SIDE_DP = 120f

/** 预览框最小可见边长阈值（dp），低于该尺寸时仅保留时间胶囊。 */
internal const val SEEK_PREVIEW_MIN_SIDE_DP = 24f

/**
 * 解析拖动预览框的目标宽高比，优先采用播放器已知视频尺寸，其次回退已解码帧尺寸，默认 16:9。
 *
 * @param videoSize 播放器上报的视频宽高
 * @param frameWidth 当前缩略图帧像素宽度
 * @param frameHeight 当前缩略图帧像素高度
 * @return 安全夹紧后的宽高比（width / height）
 */
internal fun resolveSeekPreviewAspectRatio(
    videoSize: PlayerState.VideoSize = PlayerState.VideoSize.UNKNOWN,
    frameWidth: Int = 0,
    frameHeight: Int = 0,
): Float {
    val rawRatio =
        when {
            videoSize.width > 0 && videoSize.height > 0 ->
                videoSize.width.toFloat() / videoSize.height.toFloat()
            frameWidth > 0 && frameHeight > 0 -> frameWidth.toFloat() / frameHeight.toFloat()
            else -> 16f / 9f
        }
    return if (rawRatio.isFinite() && rawRatio > 0f) {
        rawRatio.coerceIn(1f / 3f, 3f)
    } else {
        16f / 9f
    }
}

/**
 * 根据视频宽高比与可用剩余空间计算预览区域宽高（dp），使横屏与竖屏视频均无黑边贴合展示。
 *
 * @param aspectRatio 视频宽高比（width / height）
 * @param maxWidthDp 父容器可用最大宽度（dp）
 * @param maxHeightDp 父容器可用最大高度（dp）
 * @param maxLongSideDp 预览框理想长边上限（dp）
 * @param maxShortSideDp 预览框理想短边上限（dp）
 * @return 适配后的 `(widthDp, heightDp)`；空间不足以容纳最小可见阈值时返回 `null`
 */
internal fun calculateSeekPreviewFrameSize(
    aspectRatio: Float,
    maxWidthDp: Float,
    maxHeightDp: Float,
    maxLongSideDp: Float = SEEK_PREVIEW_MAX_LONG_SIDE_DP,
    maxShortSideDp: Float = SEEK_PREVIEW_MAX_SHORT_SIDE_DP,
): Pair<Float, Float>? {
    if (
        !aspectRatio.isFinite() ||
            aspectRatio <= 0f ||
            !maxWidthDp.isFinite() ||
            !maxHeightDp.isFinite() ||
            maxWidthDp <= 0f ||
            maxHeightDp <= 0f
    ) {
        return null
    }
    // 先按视频比例推导理想预览尺寸（例如 16:9 为 160x90，9:16 为 90x160，1:1 为 120x120）
    val (idealWidth, idealHeight) =
        if (aspectRatio >= 1f) {
            val candidateHeight = maxLongSideDp / aspectRatio
            if (candidateHeight <= maxShortSideDp) {
                maxLongSideDp to candidateHeight
            } else {
                (maxShortSideDp * aspectRatio) to maxShortSideDp
            }
        } else {
            val candidateWidth = maxLongSideDp * aspectRatio
            if (candidateWidth <= maxShortSideDp) {
                candidateWidth to maxLongSideDp
            } else {
                maxShortSideDp to (maxShortSideDp / aspectRatio)
            }
        }
    // 等比缩放装入当前播放器剩余空间，绝不挤压上下控制栏与时间文字
    val fitScale = minOf(maxWidthDp / idealWidth, maxHeightDp / idealHeight, 1f)
    val finalWidth = idealWidth * fitScale
    val finalHeight = idealHeight * fitScale
    if (finalWidth < SEEK_PREVIEW_MIN_SIDE_DP || finalHeight < SEEK_PREVIEW_MIN_SIDE_DP) {
        return null
    }
    return finalWidth to finalHeight
}

/** 在组件作用域内复用串行调度器与本地解码会话；媒体代次变化时清缓存并释放提取器，非拖动期间不启动取帧。 */
@Composable
internal fun rememberSeekThumbnail(
    sourceUri: String?,
    mediaKey: Any,
    active: Boolean,
    positionMs: Long,
    durationMs: Long,
): SeekThumbnailState<Bitmap> {
    val context = LocalContext.current.applicationContext
    val scope = rememberCoroutineScope()
    val localSession = remember(context) { LocalSeekThumbnailSession.createAndroid(context) }
    val loader =
        remember(scope, context, localSession) {
            SeekThumbnailLoader(
                scope = scope,
                frameBytes = { frame: Bitmap -> frame.allocationByteCount },
                loadFrame = { request ->
                    if (request.isNetwork) loadNetworkSeekThumbnail(context, request)
                    else localSession.loadFrame(request)
                },
                onResetSource = { localSession.resetAsync() },
            )
        }
    DisposableEffect(loader, localSession) {
        onDispose {
            loader.close()
            localSession.closeAsync()
        }
    }
    DisposableEffect(loader, mediaKey) {
        loader.reset()
        onDispose { loader.reset() }
    }
    val request = if (active) seekThumbnailRequest(sourceUri, positionMs, durationMs) else null
    SideEffect { loader.request(request) }
    val result by loader.state.collectAsState()
    // 新目标首次组合时立即清空旧帧，避免 SideEffect 执行前短暂串图。
    return if (result.request == request) result
    else SeekThumbnailState(request, loading = request != null)
}

/** 预览可居中或贴在进度条上方随动；自适应视频宽高比，空间不足时等比缩图，不改变下方控件位置。 */
@Composable
internal fun PlayerSeekPreview(
    targetTime: String,
    deltaTime: String,
    thumbnail: SeekThumbnailState<Bitmap>,
    fraction: Float,
    modifier: Modifier = Modifier,
    videoSize: PlayerState.VideoSize = PlayerState.VideoSize.UNKNOWN,
    position: SeekPreviewPosition = SeekPreviewPosition.FollowSlider,
    trackLeft: Float = 0f,
    trackWidth: Float? = null,
) {
    // 跨秒未命中缓存时立即清空旧帧，仅对加载圈施加 150ms 延迟门控：快速本地取帧期间不闪转圈，慢速网络源超时后稳定展示转圈
    val isWaitingFrame = thumbnail.loading && thumbnail.frame == null
    var showDelayedLoading by remember { mutableStateOf(false) }
    LaunchedEffect(isWaitingFrame) {
        if (!isWaitingFrame) {
            showDelayedLoading = false
        } else {
            delay(SEEK_THUMBNAIL_LOADING_DELAY_MS)
            showDelayedLoading = true
        }
    }

    val aspectRatio =
        resolveSeekPreviewAspectRatio(
            videoSize = videoSize,
            frameWidth = thumbnail.frame?.width ?: 0,
            frameHeight = thumbnail.frame?.height ?: 0,
        )

    Layout(
        modifier = modifier.fillMaxSize().padding(bottom = 4.dp),
        content = {
            Column(
                modifier = Modifier.wrapContentWidth().testTag("player_seek_preview_bubble"),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                val showFrame =
                    thumbnail.request != null &&
                        (!thumbnail.request.isNetwork ||
                            thumbnail.loading ||
                            thumbnail.frame != null)
                if (showFrame) {
                    // 先测量非加权的时间文字，再把真正剩余的高度分配给自适应比例的预览画面
                    BoxWithConstraints(
                        modifier = Modifier.weight(1f, fill = false),
                        contentAlignment = Alignment.Center,
                    ) {
                        val frameSizeDp =
                            calculateSeekPreviewFrameSize(
                                aspectRatio = aspectRatio,
                                maxWidthDp = maxWidth.value,
                                maxHeightDp = maxHeight.value,
                            )
                        if (frameSizeDp != null) {
                            Box(
                                modifier =
                                    Modifier.size(frameSizeDp.first.dp, frameSizeDp.second.dp)
                                        .clip(RoundedCornerShape(4.dp))
                                        .background(Color.Black.copy(alpha = 0.8f))
                                        .testTag("player_seek_thumbnail"),
                                contentAlignment = Alignment.Center,
                            ) {
                                val frame = thumbnail.frame
                                when {
                                    frame != null ->
                                        Image(
                                            bitmap = frame.asImageBitmap(),
                                            contentDescription =
                                                stringResource(R.string.player_seek_keyframe),
                                            contentScale = ContentScale.Fit,
                                            modifier = Modifier.fillMaxSize(),
                                        )
                                    thumbnail.loading -> {
                                        if (showDelayedLoading) {
                                            CircularProgressIndicator(
                                                modifier =
                                                    Modifier.size(16.dp)
                                                        .testTag("player_seek_thumbnail_loading"),
                                                color = Color.White,
                                                strokeWidth = 2.dp,
                                            )
                                        }
                                    }
                                    else ->
                                        Icon(
                                            imageVector = Icons.Outlined.ImageNotSupported,
                                            contentDescription =
                                                stringResource(
                                                    R.string.player_seek_thumbnail_unavailable
                                                ),
                                            tint = Color.White,
                                            modifier =
                                                Modifier.size(20.dp)
                                                    .testTag("player_seek_thumbnail_unavailable"),
                                        )
                                }
                            }
                        }
                    }
                }
                Text(
                    text = "$targetTime ($deltaTime)",
                    modifier =
                        Modifier.background(
                                Color.Black.copy(alpha = 0.8f),
                                RoundedCornerShape(4.dp),
                            )
                            .padding(horizontal = 6.dp, vertical = 2.dp)
                            .testTag("player_seek_preview_time"),
                    color = Color.White,
                    style = MaterialTheme.typography.titleSmall,
                    textAlign = TextAlign.Center,
                    maxLines = 2,
                )
            }
        },
    ) { measurables, constraints ->
        val bubble =
            measurables
                .single()
                .measure(
                    constraints.copy(
                        minWidth = 0,
                        minHeight = 0,
                        maxWidth = minOf(constraints.maxWidth, 160.dp.roundToPx()),
                    )
                )
        val left =
            calculateSeekPreviewLeft(
                containerWidth = constraints.maxWidth,
                previewWidth = bubble.width,
                fraction = fraction,
                thumbInset = PlayerSeekThumbLayoutSize.toPx() / 2f,
                isRtl = layoutDirection == LayoutDirection.Rtl,
                position = position,
                trackLeft = trackLeft,
                trackWidth = trackWidth ?: constraints.maxWidth.toFloat(),
            )
        val availableHeight = (constraints.maxHeight - bubble.height).coerceAtLeast(0)
        val top =
            if (position == SeekPreviewPosition.Center) availableHeight / 2 else availableHeight
        layout(constraints.maxWidth, constraints.maxHeight) {
            // left 已是物理坐标，使用 place 而非 placeRelative，避免 RTL 再次镜像。
            bubble.place(left, top)
        }
    }
}

/** 按滑块有效轨道计算预览左边界；左右端点夹紧，RTL 仅镜像一次。 */
internal fun calculateSeekPreviewLeft(
    containerWidth: Int,
    previewWidth: Int,
    fraction: Float,
    thumbInset: Float,
    isRtl: Boolean,
    position: SeekPreviewPosition = SeekPreviewPosition.FollowSlider,
    trackLeft: Float = 0f,
    trackWidth: Float = containerWidth.toFloat(),
): Int {
    val width = containerWidth.coerceAtLeast(0)
    val bubbleWidth = previewWidth.coerceIn(0, width)
    if (position == SeekPreviewPosition.Center) return (width - bubbleWidth) / 2
    val trackStart = if (trackLeft.isFinite()) trackLeft.coerceIn(0f, width.toFloat()) else 0f
    val availableTrackWidth =
        if (trackWidth.isFinite()) trackWidth.coerceIn(0f, width - trackStart) else 0f
    val inset = if (thumbInset.isFinite()) thumbInset.coerceIn(0f, availableTrackWidth / 2f) else 0f
    val progress = if (fraction.isFinite()) fraction.coerceIn(0f, 1f) else 0f
    val physicalProgress = if (isRtl) 1f - progress else progress
    val thumbCenter = trackStart + inset + (availableTrackWidth - 2f * inset) * physicalProgress
    return (thumbCenter - bubbleWidth / 2f).roundToInt().coerceIn(0, width - bubbleWidth)
}
