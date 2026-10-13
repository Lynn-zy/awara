package me.rerere.awara.data.download

import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DownloadStorageTest {
    @Test
    fun buildDownloadFileNameRemovesTraversalAndControlChars() {
        val fileName =
            buildDownloadFileName(
                title = "../..\u0000\u001F\u007Fevil/path\\name:*?\"<>|test",
                resourceId = "res../123",
            )

        assertTrue(fileName.endsWith(".mp4"))
        assertFalse(fileName.contains(".."))
        assertFalse(fileName.contains("/"))
        assertFalse(fileName.contains("\\"))
        assertFalse(fileName.any { it.code in 0..0x1F || it.code == 0x7F })
        assertTrue(fileName.contains("evil_path_name"))
        assertTrue(fileName.contains("res_123"))
    }

    @Test
    fun buildDownloadFileNameGeneratesUniqueNamesAndBoundsLength() {
        val longTitle = "超长视频标题测试".repeat(30)
        val first = buildDownloadFileName(longTitle, "video123")
        val second = buildDownloadFileName(longTitle, "video123")

        assertNotEquals(first, second)
        assertTrue(first.endsWith(".mp4"))
        assertTrue(first.toByteArray(Charsets.UTF_8).size <= 200)
    }

    @Test
    fun buildDownloadFileNameFallsBackWhenTitleOrIdIsBlank() {
        val fileName = buildDownloadFileName(" ... \n\t ", "")

        assertTrue(fileName.startsWith("video_unknown_"))
        assertTrue(fileName.endsWith(".mp4"))
    }

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
