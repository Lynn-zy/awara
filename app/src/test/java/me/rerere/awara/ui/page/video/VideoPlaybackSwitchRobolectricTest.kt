package me.rerere.awara.ui.page.video

import android.content.Context
import android.net.Uri
import android.os.Looper
import androidx.annotation.OptIn
import androidx.lifecycle.SavedStateHandle
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.room.Room
import java.io.File
import java.lang.reflect.Proxy
import java.net.InetAddress
import java.time.Instant
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import me.rerere.awara.data.download.DownloadStorage
import me.rerere.awara.data.entity.Comment
import me.rerere.awara.data.entity.DownloadItem
import me.rerere.awara.data.entity.DownloadType
import me.rerere.awara.data.entity.Playlist
import me.rerere.awara.data.entity.Video
import me.rerere.awara.data.repo.CommentRepo
import me.rerere.awara.data.repo.MediaRepo
import me.rerere.awara.data.repo.UserRepo
import me.rerere.awara.data.source.IwaraAPI
import me.rerere.awara.data.source.Pager
import me.rerere.awara.di.AppDatabase
import me.rerere.awara.ui.component.player.PlayerState
import me.rerere.awara.ui.component.player.shouldBlockInitialLoadingInteraction
import me.rerere.awara.ui.component.player.shouldShowInitialPlayPrompt
import me.rerere.awara.ui.page.video.pager.shouldShowLocalVideoBadge
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import okio.Buffer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowLog

/**
 * 本地与在线清晰度切换、403 自动刷新恢复及 `VideoVM.requestOnlineUrls` 详情兜底刷新的集成回归测试。
 */
@OptIn(UnstableApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], manifest = Config.NONE)
class VideoPlaybackSwitchRobolectricTest {

    private lateinit var context: Context
    private lateinit var server: MockWebServer
    private lateinit var fixtureBytes: ByteArray
    private lateinit var tempLocalFile: File

    @Before
    fun setUp() {
        ShadowLog.stream = System.out
        context = RuntimeEnvironment.getApplication()
        fixtureBytes =
            javaClass.getResourceAsStream("/media/resume-fixture.mp4")!!.use { it.readBytes() }
        tempLocalFile = File.createTempFile("awara-local-switch-", ".mp4", context.cacheDir)
        tempLocalFile.writeBytes(fixtureBytes)

        server = MockWebServer()
        // 显式绑定 IPv4 回环地址，避免双栈环境 localhost 解析开销与断流漂移
        server.start(InetAddress.getByName("127.0.0.1"), 0)
    }

    @After
    fun tearDown() {
        server.shutdown()
        tempLocalFile.delete()
    }

    @Test
    fun localToOnlineQualitySwitchReachesReadyAndOnlyRequestsSelectedQuality() {
        val requestedPaths = CopyOnWriteArrayList<String>()
        server.dispatcher =
            object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse {
                    requestedPaths.add(request.path.orEmpty())
                    return buildMp4Response(request)
                }
            }

        val player = createTestExoPlayer()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        try {
            val state =
                PlayerState(
                    player = player,
                    initialQuality = "Source",
                    saveQualityPreference = {},
                    coroutineScope = scope,
                )

            val localItem =
                PlayerState.PlayerItem(
                    quality = "Source",
                    mediaItem = MediaItem.fromUri(Uri.fromFile(tempLocalFile)),
                )
            state.updatePlayerItems(listOf(localItem))
            waitUntil("本地视频应就绪") { state.state == PlayerState.State.READY }

            val onlineItems =
                listOf(
                    PlayerState.PlayerItem(
                        quality = "Source",
                        mediaItem = MediaItem.fromUri(loopbackUrl("/view?quality=source")),
                    ),
                    PlayerState.PlayerItem(
                        quality = "540",
                        mediaItem = MediaItem.fromUri(loopbackUrl("/view?quality=540")),
                    ),
                )
            state.updatePlayerItems(
                items = onlineItems,
                targetQuality = "540",
                persistQualitySelection = true,
                preservePositionMs = 0L,
                targetPlayWhenReady = false,
            )

            waitUntil("切到在线 540 后应达到 READY 且无报错") {
                state.state == PlayerState.State.READY && state.currentQuality == "540"
            }

            assertNull("在线切换成功不应出现错误", state.playbackError)
            assertFalse("请求中未请求未被选中的 Source 画质", requestedPaths.any { it.contains("quality=source") })
            assertTrue("应请求选中的 540 画质", requestedPaths.any { it.contains("quality=540") })
            assertFalse("不应启动自动播放", state.playWhenReady)

            val showPrompt =
                shouldShowInitialPlayPrompt(
                    playing = state.playing,
                    playWhenReady = state.playWhenReady,
                    hasStartedPlayback = state.hasStartedPlayback,
                    showLoading = state.showLoading,
                    playbackState = state.state,
                    playbackError = state.playbackError,
                )
            assertTrue("READY 且暂停状态应直接显示播放提示，不依赖首帧回调", showPrompt)
            assertFalse(
                "READY 后必须解除交互阻塞",
                shouldBlockInitialLoadingInteraction(
                    hasStartedPlayback = state.hasStartedPlayback,
                    playing = state.playing,
                    initialPlayPromptReady = showPrompt,
                    playbackState = state.state,
                    playbackError = state.playbackError,
                ),
            )
        } finally {
            scope.cancel()
            player.release()
        }
    }

    @Test
    fun http403ExposesPlaybackErrorAndRecoversOnRetry() {
        val allowSuccess = AtomicBoolean(false)
        server.dispatcher =
            object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse {
                    return if (allowSuccess.get()) {
                        buildMp4Response(request)
                    } else {
                        MockResponse().setResponseCode(403)
                    }
                }
            }

        val player = createTestExoPlayer()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        try {
            val state =
                PlayerState(
                    player = player,
                    initialQuality = "540",
                    saveQualityPreference = {},
                    coroutineScope = scope,
                    refreshPlaybackSource = null,
                    usesInternalSourceRefresh = false,
                )

            val localItem =
                PlayerState.PlayerItem(
                    quality = "Source",
                    mediaItem = MediaItem.fromUri(Uri.fromFile(tempLocalFile)),
                )
            state.updatePlayerItems(listOf(localItem))
            waitUntil("本地视频应先就绪") { state.state == PlayerState.State.READY }

            state.controllerState.hide()
            assertFalse(state.controllerState.topVisible)

            val onlineItem =
                PlayerState.PlayerItem(
                    quality = "540",
                    mediaItem = MediaItem.fromUri(loopbackUrl("/view?quality=540")),
                )
            state.updatePlayerItems(
                items = listOf(onlineItem),
                targetQuality = "540",
                persistQualitySelection = true,
                preservePositionMs = 0L,
                targetPlayWhenReady = false,
            )

            waitUntil({ "在线 403 失败后应暴露 playbackError (requests=${server.requestCount}, state=${state.state})" }) {
                state.playbackError != null
            }

            assertTrue("403 应被标识为鉴权/链接失效错误", state.playbackError!!.isHttpAuthError)
            assertFalse("失败后必须停止 loading", state.isLoading)
            assertTrue("失败后必须呼出顶栏菜单方便切换或退出", state.controllerState.topVisible)
            assertFalse(
                "错误态绝不能阻断界面交互",
                shouldBlockInitialLoadingInteraction(
                    hasStartedPlayback = state.hasStartedPlayback,
                    playing = state.playing,
                    initialPlayPromptReady = false,
                    playbackState = state.state,
                    playbackError = state.playbackError,
                ),
            )

            // 模拟链接恢复可用并点击重试
            allowSuccess.set(true)
            state.retry()

            waitUntil("retry 后应清除错误并恢复至 READY") {
                state.playbackError == null && state.state == PlayerState.State.READY
            }
            assertEquals("540", state.currentQuality)
        } finally {
            scope.cancel()
            player.release()
        }
    }

    @Test
    fun http403TriggersSingleShotAutoRefreshAndStopsIfRefreshedUrlAlsoFails() {
        server.dispatcher =
            object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse {
                    val path = request.path.orEmpty()
                    return if (path.contains("token=fresh")) {
                        buildMp4Response(request)
                    } else {
                        MockResponse().setResponseCode(403)
                    }
                }
            }

        val player = createTestExoPlayer()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        try {
            val refreshCalls = AtomicInteger(0)
            val staleUrl = loopbackUrl("/view?quality=540&token=stale")
            val freshUrl = loopbackUrl("/view?quality=540&token=fresh")

            // 1. 首次 403 且刷新返回可用新链接时，自动无感恢复至 READY
            val autoRecoverState =
                PlayerState(
                    player = player,
                    initialQuality = "540",
                    saveQualityPreference = {},
                    refreshPlaybackSource = {
                        refreshCalls.incrementAndGet()
                        freshUrl
                    },
                    coroutineScope = scope,
                    usesInternalSourceRefresh = false,
                )
            autoRecoverState.updatePlayerItems(
                items =
                    listOf(
                        PlayerState.PlayerItem("540", MediaItem.fromUri(staleUrl)),
                    ),
                targetQuality = "540",
                persistQualitySelection = true,
                preservePositionMs = 0L,
                targetPlayWhenReady = false,
            )

            waitUntil({ "403 自动刷新后应直接达到 READY (requests=${server.requestCount}, refreshes=${refreshCalls.get()}, err=${autoRecoverState.playbackError})" }) {
                autoRecoverState.state == PlayerState.State.READY
            }
            assertNull("自动刷新恢复后不应暴露错误浮层", autoRecoverState.playbackError)
            assertEquals("同 URL 只应自动刷新一次", 1, refreshCalls.get())
            assertEquals(
                freshUrl,
                autoRecoverState.currentMediaItem?.localConfiguration?.uri?.toString(),
            )

            // 2. 若刷新后仍返回同一失效 URL，不得死循环重试，而应落地错误状态
            val stuckRefreshCalls = AtomicInteger(0)
            val failedUrl = loopbackUrl("/view?quality=540&token=broken")
            val failFastState =
                PlayerState(
                    player = player,
                    initialQuality = "540",
                    saveQualityPreference = {},
                    refreshPlaybackSource = {
                        stuckRefreshCalls.incrementAndGet()
                        failedUrl
                    },
                    coroutineScope = scope,
                    usesInternalSourceRefresh = false,
                )
            failFastState.updatePlayerItems(
                items =
                    listOf(
                        PlayerState.PlayerItem("540", MediaItem.fromUri(failedUrl)),
                    ),
                targetQuality = "540",
                persistQualitySelection = true,
                preservePositionMs = 0L,
                targetPlayWhenReady = false,
            )

            waitUntil({ "连续 403 且刷新仍失效时必须落地为 playbackError (requests=${server.requestCount}, refreshes=${stuckRefreshCalls.get()}, state=${failFastState.state})" }) {
                failFastState.playbackError != null
            }
            assertEquals("同一失效地址绝不能循环刷新", 1, stuckRefreshCalls.get())
            assertTrue(failFastState.playbackError!!.isHttpAuthError)
        } finally {
            scope.cancel()
            player.release()
        }
    }

    @Test
    fun requestOnlineUrlsRefreshesVideoMetadataWhenCachedFileUrlFails() {
        val db =
            Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
                .allowMainThreadQueries()
                .build()
        val videoId = "vid_stale_file_url"
        runBlocking {
            db.downloadDao()
                .insertDownloadItem(
                    DownloadItem(
                        title = "本地已下载视频",
                        thumbnail = "",
                        uri = tempLocalFile.toURI().toString(),
                        type = DownloadType.VIDEO,
                        resourceId = videoId,
                        time = Instant.ofEpochSecond(1700000000L),
                    )
                )
        }

        val staleFileUrl = "https://filesq.iwara.tv/file/stale-file?expires=1700000000"
        val freshFileUrl = "https://filesq.iwara.tv/file/fresh-file?expires=1700000999"
        val staleVideo = VideoPlaybackLoadTest.createDummyVideo(videoId).copy(fileUrl = staleFileUrl)
        val freshVideo = VideoPlaybackLoadTest.createDummyVideo(videoId).copy(fileUrl = freshFileUrl)

        val getVideoCalls = AtomicInteger(0)
        val api =
            Proxy.newProxyInstance(
                IwaraAPI::class.java.classLoader,
                arrayOf(IwaraAPI::class.java),
            ) { _, method, _ ->
                when (method.name) {
                    "getVideo" -> {
                        if (getVideoCalls.incrementAndGet() == 1) staleVideo else freshVideo
                    }
                    "getVideoComments" -> Pager<Comment>(0, 0, 0, emptyList())
                    "getRelatedVideo" -> Pager<Video>(0, 0, 0, emptyList())
                    "getLightPlaylists" -> emptyList<Playlist>()
                    else -> error("Unexpected API call: ${method.name}")
                }
            } as IwaraAPI

        val client =
            OkHttpClient.Builder()
                .addInterceptor { chain ->
                    val requestUrl = chain.request().url.toString()
                    if (requestUrl.contains("stale-file")) {
                        Response.Builder()
                            .request(chain.request())
                            .protocol(Protocol.HTTP_1_1)
                            .code(403)
                            .message("Forbidden")
                            .body("expired".toResponseBody("text/plain".toMediaType()))
                            .build()
                    } else {
                        Response.Builder()
                            .request(chain.request())
                            .protocol(Protocol.HTTP_1_1)
                            .code(200)
                            .message("OK")
                            .body(
                                """[
                                    {"id":"540","name":"540","type":"video/mp4","src":{"view":"https://cdn.iwara.tv/540.mp4","download":"https://cdn.iwara.tv/540.mp4"}}
                                ]"""
                                    .toResponseBody("application/json".toMediaType())
                            )
                            .build()
                    }
                }
                .build()

        val vm =
            VideoVM(
                savedStateHandle = SavedStateHandle(mapOf("id" to videoId)),
                mediaRepo = MediaRepo(client, api),
                userRepo = UserRepo(api),
                commentRepo = CommentRepo(api),
                appDatabase = db,
                downloadStorage = DownloadStorage(context),
            )
        try {
            waitUntil({
                "VideoVM 应完成本地视频与初始详情加载 (local=${vm.state.localDownload != null}, video=${vm.state.video != null}, err=${vm.state.sourceError}, calls=${getVideoCalls.get()})"
            }) {
                vm.state.localDownload != null && vm.state.video != null
            }
            assertTrue("本地播放初始不主动解析在线清晰度", vm.state.urls.isEmpty())

            var onSuccessCalled = false
            vm.requestOnlineUrls { onSuccessCalled = true }

            waitUntil("requestOnlineUrls 应在旧 fileUrl 403 后刷新 getVideo 并成功解析") {
                onSuccessCalled && !vm.state.onlineUrlsLoading
            }

            assertNull("兜底刷新成功后不应残留 onlineUrlsError", vm.state.onlineUrlsError)
            assertEquals(listOf("540"), vm.state.urls.map { it.name })
            assertEquals("应先复用缓存 video 再兜底刷新一次详情", 2, getVideoCalls.get())
            assertEquals(freshFileUrl, vm.state.video?.fileUrl)

            // 切至在线播放后，应保留本地下载记录及详情卡片“✓本地视频”标识；未下载视频则不展示
            assertTrue(shouldShowLocalVideoBadge(vm.state))
            vm.selectOnlinePlayback()
            assertFalse("切至在线源后 isLocalPlayback 应置为 false", vm.state.isLocalPlayback)
            assertNotNull("切至在线源后仍应保留 localDownload 记录", vm.state.localDownload)
            assertTrue("切至在线清晰度后仍应保持展示本地视频 tag", shouldShowLocalVideoBadge(vm.state))
            assertFalse("无本地下载记录时不展示本地视频 tag", shouldShowLocalVideoBadge(VideoVM.VideoState()))
        } finally {
            db.close()
        }
    }

    /** 构建支持全量与单段 Range 请求的回环 MP4 响应。 */
    private fun buildMp4Response(request: RecordedRequest): MockResponse {
        val range = request.getHeader("Range")
        if (range != null && range.startsWith("bytes=")) {
            val parts = range.removePrefix("bytes=").split("-")
            val start = parts[0].toInt()
            val end = parts.getOrNull(1)?.takeIf { it.isNotBlank() }?.toInt() ?: (fixtureBytes.size - 1)
            val clampedEnd = end.coerceAtMost(fixtureBytes.size - 1)
            val slice = fixtureBytes.copyOfRange(start, clampedEnd + 1)
            return MockResponse()
                .setResponseCode(206)
                .setHeader("Content-Type", "video/mp4")
                .setHeader("Accept-Ranges", "bytes")
                .setHeader("Content-Range", "bytes $start-$clampedEnd/${fixtureBytes.size}")
                .setBody(Buffer().write(slice))
        }
        return MockResponse()
            .setResponseCode(200)
            .setHeader("Content-Type", "video/mp4")
            .setHeader("Accept-Ranges", "bytes")
            .setBody(Buffer().write(fixtureBytes))
    }

    /** 构造指向本地 IPv4 回环服务器的测试 URL。 */
    private fun loopbackUrl(path: String): String = "http://127.0.0.1:${server.port}$path"

    /** 构建关闭系统帧率覆写策略、支持本地与 HTTP 源切换的测试用 ExoPlayer。 */
    private fun createTestExoPlayer(): ExoPlayer {
        val httpFactory = DefaultHttpDataSource.Factory().setAllowCrossProtocolRedirects(true)
        val dataSourceFactory = DefaultDataSource.Factory(context, httpFactory)
        return ExoPlayer.Builder(context)
            .setVideoChangeFrameRateStrategy(C.VIDEO_CHANGE_FRAME_RATE_STRATEGY_OFF)
            .setMediaSourceFactory(DefaultMediaSourceFactory(dataSourceFactory))
            .build()
    }

    @Test
    fun playerListenerErrorRevealsTopControlsAndRetryClearsError() {
        val player = createTestExoPlayer()
        try {
            val state = PlayerState(player = player, initialQuality = "540", saveQualityPreference = {})
            val item =
                PlayerState.PlayerItem(
                    quality = "540",
                    mediaItem = MediaItem.fromUri(Uri.fromFile(tempLocalFile)),
                )
            state.updatePlayerItems(listOf(item))
            waitUntil("本地视频应就绪") { state.state == PlayerState.State.READY }

            state.controllerState.hide()
            assertFalse(state.controllerState.topVisible)

            state.playbackListener.onPlayerError(
                PlaybackException(
                    "Simulated network failure",
                    null,
                    PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED,
                )
            )

            assertNotNull("出错后记录 playbackError 供界面展示与重试", state.playbackError)
            assertFalse("出错后结束 loading", state.isLoading)
            assertTrue("出错后必须呼出顶栏以便切换清晰度或退出", state.controllerState.topVisible)

            state.retry()
            assertNull("手动重试后应清空错误状态", state.playbackError)
            assertTrue("手动重试应尝试继续播放", state.playWhenReady)
        } finally {
            player.release()
        }
    }

    /** 在超时窗口内推进 Robolectric 主线程虚拟时钟并轮询，直到异步播放或加载断言成立。 */
    private fun waitUntil(
        message: String,
        timeoutMs: Long = 8_000L,
        predicate: () -> Boolean,
    ) = waitUntil({ message }, timeoutMs, predicate)

    /** 在超时窗口内推进 Robolectric 主线程虚拟时钟并轮询，支持动态诊断失败信息。 */
    private fun waitUntil(
        messageProvider: () -> String,
        timeoutMs: Long = 8_000L,
        predicate: () -> Boolean,
    ) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            shadowOf(Looper.getMainLooper()).idleFor(100L, TimeUnit.MILLISECONDS)
            if (predicate()) return
            Thread.sleep(15L)
        }
        shadowOf(Looper.getMainLooper()).idle()
        assertNotNull("${messageProvider()}（超时）", if (predicate()) Unit else null)
    }
}
