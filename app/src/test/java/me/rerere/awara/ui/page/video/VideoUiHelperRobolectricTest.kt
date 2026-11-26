package me.rerere.awara.ui.page.video

import android.os.Looper
import java.lang.reflect.Proxy
import java.time.Instant
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import me.rerere.awara.data.entity.Comment
import me.rerere.awara.data.entity.DownloadItem
import me.rerere.awara.data.entity.DownloadType
import me.rerere.awara.data.entity.Playlist
import me.rerere.awara.data.entity.VideoFile
import me.rerere.awara.data.entity.VideoSrc
import me.rerere.awara.data.repo.CommentRepo
import me.rerere.awara.data.repo.MediaRepo
import me.rerere.awara.data.repo.UserRepo
import me.rerere.awara.data.source.IwaraAPI
import me.rerere.awara.data.source.Pager
import me.rerere.awara.ui.component.iwara.comment.CommentState
import me.rerere.awara.ui.component.iwara.comment.CommentStateItem
import me.rerere.awara.ui.page.video.VideoVM.OnlineUrlsState
import me.rerere.awara.ui.page.video.VideoVM.VideoDetailsState
import me.rerere.awara.ui.page.video.VideoVM.VideoEvent
import me.rerere.awara.ui.page.video.VideoVM.VideoState
import me.rerere.awara.ui.page.video.pager.parseExternalDownloadUri
import okhttp3.OkHttpClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import retrofit2.Response

/** 验证播放器条目解析、外链下载 URL 校验及视频社交操作协作者的行为契约。 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], manifest = Config.NONE)
class VideoUiHelperRobolectricTest {

    @Test
    fun resolvePlayerSyncItemsSelectsLocalOnlineOrEmptyBasedOnDetailsState() {
        val validLocal =
            DownloadItem(
                title = "本地缓存",
                thumbnail = "",
                uri = "content://media/external/downloads/9",
                type = DownloadType.VIDEO,
                resourceId = "vid_sync",
                time = Instant.EPOCH,
                quality = "",
            )
        val localItems =
            resolvePlayerSyncItems(
                videoId = "vid_sync",
                details = VideoDetailsState.Local(item = validLocal, playingLocal = true),
            )
        assertNotNull(localItems)
        assertEquals(1, localItems!!.size)
        assertEquals("local", localItems.first().quality)
        assertEquals("content://media/external/downloads/9", localItems.first().mediaItem.localConfiguration?.uri?.toString())

        // 本地 URI 为空时跳过更新，避免误清空播放器
        val blankUriLocal = validLocal.copy(uri = "   ")
        assertNull(
            resolvePlayerSyncItems(
                videoId = "vid_sync",
                details = VideoDetailsState.Local(item = blankUriLocal, playingLocal = true),
            )
        )

        // 加载中返回 null，不打断当前播放状态
        assertNull(resolvePlayerSyncItems(videoId = "vid_sync", details = VideoDetailsState.Loading()))

        // 非本地播放且已解析在线地址时仅映射可播放清晰度条目
        val onlineVideo = VideoPlaybackLoadTest.createDummyVideo("vid_sync")
        val onlineFiles =
            listOf(
                VideoFile(
                    id = "f1",
                    name = "540",
                    type = "video/mp4",
                    src = VideoSrc(download = "//cdn.iwara.tv/dl/540", view = "//cdn.iwara.tv/view/540"),
                ),
                VideoFile(
                    id = "f2",
                    name = "preview",
                    type = "video/mp4",
                    src = VideoSrc(download = "//cdn.iwara.tv/dl/p", view = "//cdn.iwara.tv/view/p"),
                ),
            )
        val onlineItems =
            resolvePlayerSyncItems(
                videoId = "vid_sync",
                details =
                    VideoDetailsState.Online.Loaded(
                        localFileUnavailable = false,
                        video = onlineVideo,
                        onlineUrlsState = OnlineUrlsState.Loaded(onlineFiles),
                    ),
            )
        assertEquals(listOf("540"), onlineItems?.map { it.quality })
        assertEquals(
            "https://cdn.iwara.tv/view/540",
            onlineItems?.first()?.mediaItem?.localConfiguration?.uri?.toString(),
        )

        // 完成加载但既无本地又无在线源时清空播放器队列
        assertEquals(
            emptyList<Any>(),
            resolvePlayerSyncItems(
                videoId = "vid_sync",
                details =
                    VideoDetailsState.Online.Failed(
                        localFileUnavailable = false,
                        isOnlineRemoved = true,
                        errorReason = "errors.notFound",
                    ),
            ),
        )
    }

    @Test
    fun parseExternalDownloadUriAllowsOnlyHttpAndHttpsWithHost() {
        val validHttps = parseExternalDownloadUri("  https://files.iwara.tv/video.mp4?token=1  ")
        assertEquals("https://files.iwara.tv/video.mp4?token=1", validHttps?.toString())
        assertEquals("http", parseExternalDownloadUri("HTTP://example.com/a.mp4")?.scheme?.lowercase())

        assertNull(parseExternalDownloadUri(""))
        assertNull(parseExternalDownloadUri("   "))
        assertNull(parseExternalDownloadUri("https:///missing-host.mp4"))
        assertNull(parseExternalDownloadUri("file:///sdcard/movie.mp4"))
        assertNull(parseExternalDownloadUri("javascript:alert(1)"))
    }

    @Test
    fun socialActionDelegateUpdatesPlaylistLikeAndOfflineFollowState() {
        val video = VideoPlaybackLoadTest.createDummyVideo("vid_social")
        val addedState = AtomicReference(false)
        val reloadCount = AtomicInteger(0)
        val playlistIdPassed = AtomicReference<String?>(null)

        val api =
            Proxy.newProxyInstance(
                IwaraAPI::class.java.classLoader,
                arrayOf(IwaraAPI::class.java),
            ) { _, method, args ->
                when (method.name) {
                    "likeVideo",
                    "unlikeVideo",
                    "followUser",
                    "unfollowUser" -> Response.success(Unit)
                    "addVideoToPlaylist" -> {
                        playlistIdPassed.set(args?.get(0) as? String)
                        addedState.set(true)
                        Response.success(Unit)
                    }
                    "removeVideoFromPlaylist" -> {
                        playlistIdPassed.set(args?.get(0) as? String)
                        addedState.set(false)
                        Response.success(Unit)
                    }
                    "getLightPlaylists" ->
                        listOf(
                            Playlist(
                                id = "pl_1",
                                numVideos = 1,
                                title = "Fav",
                                user = null,
                                added = addedState.get(),
                            )
                        )
                    else -> error("Unexpected API call: ${method.name}")
                }
            } as IwaraAPI

        val stateRef =
            AtomicReference(
                VideoState(
                    details =
                        VideoDetailsState.Online.Loaded(
                            localFileUnavailable = false,
                            video = video,
                            onlineUrlsState = OnlineUrlsState.Idle,
                        )
                )
            )
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        try {
            val delegate =
                VideoSocialActionDelegate(
                    videoId = "vid_social",
                    mediaRepo = MediaRepo(OkHttpClient(), api),
                    userRepo = UserRepo(api),
                    scope = scope,
                    readState = { stateRef.get() },
                    updateState = { transform -> stateRef.set(transform(stateRef.get())) },
                )

            delegate.likeOrUnlike()
            shadowOf(Looper.getMainLooper()).idle()
            assertTrue(stateRef.get().details.interactiveVideo?.liked == true)
            assertFalse(stateRef.get().likeLoading)

            delegate.updatePlaylistMembership("pl_1", add = true)
            shadowOf(Looper.getMainLooper()).idle()
            assertEquals("pl_1", playlistIdPassed.get())
            assertEquals(true, stateRef.get().playlist.first().added)
            assertFalse(stateRef.get().playlistLoading)

            delegate.updatePlaylistMembership("pl_1", add = false)
            shadowOf(Looper.getMainLooper()).idle()
            assertEquals(false, stateRef.get().playlist.first().added)
            assertFalse(stateRef.get().playlistLoading)

            // 离线视频已删但作者存活场景：关注成功后直接翻转本地作者关注态而不强制重载在线详情
            val offlineAuthor = video.user!!.copy(following = false)
            stateRef.set(
                VideoState(
                    details =
                        VideoDetailsState.Local(
                            item =
                                DownloadItem(
                                    title = "offline",
                                    thumbnail = "",
                                    uri = "file:///offline.mp4",
                                    type = DownloadType.VIDEO,
                                    resourceId = "vid_social",
                                    time = Instant.EPOCH,
                                ),
                            metadata =
                                VideoDetailsState.Local.Metadata.OfflineFallback(
                                    LocalOfflineStatus.VideoDeleted(offlineAuthor)
                                ),
                        )
                )
            )
            delegate.followOrUnfollow(onReloadOnlineVideo = { reloadCount.incrementAndGet() })
            shadowOf(Looper.getMainLooper()).idle()
            assertEquals(0, reloadCount.get())
            assertEquals(true, stateRef.get().details.offlineDeletedAuthor?.following)
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun commentDelegateHandlesEditAndDeletePageRollback() {
        val video = VideoPlaybackLoadTest.createDummyVideo("vid_comm")
        val deletedIds = mutableListOf<String>()
        val requestedPages = mutableListOf<Int>()
        val comment =
            Comment(
                id = "c_last",
                body = "old body",
                createdAt = Instant.EPOCH,
                updatedAt = Instant.EPOCH,
                numReplies = 0,
                user = video.user,
                parent = null,
            )
        val api =
            Proxy.newProxyInstance(
                IwaraAPI::class.java.classLoader,
                arrayOf(IwaraAPI::class.java),
            ) { _, method, args ->
                when (method.name) {
                    "updateComment" -> Response.success(Unit)
                    "deleteComment" -> {
                        deletedIds += args?.get(0) as String
                        Response.success(Unit)
                    }
                    "getVideoComments" -> {
                        val params = args?.get(1) as? Map<*, *>
                        val page = params?.get("page")?.toString()?.toInt() ?: 0
                        requestedPages += page
                        Pager(count = 32, limit = 32, page = page, results = emptyList<Comment>())
                    }
                    else -> error("Unexpected API call: ${method.name}")
                }
            } as IwaraAPI

        val stateRef =
            AtomicReference(
                VideoState(
                    details =
                        VideoDetailsState.Online.Loaded(
                            localFileUnavailable = false,
                            video = video,
                            onlineUrlsState = OnlineUrlsState.Idle,
                        ),
                    commentState =
                        CommentState(
                            stack =
                                listOf(
                                    CommentStateItem(
                                        page = 2,
                                        limit = 32,
                                        total = 33,
                                        comments = listOf(comment),
                                        loadedPage = 2,
                                    )
                                )
                        ),
                )
            )
        val emittedEvents = mutableListOf<VideoEvent>()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        try {
            val delegate =
                VideoCommentDelegate(
                    videoId = "vid_comm",
                    commentRepo = CommentRepo(api),
                    scope = scope,
                    readState = { stateRef.get() },
                    updateCommentState = { transform ->
                        val current = stateRef.get()
                        stateRef.set(current.copy(commentState = transform(current.commentState)))
                    },
                    emitEvent = { emittedEvents += it },
                )

            runBlocking {
                assertTrue(delegate.editComment("c_last", "updated body"))
            }
            shadowOf(Looper.getMainLooper()).idle()
            // 第二页仅一条评论被删除时，应自动退回第一页并请求 page=0
            runBlocking {
                assertTrue(delegate.deleteComment("c_last"))
            }
            shadowOf(Looper.getMainLooper()).idle()
            assertEquals(listOf("c_last"), deletedIds)
            assertEquals(1, stateRef.get().commentState.stack.last().page)
            assertTrue(requestedPages.contains(0))
        } finally {
            scope.cancel()
        }
    }
}
