package me.rerere.awara.ui.page.video

import android.content.Context
import android.content.ContextWrapper
import android.content.res.Resources
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import androidx.activity.ComponentActivity
import androidx.compose.runtime.BroadcastFrameClock
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Recomposer
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.node.RootForTest
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.lifecycle.SavedStateHandle
import androidx.navigation.NavHostController
import androidx.room.Room
import java.net.InetAddress
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import me.rerere.awara.R
import me.rerere.awara.data.download.DownloadManager
import me.rerere.awara.data.download.DownloadStorage
import me.rerere.awara.data.dto.CommentCreationDto
import me.rerere.awara.data.entity.DownloadItem
import me.rerere.awara.data.entity.DownloadType
import me.rerere.awara.data.repo.CommentRepo
import me.rerere.awara.data.repo.MediaRepo
import me.rerere.awara.data.repo.UserRepo
import me.rerere.awara.data.source.IwaraAPI
import me.rerere.awara.data.user.UserBlocklist
import me.rerere.awara.di.AppDatabase
import me.rerere.awara.di.TagI18NResolver
import me.rerere.awara.ui.LocalRouterProvider
import me.rerere.awara.ui.component.common.DialogProvider
import me.rerere.awara.ui.component.common.MessageProvider
import me.rerere.awara.ui.component.iwara.comment.CommentState
import me.rerere.awara.ui.component.iwara.comment.CommentStateItem
import me.rerere.awara.ui.page.video.layout.VideoPagePhoneLayout
import me.rerere.awara.ui.stores.LocalUserStore
import me.rerere.awara.ui.stores.UserStore
import me.rerere.awara.ui.theme.LocalDarkMode
import me.rerere.awara.util.SerializationConverterFactory
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.koin.core.context.startKoin
import org.koin.core.context.stopKoin
import org.koin.dsl.module
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import retrofit2.Retrofit

/**
 * 验证视频详情页评论选项卡计数与真实评论分页总数的同步行为。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30])
class VideoCommentCountTest {

    private lateinit var context: Context
    private lateinit var server: MockWebServer

    @Before
    fun setUp() {
        stopKoin()
        context = RuntimeEnvironment.getApplication()
        server = MockWebServer()
        server.start(InetAddress.getByName("127.0.0.1"), 0)
    }

    @After
    fun tearDown() {
        stopKoin()
        server.shutdown()
    }

    private fun wrapTestResourcesContext(base: Context): Context {
        val baseResources = base.resources
        @Suppress("DEPRECATION")
        val fallbackResources =
            object :
                Resources(
                    baseResources.assets,
                    baseResources.displayMetrics,
                    baseResources.configuration,
                ) {
                override fun getText(id: Int): CharSequence =
                    when (id) {
                        R.string.video_tab_overview -> "简介"
                        R.string.video_tab_comments -> "评论"
                        else ->
                            try {
                                super.getText(id)
                            } catch (_: NotFoundException) {
                                "res_$id"
                            }
                    }

                override fun getText(id: Int, def: CharSequence): CharSequence =
                    try {
                        super.getText(id, def)
                    } catch (_: NotFoundException) {
                        def
                    }

                override fun getString(id: Int): String = getText(id).toString()

                override fun getString(id: Int, vararg formatArgs: Any?): String =
                    try {
                        super.getString(id, *formatArgs)
                    } catch (_: NotFoundException) {
                        "res_$id"
                    }
            }
        return object : ContextWrapper(base) {
            override fun getResources(): Resources = fallbackResources
        }
    }

    private fun videoJson(id: String, numComments: Int): String =
        """
        {
          "id": "$id",
          "title": "阿慈谷ヒフミ",
          "liked": false,
          "numComments": $numComments,
          "numLikes": 150,
          "numViews": 2537,
          "body": null,
          "createdAt": "2026-11-18T10:00:00.000Z",
          "updatedAt": "2026-11-18T10:00:00.000Z",
          "deletedAt": null,
          "slug": "video-slug",
          "user": {
            "id": "author_1",
            "username": "author1",
            "name": "Author",
            "createdAt": "2026-01-01T00:00:00.000Z",
            "deletedAt": null,
            "followedBy": false,
            "following": false,
            "friend": false,
            "premium": false,
            "role": "user",
            "seenAt": null,
            "status": "",
            "updatedAt": "2026-01-01T00:00:00.000Z",
            "avatar": null
          },
          "tags": [],
          "private": false,
          "rating": "ecchi",
          "status": "complete",
          "embedUrl": null,
          "file": null,
          "fileUrl": null,
          "thumbnail": 0
        }
        """
            .trimIndent()

    private fun commentJson(id: String, body: String, numReplies: Int = 0): String =
        """
        {
          "id": "$id",
          "body": "$body",
          "createdAt": "2026-11-18T12:00:00.000Z",
          "updatedAt": "2026-11-18T12:00:00.000Z",
          "numReplies": $numReplies,
          "user": {
            "id": "user_$id",
            "username": "u_$id",
            "name": "User $id",
            "createdAt": "2026-01-01T00:00:00.000Z",
            "deletedAt": null,
            "followedBy": false,
            "following": false,
            "friend": false,
            "premium": false,
            "role": "user",
            "seenAt": null,
            "status": "",
            "updatedAt": "2026-01-01T00:00:00.000Z",
            "avatar": null
          },
          "parent": null
        }
        """
            .trimIndent()

    private fun commentsPagerJson(count: Int, items: List<Pair<String, String>>, page: Int = 0): String {
        val results = items.joinToString(",") { (id, body) -> commentJson(id, body) }
        return """{"count":$count,"limit":32,"page":$page,"results":[$results]}"""
    }

    private fun collectTabTextGroups(composeView: ComposeView): List<List<String>> {
        val root = (composeView.getChildAt(0) as? RootForTest)?.semanticsOwner?.rootSemanticsNode
            ?: return emptyList()
        val groups = mutableListOf<List<String>>()
        fun visit(node: SemanticsNode) {
            if (node.config.getOrNull(SemanticsProperties.Role) == Role.Tab) {
                val texts =
                    node.config
                        .getOrNull(SemanticsProperties.Text)
                        ?.map { it.text }
                        .orEmpty()
                if (texts.isNotEmpty()) {
                    groups += texts
                }
            }
            node.children.forEach(::visit)
        }
        visit(root)
        return groups
    }

    private fun waitUntil(message: String, predicate: () -> Boolean) {
        val deadline = System.currentTimeMillis() + 5_000L
        while (System.currentTimeMillis() < deadline) {
            shadowOf(Looper.getMainLooper()).idle()
            if (predicate()) return
            Thread.sleep(20)
        }
        assertTrue(message, predicate())
    }

    @Test
    fun commentTabDisplaysActualCommentListTotalInsteadOfStaleVideoNumComments() {
        val videoId = "Nn7iRK4Pkb5WPC"
        val currentComments =
            AtomicReference(
                listOf(
                    "c1" to "i gotta resist",
                    "c2" to "nice",
                    "c3" to "nice video!",
                    "c4" to "comment 4",
                    "c5" to "comment 5",
                )
            )
        val commentsCount = AtomicInteger(5)

        server.dispatcher =
            object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse {
                    val path = request.requestUrl?.encodedPath.orEmpty()
                    val parent = request.requestUrl?.queryParameter("parent")
                    return when {
                        path == "/video/$videoId" && request.method == "GET" ->
                            MockResponse()
                                .setResponseCode(200)
                                .setBody(videoJson(videoId, numComments = 6))
                        path == "/video/$videoId/comments" && request.method == "GET" && parent == null ->
                            MockResponse()
                                .setResponseCode(200)
                                .setBody(
                                    commentsPagerJson(
                                        count = commentsCount.get(),
                                        items = currentComments.get(),
                                    )
                                )
                        path == "/video/$videoId/comments" && request.method == "GET" && parent != null ->
                            MockResponse()
                                .setResponseCode(200)
                                .setBody(commentsPagerJson(count = 1, items = listOf("r1" to "reply")))
                        path == "/video/$videoId/comments" && request.method == "POST" -> {
                            currentComments.updateAndGet { it + ("c6" to "new comment") }
                            commentsCount.set(6)
                            MockResponse()
                                .setResponseCode(200)
                                .setBody(commentJson("c6", "new comment"))
                        }
                        path == "/comment/c6" && request.method == "DELETE" -> {
                            currentComments.updateAndGet { list -> list.filterNot { it.first == "c6" } }
                            commentsCount.set(5)
                            MockResponse().setResponseCode(200)
                        }
                        path == "/video/$videoId/related" ->
                            MockResponse()
                                .setResponseCode(200)
                                .setBody("""{"count":0,"limit":32,"page":0,"results":[]}""")
                        path == "/light/playlists" ->
                            MockResponse().setResponseCode(200).setBody("[]")
                        else -> MockResponse().setResponseCode(404)
                    }
                }
            }

        val okHttpClient = OkHttpClient.Builder().build()
        val api =
            Retrofit.Builder()
                .baseUrl(server.url("/"))
                .client(okHttpClient)
                .addConverterFactory(SerializationConverterFactory.create())
                .build()
                .create(IwaraAPI::class.java)
        val db =
            Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
                .allowMainThreadQueries()
                .build()
        val storage = DownloadStorage(context)
        val mediaRepo = MediaRepo(okHttpClient, api)
        val downloadManager = DownloadManager(context, db, storage, mediaRepo, { false }, { false })

        startKoin {
            modules(
                module {
                    single { UserBlocklist(readText = { "" }, writeText = {}) }
                    single { storage }
                    single { downloadManager }
                    single { TagI18NResolver(context) }
                }
            )
        }

        val vm =
            VideoVM(
                savedStateHandle = SavedStateHandle(mapOf("id" to videoId)),
                mediaRepo = mediaRepo,
                userRepo = UserRepo(api),
                commentRepo = CommentRepo(api),
                appDatabase = db,
                downloadStorage = storage,
            )

        val controller = Robolectric.buildActivity(ComponentActivity::class.java).setup()
        val activity = controller.get()
        val wrappedContext = wrapTestResourcesContext(activity)
        val clock = BroadcastFrameClock()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate + clock)
        val recomposer = Recomposer(scope.coroutineContext as CoroutineContext)
        val composeView = ComposeView(activity)
        composeView.setParentCompositionContext(recomposer)
        val navController = NavHostController(activity)
        val userStore = UserStore()

        composeView.setContent {
            CompositionLocalProvider(
                LocalContext provides wrappedContext,
                LocalRouterProvider provides navController,
                LocalUserStore provides userStore,
                LocalDarkMode provides false,
            ) {
                DialogProvider {
                    MessageProvider {
                        VideoPagePhoneLayout(
                            vm = vm,
                            isPortraitVideo = false,
                            player = {},
                        )
                    }
                }
            }
        }
        activity.setContentView(
            composeView,
            ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT,
            ),
        )
        val recomposerJob = scope.launch { recomposer.runRecomposeAndApplyChanges() }

        val decorView = activity.window.decorView
        val widthSpec = View.MeasureSpec.makeMeasureSpec(1080, View.MeasureSpec.EXACTLY)
        val heightSpec = View.MeasureSpec.makeMeasureSpec(1920, View.MeasureSpec.EXACTLY)
        var frameTimeNanos = 1_000_000_000L

        fun pumpUi() {
            repeat(5) {
                Snapshot.sendApplyNotifications()
                frameTimeNanos += 16_666_667L
                clock.sendFrame(frameTimeNanos)
                shadowOf(Looper.getMainLooper()).idle()
                decorView.measure(widthSpec, heightSpec)
                decorView.layout(0, 0, 1080, 1920)
            }
        }

        try {
            waitUntil("VideoVM 应加载完成视频详情与根评论分页") {
                vm.state.details.displayVideo != null &&
                    vm.state.commentState.stack.firstOrNull()?.loadedPage == 1
            }
            pumpUi()

            val commentTabGroup =
                collectTabTextGroups(composeView).firstOrNull { texts ->
                    texts.contains("评论")
                }
            assertTrue(
                "应在评论选项卡节点中找到实际评论计数 5（非陈旧的 6），实际节点文本=$commentTabGroup",
                commentTabGroup != null &&
                    commentTabGroup.contains("5") &&
                    !commentTabGroup.contains("6"),
            )

            // 进入二级回复层时，主选项卡计数应保持根评论数量 5 而非切为回复数 1
            vm.pushComment("c1")
            waitUntil("二级回复加载完成") {
                vm.state.commentState.stack.size == 2 &&
                    vm.state.commentState.stack.last().loadedPage == 1
            }
            pumpUi()
            val groupInReply =
                collectTabTextGroups(composeView).firstOrNull { it.contains("评论") }
            assertTrue(
                "查看回复时评论选项卡应保持根计数 5，实际=$groupInReply",
                groupInReply != null && groupInReply.contains("5"),
            )
            vm.popComment()
            pumpUi()

            // 发表新评论与删除评论后，选项卡计数应同步更新
            runBlocking { assertTrue(vm.postComment(CommentCreationDto(body = "new comment", parentId = null))) }
            waitUntil("发表后应刷新到 6") {
                vm.state.commentState.stack.first().total == 6
            }
            pumpUi()
            val groupAfterPost =
                collectTabTextGroups(composeView).firstOrNull { it.contains("评论") }
            assertTrue(
                "发表评论后选项卡计数应更新为 6，实际=$groupAfterPost",
                groupAfterPost != null && groupAfterPost.contains("6"),
            )

            runBlocking { assertTrue(vm.deleteComment("c6")) }
            waitUntil("删除后应刷新回 5") {
                vm.state.commentState.stack.first().total == 5
            }
            pumpUi()
            val groupAfterDelete =
                collectTabTextGroups(composeView).firstOrNull { it.contains("评论") }
            assertEquals(true, groupAfterDelete?.contains("5"))
            assertFalse(groupAfterDelete?.contains("6") == true)
        } finally {
            recomposer.cancel()
            recomposerJob.cancel()
            scope.cancel()
            controller.pause().stop().destroy()
            db.close()
        }
    }

    @Test
    fun displayCommentCountHandlesFallbackMultiPageAndOfflineStates() {
        val loadedVideo = VideoPlaybackLoadTest.createDummyVideo("vid_1").copy(numComments = 6)
        val detailsLoaded =
            VideoVM.VideoDetailsState.Online.Loaded(
                localFileUnavailable = false,
                video = loadedVideo,
                onlineUrlsState = VideoVM.OnlineUrlsState.Idle,
            )

        // 评论尚未加载或加载失败时，回退展示 video.numComments
        val beforeCommentsLoaded = VideoVM.VideoState(details = detailsLoaded)
        assertEquals(6, beforeCommentsLoaded.displayCommentCount)

        // 根评论加载完成后（即使多页且仅加载第 2 页），始终采用根分页总数而非当前页条数
        val multiPageComments =
            CommentState(
                stack =
                    listOf(
                        CommentStateItem(
                            page = 2,
                            limit = 32,
                            total = 40,
                            loadedPage = 2,
                        )
                    )
            )
        assertEquals(
            40,
            VideoVM.VideoState(details = detailsLoaded, commentState = multiPageComments)
                .displayCommentCount,
        )

        // 根评论真实总数为 0 时覆盖陈旧的非零 numComments
        val zeroCommentsLoaded =
            CommentState(
                stack = listOf(CommentStateItem(page = 1, limit = 32, total = 0, loadedPage = 1))
            )
        assertEquals(
            0,
            VideoVM.VideoState(details = detailsLoaded, commentState = zeroCommentsLoaded)
                .displayCommentCount,
        )

        // 离线回退模式且无远端评论时返回 0
        val offlineDetails =
            VideoVM.VideoDetailsState.Local(
                item =
                    DownloadItem(
                        title = "offline",
                        thumbnail = "",
                        uri = "file:///offline.mp4",
                        type = DownloadType.VIDEO,
                        resourceId = "vid_1",
                        time = java.time.Instant.EPOCH,
                    ),
                metadata =
                    VideoVM.VideoDetailsState.Local.Metadata.OfflineFallback(
                        status = LocalOfflineStatus.LoadFailed("offline")
                    ),
            )
        assertEquals(0, VideoVM.VideoState(details = offlineDetails).displayCommentCount)
    }
}
