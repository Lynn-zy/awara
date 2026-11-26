package me.rerere.awara.ui.page.video

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.windowsizeclass.WindowWidthSizeClass
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import me.rerere.awara.ui.LocalRouterProvider
import me.rerere.awara.ui.component.player.Player
import me.rerere.awara.ui.component.player.PlayerBackIconButton
import me.rerere.awara.ui.component.player.PlayerFullscreenIconButton
import me.rerere.awara.ui.component.player.PlayerScaffold
import me.rerere.awara.ui.component.player.rememberPlayerState
import me.rerere.awara.ui.hooks.ForceSystemBarColor
import me.rerere.awara.ui.hooks.rememberWindowSizeClass
import me.rerere.awara.ui.page.video.layout.VideoPagePhoneLayout
import me.rerere.awara.ui.page.video.layout.VideoPageTabletLayout
import me.rerere.awara.ui.page.video.layout.isPortraitVideo
import me.rerere.awara.ui.theme.DynamicColorTheme
import me.rerere.awara.ui.theme.rememberDynamicColorSchemeState
import org.koin.androidx.compose.koinViewModel

@Composable
fun VideoPage(vm: VideoVM = koinViewModel()) {
    ForceSystemBarColor(appearanceLight = false)

    val windowSize = rememberWindowSizeClass()
    val playerState = rememberPlayerState(refreshPlaybackSource = vm::refreshPlaybackSource)
    val router = LocalRouterProvider.current
    val details = vm.state.details
    val colorState = rememberDynamicColorSchemeState()
    val fullscreenController =
        rememberVideoFullscreenController(
            playerState = playerState,
            displayVideo = details.displayVideo,
        )

    VideoPlayerSideEffects(
        videoId = vm.id,
        details = details,
        playerState = playerState,
        colorState = colorState,
        events = vm.events,
    )

    DynamicColorTheme(state = colorState) {
        PlayerScaffold(
            fullscreen = fullscreenController.fullscreen,
            player = {
                Player(
                    state = playerState,
                    fullscreen = fullscreenController.fullscreen,
                    modifier = Modifier.fillMaxSize(),
                    navigationIcon = {
                        PlayerBackIconButton(onClick = { router.popBackStack() })
                    },
                    onRotateScreen = fullscreenController::rotate,
                    actions = {
                        VideoPlayerQualityAction(
                            videoId = vm.id,
                            details = vm.state.details,
                            playerState = playerState,
                            onRequestOnlineUrls = { vm.requestOnlineUrls() },
                            onSelectLocalPlayback = vm::selectLocalPlayback,
                            onSelectOnlinePlayback = vm::selectOnlinePlayback,
                        )
                    },
                    controllerTrail = {
                        PlayerFullscreenIconButton(
                            fullscreen = fullscreenController.fullscreen,
                            onClick = {
                                if (!fullscreenController.fullscreen) {
                                    fullscreenController.enter()
                                } else {
                                    fullscreenController.exit()
                                }
                            },
                        )
                    },
                )
            },
        ) { player ->
            val portraitVideo = isPortraitVideo(playerState.videoSize, vm.state.details.displayVideo)
            when (windowSize.widthSizeClass) {
                WindowWidthSizeClass.Compact ->
                    VideoPagePhoneLayout(
                        vm = vm,
                        isPortraitVideo = portraitVideo,
                        player = player,
                    )
                else ->
                    VideoPageTabletLayout(
                        vm = vm,
                        isPortraitVideo = portraitVideo,
                        player = player,
                    )
            }
        }
    }
}
