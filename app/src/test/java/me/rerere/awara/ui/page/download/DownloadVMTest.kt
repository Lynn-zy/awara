package me.rerere.awara.ui.page.download

import android.app.Application
import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.net.Uri
import android.os.Looper
import android.os.ParcelFileDescriptor
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.room.Room
import java.io.File
import java.io.FileNotFoundException
import java.lang.reflect.Proxy
import java.time.Instant
import kotlinx.coroutines.runBlocking
import me.rerere.awara.data.download.DownloadStorage
import me.rerere.awara.data.dto.ProfileDto
import me.rerere.awara.data.entity.DownloadItem
import me.rerere.awara.data.entity.DownloadType
import me.rerere.awara.data.entity.File as IwaraFile
import me.rerere.awara.data.entity.User
import me.rerere.awara.data.entity.toAvatarUrl
import me.rerere.awara.data.repo.UserRepo
import me.rerere.awara.data.source.IwaraAPI
import me.rerere.awara.di.AppDatabase
import me.rerere.awara.ui.component.common.UiState
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.koin.core.context.startKoin
import org.koin.core.context.stopKoin
import org.koin.dsl.module
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowContentResolver

/**
 * [DownloadVM] 已下载页面 ViewModel Robolectric 测试。
 *
 * 使用内存 Room 数据库、可控虚拟 ContentProvider 与内存 `IwaraAPI` 代理，覆盖已下载列表加载与聚焦定位、
 * 批量删除部分失败的错误保留与清理，以及按现有作者分组或输入 `@handle` 手动关联作者的核心业务流转。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class)
class DownloadVMTest {

    private lateinit var context: Context
    private lateinit var database: AppDatabase
    private lateinit var storage: DownloadStorage
    private lateinit var provider: ControlledDeleteProvider
    private lateinit var viewModelStore: ViewModelStore

    private var profileResolver: (String) -> ProfileDto = { username ->
        ProfileDto(
            user = createUser(id = "u_$username", username = username, name = "Name $username"),
            header = null,
            updatedAt = Instant.EPOCH,
            body = null,
        )
    }

    @Before
    fun setUp() {
        stopKoin()
        context = RuntimeEnvironment.getApplication()
        startKoin { modules(module { single<Context> { context } }) }

        database =
            Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
                .allowMainThreadQueries()
                .build()
        storage = DownloadStorage(context)
        provider = ControlledDeleteProvider(File(context.cacheDir, "vm_download_provider"))
        ShadowContentResolver.registerProviderInternal(PROVIDER_AUTHORITY, provider)
        viewModelStore = ViewModelStore()
    }

    @After
    fun tearDown() {
        viewModelStore.clear()
        database.close()
        stopKoin()
    }

    @Test
    fun loadDownloadedItemsTracksEmptySuccessAndFocusTarget() = runBlocking {
        val vm = createViewModel()
        waitUntil { vm.state.uiState == UiState.Empty }
        assertTrue(vm.state.items.isEmpty())
        assertNull(vm.resolveFocusTarget("missing"))

        val unknownItem =
            createItem(
                resourceId = "vid_unknown",
                title = "Unknown Author Video",
                authorId = "",
                authorName = "",
            )
        val knownItem =
            createItem(
                resourceId = "vid_known",
                title = "Known Author Video",
                authorId = "author_a",
                authorName = "Author A",
            )
        val unknownId = database.downloadDao().insertDownloadItem(unknownItem).toInt()
        val knownId = database.downloadDao().insertDownloadItem(knownItem).toInt()

        waitUntil { vm.state.uiState == UiState.Success && vm.state.items.size == 2 }

        val target = vm.resolveFocusTarget("vid_known")
        assertNotNull(target)
        assertEquals(knownId, target?.itemId)
        assertEquals("author_a", target?.authorId)

        val unknownTarget = vm.resolveFocusTarget("vid_unknown")
        assertNotNull(unknownTarget)
        assertEquals(unknownId, unknownTarget?.itemId)
        assertEquals("", unknownTarget?.authorId)
    }

    @Test
    fun deleteHandlesPartialFailurePreservesFailedItemAndClearsError() = runBlocking {
        val okUri = provider.createFile("ok_video.mp4")
        val failUri = provider.createFile("fail_delete.mp4")
        provider.failOnDeleteNames.add("fail_delete.mp4")

        val okId =
            database
                .downloadDao()
                .insertDownloadItem(
                    createItem(resourceId = "vid_ok", title = "OK", uri = okUri.toString())
                )
                .toInt()
        val failId =
            database
                .downloadDao()
                .insertDownloadItem(
                    createItem(resourceId = "vid_fail", title = "Fail", uri = failUri.toString())
                )
                .toInt()

        val vm = createViewModel()
        waitUntil { vm.state.items.size == 2 }

        val okItem = vm.state.items.first { it.id == okId }
        val failItem = vm.state.items.first { it.id == failId }

        var callbackResult: Pair<Set<Int>, Set<Int>>? = null
        vm.delete(listOf(okItem, failItem)) { successIds, failedIds ->
            callbackResult = successIds to failedIds
        }

        waitUntil { callbackResult != null && vm.state.deletingIds.isEmpty() }

        assertEquals(setOf(okId), callbackResult?.first)
        assertEquals(setOf(failId), callbackResult?.second)
        assertNotNull(vm.state.deleteError)
        assertNull(database.downloadDao().getDownloadItem("vid_ok"))
        assertNotNull(database.downloadDao().getDownloadItem("vid_fail"))

        vm.clearDeleteError()
        assertNull(vm.state.deleteError)
    }

    @Test
    fun linkItemsToExistingGroupUpdatesOnlyLinkableItemsAndIgnoresUnknownGroup() = runBlocking {
        val unknownId =
            database
                .downloadDao()
                .insertDownloadItem(
                    createItem(
                        resourceId = "vid_u",
                        title = "Unknown",
                        authorId = "",
                        manualAuthor = false,
                    )
                )
                .toInt()
        val manualId =
            database
                .downloadDao()
                .insertDownloadItem(
                    createItem(
                        resourceId = "vid_m",
                        title = "Manual",
                        authorId = "old_manual",
                        authorName = "Old Manual",
                        manualAuthor = true,
                    )
                )
                .toInt()
        val officialId =
            database
                .downloadDao()
                .insertDownloadItem(
                    createItem(
                        resourceId = "vid_o",
                        title = "Official",
                        authorId = "official_creator",
                        authorName = "Official Creator",
                        manualAuthor = false,
                    )
                )
                .toInt()

        val vm = createViewModel()
        waitUntil { vm.state.items.size == 3 }

        val unknownGroup =
            DownloadAuthorGroup(authorId = "", authorName = "", avatar = "", items = emptyList())
        vm.linkItemsToExistingGroup(vm.state.items, unknownGroup)
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals("", database.downloadDao().getDownloadItem("vid_u")?.authorId)

        val targetGroup =
            DownloadAuthorGroup(
                authorId = "mmd_star",
                authorName = "MMD Star",
                avatar = "",
                items = emptyList(),
            )
        var linkedCount = -1
        vm.linkItemsToExistingGroup(vm.state.items, targetGroup, onSuccess = { linkedCount = it })
        waitUntil { linkedCount >= 0 && !vm.state.linkingAuthor }

        assertEquals(2, linkedCount)
        val updatedUnknown = database.downloadDao().getDownloadItem("vid_u")!!
        val updatedManual = database.downloadDao().getDownloadItem("vid_m")!!
        val untouchedOfficial = database.downloadDao().getDownloadItem("vid_o")!!

        assertEquals(unknownId, updatedUnknown.id)
        assertEquals("mmd_star", updatedUnknown.authorId)
        assertEquals("MMD Star", updatedUnknown.authorName)
        assertEquals(DEFAULT_IWARA_AVATAR_URL, updatedUnknown.authorAvatar)
        assertTrue(updatedUnknown.manualAuthor)

        assertEquals(manualId, updatedManual.id)
        assertEquals("mmd_star", updatedManual.authorId)
        assertTrue(updatedManual.manualAuthor)

        assertEquals(officialId, untouchedOfficial.id)
        assertEquals("official_creator", untouchedOfficial.authorId)
        assertFalse(untouchedOfficial.manualAuthor)
    }

    @Test
    fun linkItemsByAuthorHandleCoversOnlineFallbackAndValidationError() = runBlocking {
        database
            .downloadDao()
            .insertDownloadItem(createItem(resourceId = "vid_h1", title = "V1", authorId = ""))
        database
            .downloadDao()
            .insertDownloadItem(createItem(resourceId = "vid_h2", title = "V2", authorId = ""))
        database
            .downloadDao()
            .insertDownloadItem(createItem(resourceId = "vid_h3", title = "V3", authorId = ""))

        val avatarFile =
            IwaraFile(
                id = "av_1",
                mime = "image/png",
                name = "avatar.png",
                path = "/image/avatar/av_1/avatar.png",
                type = "image",
            )
        profileResolver = { handle ->
            when (handle) {
                "live_user" ->
                    ProfileDto(
                        user =
                            createUser(
                                id = "uid_live",
                                username = "live_user",
                                name = "Live Creator",
                                avatar = avatarFile,
                            ),
                        header = null,
                        updatedAt = Instant.EPOCH,
                        body = null,
                    )
                else -> throw IllegalStateException("HTTP 404 Not Found")
            }
        }

        val vm = createViewModel()
        waitUntil { vm.state.items.size == 3 }

        // 1) 在线接口命中存活用户
        val item1 = vm.state.items.first { it.resourceId == "vid_h1" }
        var onlineResult: Pair<ResolvedManualAuthor, Int>? = null
        vm.linkItemsByAuthorHandle(
            items = listOf(item1),
            rawHandle = "  @live_user  ",
            existingAuthors = emptyList(),
            onSuccess = { resolved, count -> onlineResult = resolved to count },
        )
        waitUntil { onlineResult != null && !vm.state.linkingAuthor }

        assertTrue(onlineResult!!.first.resolvedOnline)
        assertEquals(1, onlineResult!!.second)
        val savedLive = database.downloadDao().getDownloadItem("vid_h1")!!
        assertEquals("live_user", savedLive.authorId)
        assertEquals("Live Creator", savedLive.authorName)
        assertEquals(avatarFile.toAvatarUrl(), savedLive.authorAvatar)
        assertTrue(savedLive.manualAuthor)

        // 2) 接口 404 时降级使用输入 ID 与默认头像建组
        val item2 = vm.state.items.first { it.resourceId == "vid_h2" }
        var fallbackResult: Pair<ResolvedManualAuthor, Int>? = null
        vm.linkItemsByAuthorHandle(
            items = listOf(item2),
            rawHandle = "ghost_user",
            existingAuthors = emptyList(),
            onSuccess = { resolved, count -> fallbackResult = resolved to count },
        )
        waitUntil { fallbackResult != null && !vm.state.linkingAuthor }

        assertFalse(fallbackResult!!.first.resolvedOnline)
        val savedGhost = database.downloadDao().getDownloadItem("vid_h2")!!
        assertEquals("ghost_user", savedGhost.authorId)
        assertEquals("ghost_user", savedGhost.authorName)
        assertEquals(DEFAULT_IWARA_AVATAR_URL, savedGhost.authorAvatar)

        // 3) 非法路径字符触发 onError 且保持库内记录不变
        val item3 = vm.state.items.first { it.resourceId == "vid_h3" }
        var errorMessage: String? = null
        vm.linkItemsByAuthorHandle(
            items = listOf(item3),
            rawHandle = "invalid/handle",
            existingAuthors = emptyList(),
            onError = { errorMessage = it },
        )
        waitUntil { errorMessage != null && !vm.state.linkingAuthor }
        assertEquals("", database.downloadDao().getDownloadItem("vid_h3")?.authorId)
    }

    /** 通过 ViewModelProvider 创建 [DownloadVM]，确保测试结束后能清理 `viewModelScope`。 */
    private fun createViewModel(): DownloadVM {
        val fakeApi =
            Proxy.newProxyInstance(
                IwaraAPI::class.java.classLoader,
                arrayOf(IwaraAPI::class.java),
            ) { _, method, args ->
                when (method.name) {
                    "getProfile" -> {
                        val username = args?.firstOrNull() as? String ?: ""
                        profileResolver(username)
                    }
                    else -> error("Unexpected API call: ${method.name}")
                }
            } as IwaraAPI

        val factory =
            object : ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST")
                override fun <T : ViewModel> create(modelClass: Class<T>): T {
                    return DownloadVM(database, storage, UserRepo(fakeApi)) as T
                }
            }
        return ViewModelProvider(viewModelStore, factory)["vm_${viewModelStore.keys().size}", DownloadVM::class.java]
    }

    /** 轮询主线程 Looper 与条件断言直到成立或超时。 */
    private fun waitUntil(timeoutMs: Long = 5_000L, predicate: () -> Boolean) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            shadowOf(Looper.getMainLooper()).idle()
            if (predicate()) return
            Thread.sleep(15)
        }
        shadowOf(Looper.getMainLooper()).idle()
        assertTrue("Condition not met within ${timeoutMs}ms", predicate())
    }

    private fun createItem(
        resourceId: String,
        title: String,
        uri: String = "content://$PROVIDER_AUTHORITY/$resourceId.mp4",
        authorId: String = "author_default",
        authorName: String = if (authorId.isBlank()) "" else "Author $authorId",
        manualAuthor: Boolean = false,
    ): DownloadItem {
        return DownloadItem(
            title = title,
            thumbnail = "https://example.com/thumb.jpg",
            uri = uri,
            type = DownloadType.VIDEO,
            resourceId = resourceId,
            time = Instant.ofEpochSecond(1_700_000_000L),
            authorId = authorId,
            authorName = authorName,
            authorAvatar = "",
            sizeBytes = 2048L,
            quality = "Source",
            durationMs = 60_000L,
            publishedAtEpochSeconds = 1_699_000_000L,
            manualAuthor = manualAuthor,
        )
    }

    private fun createUser(
        id: String,
        username: String,
        name: String,
        avatar: IwaraFile? = null,
    ): User {
        return User(
            id = id,
            username = username,
            name = name,
            createdAt = Instant.EPOCH,
            deletedAt = null,
            followedBy = false,
            following = false,
            friend = false,
            premium = false,
            role = "user",
            seenAt = null,
            status = "active",
            updatedAt = Instant.EPOCH,
            avatar = avatar,
        )
    }

    /** 支持按文件名注入删除异常的本地测试 ContentProvider。 */
    private class ControlledDeleteProvider(private val rootDir: File) : ContentProvider() {
        val failOnDeleteNames = mutableSetOf<String>()

        init {
            rootDir.mkdirs()
        }

        fun createFile(name: String, content: ByteArray = ByteArray(16)): Uri {
            val file = File(rootDir, name)
            file.parentFile?.mkdirs()
            file.writeBytes(content)
            return Uri.parse("content://$PROVIDER_AUTHORITY/$name")
        }

        override fun onCreate() = true

        override fun query(
            uri: Uri,
            projection: Array<out String>?,
            selection: String?,
            selectionArgs: Array<out String>?,
            sortOrder: String?,
        ): Cursor? = null

        override fun getType(uri: Uri) = "video/mp4"

        override fun insert(uri: Uri, values: ContentValues?): Uri? = null

        override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int {
            val name = uri.lastPathSegment ?: return 0
            if (name in failOnDeleteNames) {
                throw SecurityException("Simulated delete failure for $name")
            }
            return if (File(rootDir, name).delete()) 1 else 0
        }

        override fun update(
            uri: Uri,
            values: ContentValues?,
            selection: String?,
            selectionArgs: Array<out String>?,
        ) = 0

        override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor {
            val name = uri.lastPathSegment ?: throw FileNotFoundException(uri.toString())
            val file = File(rootDir, name)
            if (!file.exists()) throw FileNotFoundException(uri.toString())
            return ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_WRITE)
        }
    }

    companion object {
        private const val PROVIDER_AUTHORITY = "me.rerere.awara.test.vm_download_provider"
    }
}
