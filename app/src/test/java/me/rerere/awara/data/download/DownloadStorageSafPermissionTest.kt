package me.rerere.awara.data.download

import android.Manifest
import android.app.Application
import android.content.ComponentName
import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.content.pm.ProviderInfo
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.os.Bundle
import android.os.Looper
import android.os.ParcelFileDescriptor
import android.provider.DocumentsContract
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.room.Room
import java.io.File
import java.io.FileNotFoundException
import java.lang.reflect.Proxy
import java.time.Instant
import kotlinx.coroutines.runBlocking
import me.rerere.awara.data.entity.DownloadItem
import me.rerere.awara.data.entity.DownloadType
import me.rerere.awara.data.repo.UserRepo
import me.rerere.awara.data.source.IwaraAPI
import me.rerere.awara.di.AppDatabase
import me.rerere.awara.ui.page.download.DownloadVM
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
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
import org.robolectric.shadows.ShadowContentResolver

/**
 * 验证 SAF 目录读写权限持久化、旧版只读授权识别升级，以及删除本地扫描视频时缺写权限的补授权与自动续删闭环。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], application = Application::class)
class DownloadStorageSafPermissionTest {

    private lateinit var baseContext: Context
    private lateinit var context: SafGrantAwareContextWrapper
    private lateinit var storage: DownloadStorage
    private lateinit var safProvider: PermissionCheckingSafProvider
    private lateinit var regularProvider: SimpleDeletableProvider
    private lateinit var safRootDir: File
    private lateinit var regularRootDir: File
    private lateinit var database: AppDatabase
    private lateinit var viewModelStore: ViewModelStore

    @Before
    fun setUp() {
        stopKoin()
        baseContext = RuntimeEnvironment.getApplication()
        context = SafGrantAwareContextWrapper(baseContext)
        startKoin { modules(module { single<Context> { context } }) }

        safRootDir =
            File(context.cacheDir, "saf_permission_root").apply {
                deleteRecursively()
                mkdirs()
            }
        regularRootDir =
            File(context.cacheDir, "regular_delete_root").apply {
                deleteRecursively()
                mkdirs()
            }

        val providerInfo =
            ProviderInfo().apply {
                authority = SAF_AUTHORITY
                packageName = context.packageName
                name = PermissionCheckingSafProvider::class.java.name
                exported = true
                grantUriPermissions = true
                readPermission = Manifest.permission.MANAGE_DOCUMENTS
                writePermission = Manifest.permission.MANAGE_DOCUMENTS
            }
        val componentName =
            ComponentName(context.packageName, PermissionCheckingSafProvider::class.java.name)
        val shadowPackageManager = shadowOf(context.packageManager)
        shadowPackageManager.addOrUpdateProvider(providerInfo)
        shadowPackageManager.addIntentFilterForProvider(
            componentName,
            IntentFilter(DocumentsContract.PROVIDER_INTERFACE),
        )

        safProvider =
            Robolectric.buildContentProvider(PermissionCheckingSafProvider::class.java)
                .create(providerInfo)
                .get()
        safProvider.rootDir = safRootDir

        regularProvider = SimpleDeletableProvider(regularRootDir)
        ShadowContentResolver.registerProviderInternal(REGULAR_AUTHORITY, regularProvider)

        database =
            Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
                .allowMainThreadQueries()
                .build()
        storage = DownloadStorage(context)
        viewModelStore = ViewModelStore()
    }

    @After
    fun tearDown() {
        viewModelStore.clear()
        database.close()
        stopKoin()
        if (::safRootDir.isInitialized) safRootDir.deleteRecursively()
        if (::regularRootDir.isInitialized) regularRootDir.deleteRecursively()
    }

    @Test
    fun defaultDirectoryDetectionRequiresBothReadAndWritePersistedPermission() {
        // 复现旧版仅持久化只读权限的状态：必须视为尚未完成可删除读写授权
        context.contentResolver.takePersistableUriPermission(
            TREE_URI,
            Intent.FLAG_GRANT_READ_URI_PERMISSION,
        )

        assertNull(storage.findPersistedDefaultAwaraTreeUri())
        assertFalse(storage.canScanDefaultDirectoryDirectly())

        // 升级为读写持久化授权后，目录可直接扫描且具备写权限
        storage.persistTreeReadWritePermission(TREE_URI)

        assertEquals(TREE_URI, storage.findPersistedDefaultAwaraTreeUri())
        assertTrue(storage.canScanDefaultDirectoryDirectly())
        val persisted =
            context.contentResolver.persistedUriPermissions.firstOrNull { it.uri == TREE_URI }
        assertNotNull(persisted)
        assertTrue(persisted!!.isReadPermission)
        assertTrue(persisted.isWritePermission)
    }

    @Test
    fun persistTreeReadWritePermissionRejectsRestrictedAndReadOnlyDirectories() {
        val restrictedTreeUri =
            DocumentsContract.buildTreeDocumentUri(SAF_AUTHORITY, "primary:Android/data")
        assertThrows(IllegalArgumentException::class.java) {
            storage.persistTreeReadWritePermission(restrictedTreeUri)
        }

        safProvider.directoryWritable = false
        assertThrows(IllegalArgumentException::class.java) {
            storage.persistTreeReadWritePermission(TREE_URI)
        }
    }

    @Test
    fun safWriteTargetResolutionMatchesOnlyTargetDirectoryAndStopsPromptingOnceGranted() {
        val fileUri = buildTreeDocUri("Iwara - Sample [abc12345] [Source].mp4")
        val target = storage.resolveSafTreeWriteTarget(fileUri)
        assertNotNull(target)
        assertEquals(SAF_AUTHORITY, target!!.authority)
        assertEquals(TREE_DOC_ID, target.treeDocumentId)
        assertEquals(
            DocumentsContract.buildDocumentUri(SAF_AUTHORITY, TREE_DOC_ID),
            target.initialPickerUri,
        )
        assertEquals("Download/Awara", target.displayLabel)

        val wrongParentTreeUri =
            DocumentsContract.buildTreeDocumentUri(SAF_AUTHORITY, "primary:Download")
        val wrongSiblingTreeUri =
            DocumentsContract.buildTreeDocumentUri(SAF_AUTHORITY, "primary:Movies/Awara")
        assertTrue(storage.matchesSafTreeWriteTarget(TREE_URI, target))
        assertFalse(storage.matchesSafTreeWriteTarget(wrongParentTreeUri, target))
        assertFalse(storage.matchesSafTreeWriteTarget(wrongSiblingTreeUri, target))

        // 仅持有旧版只读授权时，必须识别为缺失写权限目标
        context.contentResolver.takePersistableUriPermission(
            TREE_URI,
            Intent.FLAG_GRANT_READ_URI_PERMISSION,
        )
        assertEquals(target, storage.findMissingSafWriteTarget(fileUri))

        // 补授读写权限后，不再返回缺权目标，避免底层其他安全异常触发重复弹窗死循环
        storage.persistTreeReadWritePermission(TREE_URI)
        assertNull(storage.findMissingSafWriteTarget(fileUri))
    }

    @Test
    fun downloadVmCapturesReadOnlySafDeleteAndRetriesCleanlyAfterGrantingWritePermission() =
        runBlocking {
            // 仅授予只读权限，真实复现旧版扫描导入本地视频后的删除失败场景
            context.contentResolver.takePersistableUriPermission(
                TREE_URI,
                Intent.FLAG_GRANT_READ_URI_PERMISSION,
            )

            val regularUri = regularProvider.createFile("mediastore_ok.mp4")
            val safFile1 = safProvider.createFile("Iwara - First [vid00001] [Source].mp4")
            val safFile2 = safProvider.createFile("Iwara - Second [vid00002] [Source].mp4")
            val safUri1 = buildTreeDocUri(safFile1.name)
            val safUri2 = buildTreeDocUri(safFile2.name)

            val regularId =
                database
                    .downloadDao()
                    .insertDownloadItem(
                        createItem(resourceId = "vid_regular", uri = regularUri.toString())
                    )
                    .toInt()
            val safId1 =
                database
                    .downloadDao()
                    .insertDownloadItem(createItem(resourceId = "vid00001", uri = safUri1.toString()))
                    .toInt()
            val safId2 =
                database
                    .downloadDao()
                    .insertDownloadItem(createItem(resourceId = "vid00002", uri = safUri2.toString()))
                    .toInt()

            val vm = createViewModel()
            waitUntil { vm.state.items.size == 3 }

            val initialItems = vm.state.items
            var firstResult: Pair<Set<Int>, Set<Int>>? = null
            vm.delete(initialItems) { successIds, failedIds ->
                firstResult = successIds to failedIds
            }
            waitUntil { firstResult != null && vm.state.deletingIds.isEmpty() }

            // 非 SAF 缺权条目已删除；两个同目录只读 SAF 条目合并到单一补授权请求且不报原始英文错误
            assertEquals(setOf(regularId), firstResult!!.first)
            assertEquals(setOf(safId1, safId2), firstResult!!.second)
            assertNull(vm.state.deleteError)
            assertNull(database.downloadDao().getDownloadItem("vid_regular"))
            assertTrue(safFile1.exists())
            assertTrue(safFile2.exists())

            val pending = vm.state.pendingDeleteWritePermission
            assertNotNull(pending)
            assertEquals("Download/Awara", pending!!.target.displayLabel)
            assertEquals(setOf(safId1, safId2), pending.items.map { it.id }.toSet())

            // 模拟用户点“去授权”并在系统选择器中完成读写授权，随后自动重试待删条目
            storage.persistTreeReadWritePermission(TREE_URI)
            vm.clearPendingDeleteWritePermission()

            var retryResult: Pair<Set<Int>, Set<Int>>? = null
            vm.delete(pending.items) { successIds, failedIds ->
                retryResult = successIds to failedIds
            }
            waitUntil { retryResult != null && vm.state.deletingIds.isEmpty() }

            assertEquals(setOf(safId1, safId2), retryResult!!.first)
            assertTrue(retryResult!!.second.isEmpty())
            assertNull(vm.state.pendingDeleteWritePermission)
            assertNull(vm.state.deleteError)
            assertFalse(safFile1.exists())
            assertFalse(safFile2.exists())
            assertNull(database.downloadDao().getDownloadItem("vid00001"))
            assertNull(database.downloadDao().getDownloadItem("vid00002"))
        }

    @Test
    fun downloadVmReportsErrorInsteadOfLoopingWhenWritePermissionAlreadyPersisted() = runBlocking {
        storage.persistTreeReadWritePermission(TREE_URI)
        val lockedFile = safProvider.createFile("Iwara - Locked [vid99999] [Source].mp4")
        safProvider.forceSecurityFailureNames += lockedFile.name
        val lockedUri = buildTreeDocUri(lockedFile.name)

        database
            .downloadDao()
            .insertDownloadItem(createItem(resourceId = "vid99999", uri = lockedUri.toString()))

        val vm = createViewModel()
        waitUntil { vm.state.items.size == 1 }

        var finished = false
        vm.delete(vm.state.items) { _, _ -> finished = true }
        waitUntil { finished && vm.state.deletingIds.isEmpty() }

        assertNull(
            "已持有持久写权限时不应重复触发补授权弹窗",
            vm.state.pendingDeleteWritePermission,
        )
        assertNotNull(vm.state.deleteError)
        assertTrue(lockedFile.exists())
    }

    private fun createViewModel(): DownloadVM {
        val fakeApi =
            Proxy.newProxyInstance(
                IwaraAPI::class.java.classLoader,
                arrayOf(IwaraAPI::class.java),
            ) { _, method, _ ->
                error("Unexpected API call: ${method.name}")
            } as IwaraAPI

        val factory =
            object : ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST")
                override fun <T : ViewModel> create(modelClass: Class<T>): T {
                    return DownloadVM(database, storage, UserRepo(fakeApi)) as T
                }
            }
        return ViewModelProvider(viewModelStore, factory)[
            "vm_${viewModelStore.keys().size}", DownloadVM::class.java]
    }

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

    private fun createItem(resourceId: String, uri: String): DownloadItem =
        DownloadItem(
            title = "Video $resourceId",
            thumbnail = "https://example.com/thumb.jpg",
            uri = uri,
            type = DownloadType.VIDEO,
            resourceId = resourceId,
            time = Instant.ofEpochSecond(1_700_000_000L),
            authorId = "author_1",
            authorName = "Author 1",
            sizeBytes = 1024L,
            quality = "Source",
            durationMs = 30_000L,
        )

    /**
     * 模拟 Android AMS 对 SAF Tree 前缀 URI 的权限检查行为：Robolectric 默认的 `ShadowContextImpl`
     * 不根据 `persistedUriPermissions` 解析 `/tree/.../document/...` 前缀授权，会导致 `DocumentFile.canWrite()`
     * 恒返回 false；此处按真实系统规则将 `checkCallingOrSelfUriPermission` 映射至已持久化授权记录。
     */
    private class SafGrantAwareContextWrapper(base: Context) : ContextWrapper(base) {
        override fun checkCallingOrSelfUriPermission(uri: Uri, modeFlags: Int): Int {
            val needRead = (modeFlags and Intent.FLAG_GRANT_READ_URI_PERMISSION) != 0
            val needWrite = (modeFlags and Intent.FLAG_GRANT_WRITE_URI_PERMISSION) != 0
            val uriTreeDocId = runCatching { DocumentsContract.getTreeDocumentId(uri) }.getOrNull()
            val granted =
                contentResolver.persistedUriPermissions.any { perm ->
                    val permTreeDocId =
                        runCatching { DocumentsContract.getTreeDocumentId(perm.uri) }.getOrNull()
                    val matchesTree =
                        perm.uri == uri ||
                            (uriTreeDocId != null &&
                                permTreeDocId != null &&
                                perm.uri.authority == uri.authority &&
                                permTreeDocId.equals(uriTreeDocId, ignoreCase = true))
                    matchesTree &&
                        (!needRead || perm.isReadPermission) &&
                        (!needWrite || perm.isWritePermission)
                }
            return if (granted) {
                PackageManager.PERMISSION_GRANTED
            } else {
                PackageManager.PERMISSION_DENIED
            }
        }
    }

    /**
     * 模拟 `ExternalStorageProvider` 的真实读写校验：
     * - 直接继承 `ContentProvider` 以规避 Robolectric 在 API 33 上 5 参数 `query()` 直调
     *   `DocumentsProvider.query(5 params)` 抛出 `UnsupportedOperationException` 的框架限制；
     * - `query()` 返回目录/文件 `MIME_TYPE` 与 `FLAGS`，使 `DocumentFile.fromTreeUri().canWrite()` 走完真实查询链路；
     * - `call(METHOD_DELETE_DOCUMENT)` 校验 `persistedUriPermissions` 是否具备写权限，缺失时抛出与线上一致的
     *   `SecurityException("Permission Denial: writing ...")`。
     */
    class PermissionCheckingSafProvider : ContentProvider() {
        lateinit var rootDir: File
        var directoryWritable: Boolean = true
        val forceSecurityFailureNames = mutableSetOf<String>()

        fun createFile(name: String, content: ByteArray = ByteArray(32)): File {
            val file = File(rootDir, name)
            file.parentFile?.mkdirs()
            file.writeBytes(content)
            return file
        }

        override fun onCreate(): Boolean = true

        override fun query(
            uri: Uri,
            projection: Array<out String>?,
            selection: String?,
            selectionArgs: Array<out String>?,
            sortOrder: String?,
        ): Cursor {
            val documentId = DocumentsContract.getDocumentId(uri)
            val columns =
                projection
                    ?: arrayOf(
                        DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                        DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                        DocumentsContract.Document.COLUMN_MIME_TYPE,
                        DocumentsContract.Document.COLUMN_FLAGS,
                    )
            val cursor = MatrixCursor(columns)
            val isDir = documentId == TREE_DOC_ID
            val displayName =
                if (isDir) "Awara" else documentId.removePrefix("$TREE_DOC_ID/")
            val mimeType =
                if (isDir) DocumentsContract.Document.MIME_TYPE_DIR else "video/mp4"
            val flags =
                if (isDir) {
                    if (directoryWritable) {
                        DocumentsContract.Document.FLAG_DIR_SUPPORTS_CREATE or
                            DocumentsContract.Document.FLAG_SUPPORTS_WRITE
                    } else {
                        0
                    }
                } else {
                    DocumentsContract.Document.FLAG_SUPPORTS_DELETE or
                        DocumentsContract.Document.FLAG_SUPPORTS_WRITE
                }
            val row =
                columns.map { column ->
                    when (column) {
                        DocumentsContract.Document.COLUMN_DOCUMENT_ID -> documentId
                        DocumentsContract.Document.COLUMN_DISPLAY_NAME -> displayName
                        DocumentsContract.Document.COLUMN_MIME_TYPE -> mimeType
                        DocumentsContract.Document.COLUMN_FLAGS -> flags
                        else -> null
                    }
                }
            cursor.addRow(row)
            return cursor
        }

        override fun getType(uri: Uri): String {
            val documentId = DocumentsContract.getDocumentId(uri)
            return if (documentId == TREE_DOC_ID) {
                DocumentsContract.Document.MIME_TYPE_DIR
            } else {
                "video/mp4"
            }
        }

        override fun insert(uri: Uri, values: ContentValues?): Uri? = null

        override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int =
            0

        override fun update(
            uri: Uri,
            values: ContentValues?,
            selection: String?,
            selectionArgs: Array<out String>?,
        ): Int = 0

        override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor {
            val documentId = DocumentsContract.getDocumentId(uri)
            val fileName = documentId.removePrefix("$TREE_DOC_ID/")
            val file = File(rootDir, fileName)
            if (!file.exists()) throw FileNotFoundException("Missing $documentId")
            return ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
        }

        override fun call(method: String, arg: String?, extras: Bundle?): Bundle {
            if (method != "android:deleteDocument") {
                throw UnsupportedOperationException("Unsupported SAF call: $method")
            }
            @Suppress("DEPRECATION")
            val targetUri =
                extras?.getParcelable<Uri>("uri")
                    ?: throw IllegalArgumentException("Missing uri extra")
            val documentId = DocumentsContract.getDocumentId(targetUri)
            val hasWriteGrant =
                context
                    ?.contentResolver
                    ?.persistedUriPermissions
                    ?.any { perm -> perm.isWritePermission && perm.uri == TREE_URI } == true
            val fileName = documentId.removePrefix("$TREE_DOC_ID/")
            if (!hasWriteGrant || fileName in forceSecurityFailureNames) {
                throw SecurityException(
                    "Permission Denial: writing com.android.externalstorage.ExternalStorageProvider " +
                        "uri $targetUri requires android.permission.MANAGE_DOCUMENTS, " +
                        "or grantUriPermission()"
                )
            }
            val file = File(rootDir, fileName)
            if (!file.exists() || !file.delete()) {
                throw FileNotFoundException("Failed to delete $documentId")
            }
            return Bundle.EMPTY
        }
    }

    private class SimpleDeletableProvider(private val rootDir: File) : ContentProvider() {
        fun createFile(name: String, content: ByteArray = ByteArray(16)): Uri {
            val file = File(rootDir, name)
            file.parentFile?.mkdirs()
            file.writeBytes(content)
            return Uri.parse("content://$REGULAR_AUTHORITY/$name")
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
        private const val SAF_AUTHORITY = "com.android.externalstorage.documents"
        private const val REGULAR_AUTHORITY = "me.rerere.awara.test.regular_download_provider"
        private const val TREE_DOC_ID = "primary:Download/Awara"
        private val TREE_URI: Uri =
            DocumentsContract.buildTreeDocumentUri(SAF_AUTHORITY, TREE_DOC_ID)

        private fun buildTreeDocUri(fileName: String): Uri =
            DocumentsContract.buildDocumentUriUsingTree(TREE_URI, "$TREE_DOC_ID/$fileName")
    }
}
