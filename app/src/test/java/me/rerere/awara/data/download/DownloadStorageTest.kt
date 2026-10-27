package me.rerere.awara.data.download

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * DownloadStorage 纯 JVM 单元测试套件。
 *
 * 验证确定性文件命名格式（“视频标题_视频页面ID.mp4”）、非法字符净化、 路径穿越防护、Unicode 与 Emoji 代理对截断安全性、回退机制以及受限系统目录检测。
 */
class DownloadStorageTest {
    /** 验证输出符合“视频标题_视频页面ID.mp4”的确切固定字符串格式， 确认不再包含随机 UUID 尾巴。 */
    @Test
    fun buildDownloadFileNameExactExpectedString() {
        // 案例 1：用户标准示例，“标题”与“nqvHGnneN5OLbq”
        val result1 = buildDownloadFileName(title = "标题", resourceId = "nqvHGnneN5OLbq")
        assertEquals("标题_nqvHGnneN5OLbq.mp4", result1)

        // 案例 2：包含英文空格与标准页面 ID
        val result2 = buildDownloadFileName(title = "Iwara Video", resourceId = "abc123XYZ")
        assertEquals("Iwara Video_abc123XYZ.mp4", result2)
    }

    /** 验证相同输入时具有完全的确定性（取代原有非等断言与随机 UUID 验证）。 多次执行必须生成完全一致的文件名，消除因随机数导致的不确定性。 */
    @Test
    fun buildDownloadFileNameIsDeterministicForSameInput() {
        val title = "确定性命名测试视频"
        val resourceId = "det123456"

        val first = buildDownloadFileName(title, resourceId)
        val second = buildDownloadFileName(title, resourceId)

        // 相同输入必须完全相等
        assertEquals(first, second)
        assertEquals("确定性命名测试视频_det123456.mp4", first)
        // 确保文件名中不包含 36 字符的标准 UUID 格式尾巴
        val uuidRegex =
            Regex(
                "_[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}\\.mp4$"
            )
        assertFalse("文件名不应包含随机 UUID 尾巴", uuidRegex.containsMatchIn(first))
    }

    /** 验证非法字符净化与路径穿越符号过滤。 防止路径遍历攻击、系统保留符号及控制字符注入。 */
    @Test
    fun buildDownloadFileNameRemovesTraversalAndControlChars() {
        val fileName =
            buildDownloadFileName(
                title = "../..\u0000\u001F\u007Fevil/path\\name:*?\"<>|test",
                resourceId = "res../123",
            )

        assertTrue(fileName.endsWith(".mp4"))
        assertFalse("文件名不得包含路径遍历序列 ..", fileName.contains(".."))
        assertFalse("文件名不得包含正斜杠 /", fileName.contains("/"))
        assertFalse("文件名不得包含反斜杠 \\", fileName.contains("\\"))
        assertFalse("文件名不得包含控制字符", fileName.any { it.code in 0..0x1F || it.code == 0x7F })
        assertTrue("应安全保留合法文本片段", fileName.contains("evil_path_name"))
        assertTrue("资源 ID 路径穿越符号应被安全规整", fileName.contains("res_123"))
        assertEquals("evil_path_name_test_res_123.mp4", fileName)
    }

    /**
     * 验证超长中文标题的 UTF-8 字节截断以及 Unicode 代理对（Surrogate Pair）安全。 保证单文件名符合文件系统 120 字节限制，且在截断边缘不产生损坏的半个
     * Emoji/代理对。
     */
    @Test
    fun buildDownloadFileNameTruncatesLongChineseAndSurrogatePairsSafely() {
        // 每个中文字符占 3 字节 UTF-8，40 个汉字即达到 120 字节上限
        val longChineseTitle = "测试超长视频标题".repeat(10) // 80 汉字 = 240 字节
        val result = buildDownloadFileName(longChineseTitle, "vid123")

        assertTrue(result.endsWith("_vid123.mp4"))
        val titlePart = result.substringBeforeLast("_vid123.mp4")
        val titleUtf8Bytes = titlePart.toByteArray(Charsets.UTF_8).size
        assertTrue("标题部分 UTF-8 字节数不得超过 120 字节", titleUtf8Bytes <= 120)

        // 测试代理对截断安全性：构造 39 个汉字（117 字节）+ 1 个 4 字节 Emoji（U+1F600，高低代理对 \uD83D\uDE00）
        // 117 + 4 = 121 字节 > 120 字节上限，算法必须安全放弃整个 Emoji，而绝不能只保留高代理项（造成乱码或编码异常）
        val titleWithEmojiAtBoundary = "测".repeat(39) + "\uD83D\uDE00"
        val safeResult = buildDownloadFileName(titleWithEmojiAtBoundary, "emojiSafe")

        assertTrue(safeResult.endsWith("_emojiSafe.mp4"))
        val safeTitlePart = safeResult.substringBeforeLast("_emojiSafe.mp4")
        val safeTitleBytes = safeTitlePart.toByteArray(Charsets.UTF_8).size
        assertTrue("安全截断后字节数不得超限", safeTitleBytes <= 120)

        // 验证没有孤立的高低代理字符（孤立代理在有效 UTF-8 中属于畸形字符）
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

    /** 验证标题或资源 ID 为空白、纯控制字符时的回退逻辑。 确保无论如何输入均输出合法的默认文件名。 */
    @Test
    fun buildDownloadFileNameFallsBackWhenTitleOrIdIsBlank() {
        // 标题为纯点号、空格和控制字符，ID 为空
        val result1 = buildDownloadFileName(" ... \n\t ", "")
        assertEquals("video_unknown.mp4", result1)

        // 两者完全为空
        val result2 = buildDownloadFileName("", "")
        assertEquals("video_unknown.mp4", result2)

        // 仅 ID 有效，标题为空
        val result3 = buildDownloadFileName("   ", "nqvHGnneN5OLbq")
        assertEquals("video_nqvHGnneN5OLbq.mp4", result3)

        // 仅标题有效，ID 为纯空格
        val result4 = buildDownloadFileName("有效标题", "   ")
        assertEquals("有效标题_unknown.mp4", result4)
    }

    /** 验证受限 Android 目录检测机制。 严禁将 Android/data 或 Android/obb 目录设为下载目标，防止访问被系统沙盒拒绝。 */
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
