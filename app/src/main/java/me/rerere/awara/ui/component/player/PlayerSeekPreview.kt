package me.rerere.awara.ui.component.player

import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ImageNotSupported
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
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
import me.rerere.awara.R

/** 滑块直径由 Slider 和预览定位共用，避免轨道内缩与预览锚点不一致。 */
internal val PlayerSeekThumbDiameter = 20.dp

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

/** 在组件作用域内复用串行调度器；媒体代次变化时清缓存，非拖动期间不启动取帧。 */
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
    val loader =
        remember(scope, context) {
            SeekThumbnailLoader(
                scope = scope,
                frameBytes = { frame: Bitmap -> frame.allocationByteCount },
                loadFrame = { request ->
                    if (request.isNetwork) loadNetworkSeekThumbnail(context, request)
                    else loadLocalSeekThumbnail(context, request)
                },
            )
        }
    DisposableEffect(loader) { onDispose { loader.close() } }
    DisposableEffect(loader, mediaKey) {
        loader.reset()
        onDispose { loader.reset() }
    }
    val request = if (active) seekThumbnailRequest(sourceUri, positionMs, durationMs) else null
    SideEffect { loader.request(request) }
    val result by loader.state.collectAsState()
    // 新目标首次组合时也不借用上一帧，避免 SideEffect 执行前短暂串图。
    return if (result.request == request) result
    else SeekThumbnailState(request, loading = request != null)
}

/** 预览可居中或贴在进度条上方随动；空间不足时缩图，不改变下方控件位置。 */
@Composable
internal fun PlayerSeekPreview(
    targetTime: String,
    deltaTime: String,
    thumbnail: SeekThumbnailState<Bitmap>,
    fraction: Float,
    modifier: Modifier = Modifier,
    position: SeekPreviewPosition = SeekPreviewPosition.FollowSlider,
) {
    Layout(
        modifier = modifier.fillMaxSize().padding(bottom = 4.dp),
        content = {
            Column(
                modifier = Modifier.fillMaxWidth().testTag("player_seek_preview_bubble"),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                val showFrame =
                    thumbnail.request != null &&
                        (!thumbnail.request.isNetwork ||
                            thumbnail.loading ||
                            thumbnail.frame != null)
                if (showFrame) {
                    // 先测量非加权的时间文字，再把真正剩余的高度分配给图片。
                    BoxWithConstraints(
                        modifier = Modifier.weight(1f, fill = false),
                        contentAlignment = Alignment.Center,
                    ) {
                        val frameHeight = minOf(90.dp, maxHeight, maxWidth * 9f / 16f)
                        if (frameHeight >= 24.dp) {
                            Box(
                                modifier =
                                    Modifier.size(frameHeight * 16f / 9f, frameHeight)
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
                                    thumbnail.loading ->
                                        CircularProgressIndicator(
                                            modifier = Modifier.size(16.dp),
                                            color = Color.White,
                                            strokeWidth = 2.dp,
                                        )
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
                thumbInset = PlayerSeekThumbDiameter.toPx() / 2f,
                isRtl = layoutDirection == LayoutDirection.Rtl,
                position = position,
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
): Int {
    val width = containerWidth.coerceAtLeast(0)
    val bubbleWidth = previewWidth.coerceIn(0, width)
    if (position == SeekPreviewPosition.Center) return (width - bubbleWidth) / 2
    val inset = if (thumbInset.isFinite()) thumbInset.coerceIn(0f, width / 2f) else 0f
    val progress = if (fraction.isFinite()) fraction.coerceIn(0f, 1f) else 0f
    val physicalProgress = if (isRtl) 1f - progress else progress
    val thumbCenter = inset + (width - 2f * inset) * physicalProgress
    return (thumbCenter - bubbleWidth / 2f).roundToInt().coerceIn(0, width - bubbleWidth)
}
