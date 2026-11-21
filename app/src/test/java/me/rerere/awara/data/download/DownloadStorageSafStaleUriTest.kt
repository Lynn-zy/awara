package me.rerere.awara.data.download

import android.Manifest
import android.app.Application
import android.content.ComponentName
import android.content.Context
import android.content.IntentFilter
import android.content.pm.ProviderInfo
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.os.CancellationSignal
import android.os.ParcelFileDescriptor
import android.provider.DocumentsContract
import android.provider.DocumentsProvider
import androidx.room.Room
import java.io.File
import java.io.FileNotFoundException
import java.lang.reflect.Proxy
import java.time.Instant
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import me.rerere.awara.data.entity.DownloadItem
import me.rerere.awara.data.entity.DownloadType
import me.rerere.awara.data.repo.MediaRepo
import me.rerere.awara.data.source.IwaraAPI
import me.rerere.awara.di.AppDatabase
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * 验证 SAF（`DocumentsProvider` / `FileSystemProvider`）在文件被重命名或外部删除导致失效 Tree Document URI 时的行为。
 *
 * 复现真实场景：AOSP `FileSystemProvider.isChildDocument()` 在 `enforceTree()` 阶段会将缺失文件的
 * `FileNotFoundException` 包装为 `IllegalArgumentException("Failed to determine if ... is child of ...")` 抛出。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], application = Application::class)
class DownloadStorageSafStaleUriTest {

    private lateinit var context: Context
    private lateinit var storage: DownloadStorage
    private lateinit var provider: FakeSafDocumentsProvider
    private lateinit var rootDir: File

    @Before
    fun setUp() {
        context = RuntimeEnvironment.getApplication()
        rootDir = File(context.cacheDir, "fake_saf_root").apply {
            deleteRecursively()
            mkdirs()
        }

        val providerInfo =
            ProviderInfo().apply {
                authority = SAF_AUTHORITY
                packageName = context.packageName
                name = FakeSafDocumentsProvider::class.java.name
                exported = true
                grantUriPermissions = true
                readPermission = Manifest.permission.MANAGE_DOCUMENTS
                writePermission = Manifest.permission.MANAGE_DOCUMENTS
            }
        val componentName =
            ComponentName(context.packageName, FakeSafDocumentsProvider::class.java.name)
        val shadowPackageManager = shadowOf(context.packageManager)
        shadowPackageManager.addOrUpdateProvider(providerInfo)
        shadowPackageManager.addIntentFilterForProvider(
            componentName,
            IntentFilter(DocumentsContract.PROVIDER_INTERFACE),
        )

        provider =
            Robolectric.buildContentProvider(FakeSafDocumentsProvider::class.java)
                .create(providerInfo)
                .get()
        provider.rootDir = rootDir
        storage = DownloadStorage(context)
    }

    @After
    fun tearDown() {
        if (::rootDir.isInitialized) {
            rootDir.deleteRecursively()
        }
    }

    @Test
    fun existsReturnsFalseWhenSafProviderThrowsChildCheckIllegalArgumentExceptionForMissingFile() {
        val staleUri = buildTreeDocUri(OLD_FILE_NAME)

        assertTrue(
            "测试前置校验：URI 必须被系统识别为 SAF DocumentUri",
            DocumentsContract.isDocumentUri(context, staleUri),
        )
        assertFalse(storage.exists(staleUri))
    }

    @Test
    fun deleteTreatsMissingSafDocumentIllegalArgumentExceptionAsAlreadyDeleted() {
        val staleUri = buildTreeDocUri(OLD_FILE_NAME)

        assertTrue(DocumentsContract.isDocumentUri(context, staleUri))
        storage.delete(staleUri)
    }

    @Test
    fun existsAndDeleteRethrowNonMissingIllegalArgumentException() {
        provider.createFile(NEW_FILE_NAME)
        val docId = docIdFor(NEW_FILE_NAME)
        provider.nonMissingIllegalArgDocIds += docId
        val existingUri = buildTreeDocUri(NEW_FILE_NAME)

        val existsError =
            assertThrows(IllegalArgumentException::class.java) { storage.exists(existingUri) }
        assertTrue(existsError.message.orEmpty().contains("Simulated invalid argument"))

        val deleteError =
            assertThrows(IllegalArgumentException::class.java) { storage.delete(existingUri) }
        assertTrue(deleteError.message.orEmpty().contains("Simulated invalid argument"))
    }

    @Test
    fun existsAndDeleteRethrowSecurityExceptionWhenPermissionDenied() {
        provider.createFile(NEW_FILE_NAME)
        provider.securityErrorDocIds += docIdFor(NEW_FILE_NAME)
        val deniedUri = buildTreeDocUri(NEW_FILE_NAME)

        assertThrows(SecurityException::class.java) { storage.exists(deniedUri) }
        assertThrows(SecurityException::class.java) { storage.delete(deniedUri) }
    }

    @Test
    fun existsAndDeleteRethrowIllegalStateExceptionWhenVolumeUnavailable() {
        provider.illegalStateDocIds += docIdFor("unmounted_volume.mp4")
        val unmountedUri = buildTreeDocUri("unmounted_volume.mp4")

        assertThrows(IllegalStateException::class.java) { storage.exists(unmountedUri) }
        assertThrows(IllegalStateException::class.java) { storage.delete(unmountedUri) }
    }

    @Test
    fun scanAndImportRepairsOldSafUriAfterFileWasRenamedToOfficialFormat() = runBlocking {
        // 磁盘上只有重命名后的官网格式文件；数据库里存的仍是旧版命名格式的 SAF URI
        val newFile = provider.createFile(NEW_FILE_NAME, ByteArray(2048) { 1 })
        val staleOldUri = buildTreeDocUri(OLD_FILE_NAME)
        val expectedNewUri = buildTreeDocUri(NEW_FILE_NAME)

        val database =
            Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
                .allowMainThreadQueries()
                .build()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        try {
            database
                .downloadDao()
                .insertDownloadItem(
                    DownloadItem(
                        title = "FUTA Kawakaze - Erotuber (ふたなり江風)",
                        thumbnail = "https://i.iwara.tv/thumb/BLxCOsxPpEjpK0.jpg",
                        uri = staleOldUri.toString(),
                        type = DownloadType.VIDEO,
                        resourceId = "BLxCOsxPpEjpK0",
                        time = Instant.ofEpochSecond(1700000000L),
                        authorId = "creator_1",
                        authorName = "Creator 1",
                        sizeBytes = 1024L,
                        quality = "source",
                        durationMs = 30_000L,
                    )
                )

            val realDataSource =
                AndroidDownloadScanDataSource(
                    context = context,
                    downloadStorage = storage,
                    downloadDao = database.downloadDao(),
                    mediaRepo = MediaRepo(OkHttpClient(), createUnusedIwaraApi()),
                )
            // 仅绕过 Robolectric 的 5 参数 SAF query 限制与无原生 MP4 解码器限制；
            // 旧 URI 存在性校验（AndroidDownloadScanDataSource -> DownloadStorage.exists -> DocumentsProvider.enforceTree）
            // 与 Room 路径修复写库全部走真实生产实现。
            val hybridDataSource =
                object : DownloadScanDataSource by realDataSource {
                    override suspend fun scanLocalDirectory(
                        treeUriString: String?
                    ): LocalDirectoryScanSnapshot {
                        return LocalDirectoryScanSnapshot(
                            files =
                                listOf(
                                    ScannedFileCandidate(
                                        uriString = expectedNewUri.toString(),
                                        displayName = newFile.name,
                                        sizeBytes = newFile.length(),
                                        lastModifiedMillis = newFile.lastModified(),
                                    )
                                ),
                            existingItems = database.downloadDao().getAllDownloadedItems(),
                        )
                    }

                    override suspend fun inspectLocalVideo(
                        uriString: String,
                        extractMediaInfo: Boolean,
                    ): LocalVideoInspection {
                        if (!extractMediaInfo) {
                            return realDataSource.inspectLocalVideo(
                                uriString,
                                extractMediaInfo = false,
                            )
                        }
                        val base =
                            realDataSource.inspectLocalVideo(uriString, extractMediaInfo = false)
                        if (!base.exists) return base
                        return LocalVideoInspection(
                            exists = true,
                            mediaInfo =
                                ProbedVideoMediaInfo(
                                    durationMs = 45_000L,
                                    width = 1920,
                                    height = 1080,
                                ),
                        )
                    }
                }

            val scanner =
                DownloadFolderScanner(
                    dataSource = hybridDataSource,
                    backgroundScope = scope,
                )
            val summary = scanner.scanAndImport(TREE_URI.toString())

            assertEquals(0, summary.newlyImportedCount)
            assertEquals(1, summary.repairedCount)
            assertEquals(0, summary.metadataTargetCount)
            assertEquals(0, summary.skippedExistingCount)

            val repairedItem = database.downloadDao().getDownloadItem("BLxCOsxPpEjpK0")
            assertEquals(expectedNewUri.toString(), repairedItem?.uri)
            assertEquals(2048L, repairedItem?.sizeBytes)
            assertEquals(45_000L, repairedItem?.durationMs)
        } finally {
            scope.cancel()
            database.close()
        }
    }

    private fun createUnusedIwaraApi(): IwaraAPI {
        return Proxy.newProxyInstance(
            IwaraAPI::class.java.classLoader,
            arrayOf(IwaraAPI::class.java),
        ) { _, method, _ ->
            throw AssertionError("Unexpected network call: ${method.name}")
        } as IwaraAPI
    }

    /**
     * 模拟 Android `ExternalStorageProvider` / `FileSystemProvider` 的真实 SAF 行为：
     * 当访问已重命名或已删除的 Tree 子文档时，`isChildDocument()` 将底层的 `FileNotFoundException`
     * 包装为 `IllegalArgumentException("Failed to determine if ... is child of ...")` 抛出。
     */
    class FakeSafDocumentsProvider : DocumentsProvider() {
        lateinit var rootDir: File
        val securityErrorDocIds = mutableSetOf<String>()
        val illegalStateDocIds = mutableSetOf<String>()
        val nonMissingIllegalArgDocIds = mutableSetOf<String>()

        fun createFile(name: String, content: ByteArray = ByteArray(32)): File {
            val file = File(rootDir, name)
            file.parentFile?.mkdirs()
            file.writeBytes(content)
            return file
        }

        private fun resolveFileOrThrowChildCheckError(documentId: String): File {
            if (documentId == TREE_DOC_ID) {
                return rootDir
            }
            if (documentId in securityErrorDocIds) {
                throw SecurityException("Permission Denial for $documentId")
            }
            if (documentId in illegalStateDocIds) {
                throw IllegalStateException("Storage volume unavailable for $documentId")
            }
            if (documentId in nonMissingIllegalArgDocIds) {
                throw IllegalArgumentException("Simulated invalid argument for $documentId")
            }
            val fileName = documentId.removePrefix("$TREE_DOC_ID/")
            val file = File(rootDir, fileName)
            if (!file.exists()) {
                val missing =
                    FileNotFoundException(
                        "Missing file for $documentId at /storage/emulated/0/Download/Awara/$fileName"
                    )
                // 与 AOSP FileSystemProvider.isChildDocument() 一致的异常类型与文案格式
                throw IllegalArgumentException(
                    "Failed to determine if $documentId is child of $TREE_DOC_ID: $missing"
                )
            }
            return file
        }

        override fun onCreate(): Boolean = true

        override fun isChildDocument(parentDocumentId: String?, documentId: String?): Boolean {
            if (documentId == null || parentDocumentId == null) return false
            if (documentId == parentDocumentId) return true
            resolveFileOrThrowChildCheckError(documentId)
            return documentId.startsWith("$parentDocumentId/")
        }

        override fun queryRoots(projection: Array<out String>?): Cursor =
            MatrixCursor(projection ?: emptyArray())

        override fun queryDocument(documentId: String, projection: Array<out String>?): Cursor {
            resolveFileOrThrowChildCheckError(documentId)
            return MatrixCursor(projection ?: emptyArray())
        }

        override fun queryChildDocuments(
            parentDocumentId: String,
            projection: Array<out String>?,
            sortOrder: String?,
        ): Cursor = MatrixCursor(projection ?: emptyArray())

        override fun openDocument(
            documentId: String,
            mode: String,
            signal: CancellationSignal?,
        ): ParcelFileDescriptor {
            val file = resolveFileOrThrowChildCheckError(documentId)
            return ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
        }

        override fun deleteDocument(documentId: String) {
            val file = resolveFileOrThrowChildCheckError(documentId)
            if (!file.delete()) {
                throw FileNotFoundException("Failed to delete $documentId")
            }
        }
    }

    companion object {
        private const val SAF_AUTHORITY = "me.rerere.awara.test.externalstorage.documents"
        private const val TREE_DOC_ID = "primary:Download/Awara"
        private const val OLD_FILE_NAME =
            "FUTA Kawakaze - Erotuber (ふたなり江風)_BLxCOsxPpEjpK0.mp4"
        private const val NEW_FILE_NAME =
            "Iwara - FUTA Kawakaze - Erotuber (ふたなり江風) [BLxCOsxPpEjpK0] [Source].mp4"
        private val TREE_URI: Uri =
            DocumentsContract.buildTreeDocumentUri(SAF_AUTHORITY, TREE_DOC_ID)

        private fun docIdFor(fileName: String): String = "$TREE_DOC_ID/$fileName"

        private fun buildTreeDocUri(fileName: String): Uri =
            DocumentsContract.buildDocumentUriUsingTree(TREE_URI, docIdFor(fileName))
    }
}
