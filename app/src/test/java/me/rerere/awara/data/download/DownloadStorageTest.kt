package me.rerere.awara.data.download

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * DownloadStorage 纯 JVM 单元测试套件。
 *
 * 验证与官网一致的确定性文件命名格式（`Iwara - 视频标题 [视频页面ID] [画质].mp4`）、非法字符净化、
 * 路径穿越防护、Unicode 与 Emoji 代理对截断安全性、回退机制以及受限系统目录检测。
 */
class DownloadStorageTest {
    /** 验证输出严格匹配官网命名格式（原画为 `[Source]`，转码档位如 `[360]`、`[540]`）。 */
    @Test
    fun buildDownloadFileNameMatchesOfficialFormat() {
        val official360 =
            buildDownloadFileName(
                title = "16.5.【Wuthering Waves 鳴潮】STRESS 愛彌斯",
                resourceId = "ox1pVQB5CC2Vxw",
                quality = "360",
            )
        assertEquals(
            "Iwara - 16.5.【Wuthering Waves 鳴潮】STRESS 愛彌斯 [ox1pVQB5CC2Vxw] [360].mp4",
            official360,
        )

        val officialSource =
            buildDownloadFileName(
                title = "16.5.【Wuthering Waves 鳴潮】STRESS 愛彌斯",
                resourceId = "ox1pVQB5CC2Vxw",
                quality = "source",
            )
        assertEquals(
            "Iwara - 16.5.【Wuthering Waves 鳴潮】STRESS 愛彌斯 [ox1pVQB5CC2Vxw] [Source].mp4",
            officialSource,
        )

        val withPSuffix =
            buildDownloadFileName(
                title = "Iwara Video",
                resourceId = "abc123XYZ",
                quality = "540p",
            )
        assertEquals("Iwara - Iwara Video [abc123XYZ] [540].mp4", withPSuffix)
    }

    /** 验证相同输入多次生成完全一致的文件名，不包含随机 UUID。 */
    @Test
    fun buildDownloadFileNameIsDeterministicForSameInput() {
        val title = "确定性命名测试视频"
        val resourceId = "det123456"

        val first = buildDownloadFileName(title, resourceId, "Source")
        val second = buildDownloadFileName(title, resourceId, "source")

        assertEquals(first, second)
        assertEquals("Iwara - 确定性命名测试视频 [det123456] [Source].mp4", first)
    }

    /** 验证非法字符净化与路径穿越符号过滤，防止系统保留符号及控制字符注入。 */
    @Test
    fun buildDownloadFileNameRemovesTraversalAndControlChars() {
        val fileName =
            buildDownloadFileName(
                title = "../..\u0000\u001F\u007Fevil/path\\name:*?\"<>|test",
                resourceId = "res../[123]",
                quality = "720P",
            )

        assertTrue(fileName.endsWith(".mp4"))
        assertFalse("文件名不得包含路径遍历序列 ..", fileName.contains(".."))
        assertFalse("文件名不得包含正斜杠 /", fileName.contains("/"))
        assertFalse("文件名不得包含反斜杠 \\", fileName.contains("\\"))
        assertFalse("文件名不得包含控制字符", fileName.any { it.code in 0..0x1F || it.code == 0x7F })
        assertEquals("Iwara - evil_path_name_test [res_123] [720].mp4", fileName)
    }

    /** 验证超长中文标题的 UTF-8 字节截断以及 Unicode 代理对（Surrogate Pair）安全。 */
    @Test
    fun buildDownloadFileNameTruncatesLongChineseAndSurrogatePairsSafely() {
        val longChineseTitle = "测试超长视频标题".repeat(10)
        val result = buildDownloadFileName(longChineseTitle, "vid123", "source")

        assertTrue(result.startsWith("Iwara - "))
        assertTrue(result.endsWith(" [vid123] [Source].mp4"))
        val titlePart = result.removePrefix("Iwara - ").substringBeforeLast(" [vid123] [Source].mp4")
        val titleUtf8Bytes = titlePart.toByteArray(Charsets.UTF_8).size
        assertTrue("标题部分 UTF-8 字节数不得超过 120 字节", titleUtf8Bytes <= 120)

        val titleWithEmojiAtBoundary = "测".repeat(39) + "\uD83D\uDE00"
        val safeResult = buildDownloadFileName(titleWithEmojiAtBoundary, "emojiSafe", "360")

        assertTrue(safeResult.endsWith(" [emojiSafe] [360].mp4"))
        val safeTitlePart =
            safeResult.removePrefix("Iwara - ").substringBeforeLast(" [emojiSafe] [360].mp4")
        val safeTitleBytes = safeTitlePart.toByteArray(Charsets.UTF_8).size
        assertTrue("安全截断后字节数不得超限", safeTitleBytes <= 120)

        for (i in safeTitlePart.indices) {
            val ch = safeTitlePart[i]
            if (ch.isHighSurrogate()) {
                assertTrue(
                    "高代理必须紧随低代理",
                    i + 1 < safeTitlePart.length && safeTitlePart[i + 1].isLowSurrogate(),
                )
            }
            if (ch.isLowSurrogate()) {
                assertTrue("低代理前必须为高代理", i > 0 && safeTitlePart[i - 1].isHighSurrogate())
            }
        }
    }

    /** 验证标题、资源 ID 或画质为空白、纯控制字符时的回退逻辑。 */
    @Test
    fun buildDownloadFileNameFallsBackWhenInputsAreBlank() {
        assertEquals(
            "Iwara - video [unknown] [unknown].mp4",
            buildDownloadFileName(" ... \n\t ", "", ""),
        )
        assertEquals(
            "Iwara - video [nqvHGnneN5OLbq] [Source].mp4",
            buildDownloadFileName("   ", "nqvHGnneN5OLbq", "source"),
        )
        assertEquals(
            "Iwara - 有效标题 [unknown] [540].mp4",
            buildDownloadFileName("有效标题", "   ", "540"),
        )
    }

    /** 验证受限 Android 目录检测机制。 */
    @Test
    fun isRestrictedAndroidPathBlocksDataAndObbOnly() {
        assertTrue(isRestrictedAndroidPath("primary:Android/data"))
        assertTrue(isRestrictedAndroidPath("primary:Android/data/me.rerere.awara"))
        assertTrue(isRestrictedAndroidPath("1234-5678:android/obb/com.example"))
        assertTrue(
            isRestrictedAndroidPath(
                "content://com.android.externalstorage.documents/tree/primary%3AAndroid%2Fdata"
            )
        )
        assertFalse(isRestrictedAndroidPath("primary:Download/Awara"))
        assertFalse(isRestrictedAndroidPath("primary:Movies/Awara"))
    }
}
