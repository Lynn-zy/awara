package me.rerere.awara.ui.page.video

import android.content.pm.ActivityInfo
import android.content.res.Configuration
import androidx.activity.compose.BackHandler
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.stringResource as composeStringResource
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.collectLatest
import me.rerere.awara.R
import me.rerere.awara.data.entity.Video
import me.rerere.awara.data.entity.thumbnailUrl
import me.rerere.awara.data.source.stringResource
import me.rerere.awara.ui.LocalMessageProvider
import me.rerere.awara.ui.component.player.PlayerState
import me.rerere.awara.ui.component.player.resolveFullscreenOrientation
import me.rerere.awara.ui.component.player.resolveToggledFullscreenOrientation
import me.rerere.awara.ui.hooks.rememberRequestedScreenOrientation
import me.rerere.awara.ui.page.setting.SettingKeys
import me.rerere.awara.ui.page.video.VideoVM.VideoDetailsState
import me.rerere.awara.ui.page.video.VideoVM.VideoEvent
import me.rerere.awara.ui.page.video.layout.resolveEffectiveVideoSize
import me.rerere.awara.ui.theme.DynamicThemeState
import me.rerere.compose_setting.preference.rememberBooleanPreference

/**
 * 视频全屏与屏幕方向状态控制器，封装进入/退出全屏及全屏内横竖屏切换逻辑。
 */
@Stable
internal class VideoFullscreenController(
    private val playerState: PlayerState,
    private val onRequestOrientation: (Int) -> Unit,
) {
    var fullscreen by mutableStateOf(false)
        private set

    var displayVideo: Video? = null
    var sensorRotationEnabled: Boolean = true
    var currentOrientation: Int = Configuration.ORIENTATION_UNDEFINED

    fun enter() {
        val effectiveSize = resolveEffectiveVideoSize(playerState.videoSize, displayVideo)
        if (effectiveSize == PlayerState.VideoSize.UNKNOWN) return
        // 单击全屏按钮进入全屏时，立即收起顶部标题与底部进度条等全部控件
        playerState.controllerState.hide()
        fullscreen = true
        onRequestOrientation(
            resolveFullscreenOrientation(
                landscape = effectiveSize.width > effectiveSize.height,
                sensorRotationEnabled = sensorRotationEnabled,
            )
        )
    }

    fun exit() {
        onRequestOrientation(ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED)
        fullscreen = false
    }

    fun rotate() {
        val isLandscape = currentOrientation == Configuration.ORIENTATION_LANDSCAPE
        onRequestOrientation(
            resolveToggledFullscreenOrientation(
                isCurrentlyLandscape = isLandscape,
                sensorRotationEnabled = sensorRotationEnabled,
            )
        )
    }
}

/** 创建并绑定视频全屏方向控制器及系统返回键退出行为。 */
@Composable
internal fun rememberVideoFullscreenController(
    playerState: PlayerState,
    displayVideo: Video?,
): VideoFullscreenController {
    var requestOrientation by rememberRequestedScreenOrientation()
    val configuration = LocalConfiguration.current
    val sensorRotationEnabled by
        rememberBooleanPreference(key = SettingKeys.PLAYER_SENSOR_ROTATION, default = true)
    val controller =
        remember(playerState) {
            VideoFullscreenController(
                playerState = playerState,
                onRequestOrientation = { orientation -> requestOrientation = orientation },
            )
        }
    controller.displayVideo = displayVideo
    controller.sensorRotationEnabled = sensorRotationEnabled
    controller.currentOrientation = configuration.orientation

    BackHandler(controller.fullscreen) { controller.exit() }
    return controller
}

/**
 * 集中承载视频详情页的播放器副作用：动态主题取色、播放队列同步、本地文件失效提示与评论事件提示。
 */
@Composable
internal fun VideoPlayerSideEffects(
    videoId: String,
    details: VideoDetailsState,
    playerState: PlayerState,
    colorState: DynamicThemeState,
    events: Flow<VideoEvent>,
) {
    val message = LocalMessageProvider.current

    LaunchedEffect(details.displayVideo) {
        colorState.updateColorScheme(url = details.displayVideo?.thumbnailUrl() ?: "")
    }

    // 状态驱动播放器内容更新，本地播放加载本地URI，在线清晰度加载网络源，切换保持播放位置与播放状态
    LaunchedEffect(
        details.localDownload,
        details.urls,
        details.isLocalPlayback,
        details.isLoading,
    ) {
        resolvePlayerSyncItems(videoId, details)?.let { items ->
            playerState.updatePlayerItems(items)
        }
    }

    LaunchedEffect(details.localFileUnavailable) {
        if (details.localFileUnavailable) {
            message.warning { Text(composeStringResource(R.string.video_local_file_unavailable)) }
        }
    }

    LaunchedEffect(events) {
        events.collectLatest { event ->
            when (event) {
                is VideoEvent.CommentPostException -> {
                    message.error {
                        Text(
                            composeStringResource(
                                R.string.comment_failed,
                                event.throwable.message ?: event.throwable.javaClass.simpleName,
                            )
                        )
                    }
                }
                is VideoEvent.CommentPostFailed -> {
                    message.error {
                        Text(
                            composeStringResource(R.string.comment_failed, stringResource(event.error))
                        )
                    }
                }
                VideoEvent.CommentPosted -> {
                    message.success { Text(composeStringResource(R.string.comment_success)) }
                }
            }
        }
    }
}
