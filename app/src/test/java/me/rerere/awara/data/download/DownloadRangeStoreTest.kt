package me.rerere.awara.data.download

import java.io.RandomAccessFile
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * DownloadRangeStore 单元测试
 *
 * 验证侧车持久化、原子落盘、损坏容错、连续前缀截断与状态迁移
 */
class DownloadRangeStoreTest {

    @get:Rule val tempFolder = TemporaryFolder()

    /** 测试侧车状态写入、读取及字段完整性 */
    @Test
    fun sidecarWriteAndReadPreservesAllFieldsAndNoTempFile() {
        val targetFile = tempFolder.newFile("test_video.mp4.part")
        val sha1 = "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855"
        val sha2 = "ca978112ca1bbdcafac231b39a23dc4da786eff8147c4e72b9807785afee48bb"

        val originalState =
            DownloadRangeState(
                resourceKey = "https://www.iwara.tv/?filename=video.mp4&path=%2Ffile",
                total = 1048576L,
                strongETag = "\"test-etag-12345\"",
                ranges = listOf(RangeEntry(0L, 524287L, sha1), RangeEntry(524288L, 1048575L, sha2)),
            )

        DownloadRangeStore.writeState(targetFile, originalState)

        // 验证侧车存在且临时文件已被清理
        assertTrue(DownloadRangeStore.hasState(targetFile))
        assertFalse(DownloadRangeStore.tempSidecarFile(targetFile).exists())

        // 读取并比对内容
        val restored = DownloadRangeStore.readState(targetFile)
        assertNotNull(restored)
        assertEquals(originalState.resourceKey, restored!!.resourceKey)
        assertEquals(originalState.total, restored.total)
        assertEquals(originalState.strongETag, restored.strongETag)
        assertEquals(2, restored.ranges.size)
        assertEquals(0L, restored.ranges[0].start)
        assertEquals(524287L, restored.ranges[0].end)
        assertEquals(sha1, restored.ranges[0].sha256)
        assertEquals(524288L, restored.ranges[1].start)
        assertEquals(1048575L, restored.ranges[1].end)
        assertEquals(sha2, restored.ranges[1].sha256)
        assertEquals(1048576L, restored.completedBytes)
        assertTrue(restored.isComplete)
    }

    /** 测试在侧车损坏时 completedBytes 严格返回 0L 而非错误回退读取 file.length */
    @Test
    fun hasStateAndCompletedBytesWithCorruptedSidecarReturnsZero() {
        val targetFile = tempFolder.newFile("sparse.part")
        // 构造一个物理长度为 2048 字节的文件（可能含有空洞）
        RandomAccessFile(targetFile, "rw").use { it.setLength(2048L) }

        // 1. 无侧车状态：返回物理文件长度
        assertFalse(DownloadRangeStore.hasState(targetFile))
        assertEquals(2048L, DownloadRangeStore.completedBytes(targetFile))

        // 2. 正常侧车状态：返回已记录区间的长度和（例如只有 0..511，共 512 字节）
        val normalState =
            DownloadRangeState(
                resourceKey = "res-key-1",
                total = 2048L,
                strongETag = "\"valid-etag\"",
                ranges =
                    listOf(
                        RangeEntry(
                            0L,
                            511L,
                            "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855",
                        )
                    ),
            )
        DownloadRangeStore.writeState(targetFile, normalState)
        assertTrue(DownloadRangeStore.hasState(targetFile))
        assertEquals(512L, DownloadRangeStore.completedBytes(targetFile))

        // 3. 损坏侧车状态（注入乱码或残缺内容）：严格返回 0L，绝对不能返回 2048L！
        val sidecar = DownloadRangeStore.sidecarFile(targetFile)
        sidecar.writeText("CORRUPTED_GARBAGE_DATA_12345\n")
        assertTrue(DownloadRangeStore.hasState(targetFile))
        assertNull(DownloadRangeStore.readState(targetFile))
        assertEquals(0L, DownloadRangeStore.completedBytes(targetFile))
    }

    /** 测试 delete 仅删除侧车元数据和临时侧车，绝不误删主 .part 数据文件 */
    @Test
    fun deleteRemovesSidecarAndTempOnly() {
        val targetFile = tempFolder.newFile("main.part").apply { writeText("MAIN_DATA") }
        val sidecar = DownloadRangeStore.sidecarFile(targetFile).apply { writeText("SIDECAR") }
        val tempSidecar = DownloadRangeStore.tempSidecarFile(targetFile).apply { writeText("TEMP") }

        assertTrue(targetFile.exists())
        assertTrue(sidecar.exists())
        assertTrue(tempSidecar.exists())

        DownloadRangeStore.delete(targetFile)

        assertTrue(targetFile.exists())
        assertEquals("MAIN_DATA", targetFile.readText())
        assertFalse(sidecar.exists())
        assertFalse(tempSidecar.exists())
    }

    /** 测试含有空洞时安全回退至连续前缀（保留 [0, 99]，截断掉 [200, 299]，清除侧车） */
    @Test
    fun rollbackToSafeSequentialPrefixWithHoles() {
        val targetFile = tempFolder.newFile("holes.part")
        // 构造总长 300 字节的稀疏文件
        RandomAccessFile(targetFile, "rw").use { it.setLength(300L) }

        // 侧车记录区间 [0, 99] 和 [200, 299]，中间 100..199 为空洞
        val state =
            DownloadRangeState(
                resourceKey = "res-key-hole",
                total = 300L,
                strongETag = "\"etag-hole\"",
                ranges =
                    listOf(
                        RangeEntry(
                            0L,
                            99L,
                            DownloadRangeStore.computeFileHash(targetFile, 0L, 100L),
                        ),
                        RangeEntry(
                            200L,
                            299L,
                            DownloadRangeStore.computeFileHash(targetFile, 200L, 100L),
                        ),
                    ),
            )
        DownloadRangeStore.writeState(targetFile, state)

        DownloadRangeStore.rollbackToSafeSequentialPrefix(targetFile)

        // 验证文件被安全截断至 100 字节连续前缀，且侧车被完全清理
        assertEquals(100L, targetFile.length())
        assertFalse(DownloadRangeStore.hasState(targetFile))
    }

    /** 测试若首块非 0 则连续前缀长度为 0，安全截断至 0 字节 */
    @Test
    fun rollbackToSafeSequentialPrefixWithoutZeroStart() {
        val targetFile = tempFolder.newFile("no_zero.part")
        RandomAccessFile(targetFile, "rw").use { it.setLength(200L) }

        // 侧车只包含 [100, 199]，没有从 0 开始的前缀
        val state =
            DownloadRangeState(
                resourceKey = "res-key-no-zero",
                total = 200L,
                strongETag = "\"etag-no-zero\"",
                ranges =
                    listOf(
                        RangeEntry(
                            100L,
                            199L,
                            "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855",
                        )
                    ),
            )
        DownloadRangeStore.writeState(targetFile, state)

        DownloadRangeStore.rollbackToSafeSequentialPrefix(targetFile)

        assertEquals(0L, targetFile.length())
        assertFalse(DownloadRangeStore.hasState(targetFile))
    }

    /** 测试无侧车的普通连续旧 .part 升级为已完成前缀 */
    @Test
    fun upgradeSequentialPartUpgradesExistingFileSafely() {
        val targetFile =
            tempFolder.newFile("legacy.part").apply {
                writeBytes("0123456789".toByteArray(Charsets.UTF_8))
            }

        assertFalse(DownloadRangeStore.hasState(targetFile))

        val upgraded =
            DownloadRangeStore.upgradeSequentialPart(
                targetFile = targetFile,
                resourceKey = "legacy-res",
                total = 50L,
                strongETag = "\"legacy-etag\"",
            )

        assertNotNull(upgraded)
        assertEquals(1, upgraded!!.ranges.size)
        assertEquals(0L, upgraded.ranges[0].start)
        assertEquals(9L, upgraded.ranges[0].end)
        assertEquals(10L, upgraded.completedBytes)
        assertTrue(DownloadRangeStore.hasState(targetFile))
    }

    /** 测试缺口计算与切块 */
    @Test
    fun computeMissingRangesAndSplitIntoBlocks() {
        // 总大小 100，已完成 [0, 19] 和 [50, 69]
        val sha = "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855"
        val ranges = listOf(RangeEntry(0L, 19L, sha), RangeEntry(50L, 69L, sha))

        val missing = DownloadRangeStore.computeMissingRanges(100L, ranges)
        assertEquals(2, missing.size)
        assertEquals(Pair(20L, 49L), missing[0])
        assertEquals(Pair(70L, 99L), missing[1])

        // 切块：缺口 [20, 49]（30字节）按 block=12 切块 -> [20, 31], [32, 43], [44, 49]
        val blocks = DownloadRangeStore.splitIntoBlocks(missing[0], 12L)
        assertEquals(3, blocks.size)
        assertEquals(Pair(20L, 31L), blocks[0])
        assertEquals(Pair(32L, 43L), blocks[1])
        assertEquals(Pair(44L, 49L), blocks[2])
    }

    /** 校验强 ETag 正则与验证规则 */
    @Test
    fun validateStrongETag() {
        assertTrue(DownloadRangeStore.isStrongETag("\"6adf1a78-9453e43\""))
        assertTrue(DownloadRangeStore.isStrongETag("\"\""))
        assertFalse(DownloadRangeStore.isStrongETag("W/\"6adf1a78-9453e43\""))
        assertFalse(DownloadRangeStore.isStrongETag("6adf1a78-9453e43"))
        assertFalse(DownloadRangeStore.isStrongETag(null))
        assertFalse(DownloadRangeStore.isStrongETag(""))
    }
}
