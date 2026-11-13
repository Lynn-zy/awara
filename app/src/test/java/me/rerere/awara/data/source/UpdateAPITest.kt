package me.rerere.awara.data.source

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * GitHub Releases 自动检查更新与语义化版本解析单元测试。
 */
class UpdateAPITest {
    /**
     * 验证语义化版本号按数字段比较，避免字典序下 `1.10.0 < 1.9.0` 的错误。
     */
    @Test
    fun isNewerSemanticVersionComparesNumericSegmentsCorrectly() {
        assertTrue(isNewerSemanticVersion(currentVersion = "1.3.0", candidateTag = "v1.3.1"))
        assertTrue(isNewerSemanticVersion(currentVersion = "1.9.0", candidateTag = "v1.10.0"))
        assertTrue(isNewerSemanticVersion(currentVersion = "1.3", candidateTag = "1.3.1"))
        assertFalse(isNewerSemanticVersion(currentVersion = "1.3.0", candidateTag = "v1.3.0"))
        assertFalse(isNewerSemanticVersion(currentVersion = "1.3.0", candidateTag = "v1.2.9"))
        assertFalse(isNewerSemanticVersion(currentVersion = "1.3.0", candidateTag = "invalid-tag"))
    }

    /**
     * 验证草稿与预发布版本不会触发更新提示。
     */
    @Test
    fun resolveAvailableUpdateIgnoresDraftAndPrerelease() {
        val draftRelease =
            GithubRelease(
                tagName = "v1.4.0",
                body = "Draft changelog",
                htmlUrl = "https://github.com/Lynn-zy/awara/releases/tag/v1.4.0",
                draft = true,
            )
        val preRelease =
            GithubRelease(
                tagName = "v1.4.0-rc1",
                body = "RC changelog",
                htmlUrl = "https://github.com/Lynn-zy/awara/releases/tag/v1.4.0-rc1",
                prerelease = true,
            )
        assertNull(resolveAvailableUpdate(currentVersion = "1.3.0", release = draftRelease))
        assertNull(resolveAvailableUpdate(currentVersion = "1.3.0", release = preRelease))
    }

    /**
     * 验证正式新版本解析出规范版本号、更新日志与受信任的仓库下载链接，异常链接回退至默认 Releases 页。
     */
    @Test
    fun resolveAvailableUpdateExtractsChangelogAndValidatesDownloadUrl() {
        val validRelease =
            GithubRelease(
                tagName = "1.3.1",
                name = "Release 1.3.1",
                body = "- 优化播放体验\n- 修复已知问题",
                htmlUrl = "https://github.com/Lynn-zy/awara/releases/tag/v1.3.1",
            )
        val resolved = resolveAvailableUpdate(currentVersion = "1.3.0", release = validRelease)
        assertNotNull(resolved)
        assertEquals("v1.3.1", resolved?.version)
        assertEquals("- 优化播放体验\n- 修复已知问题", resolved?.changes)
        assertEquals("https://github.com/Lynn-zy/awara/releases/tag/v1.3.1", resolved?.downloadUrl)

        val untrustedUrlRelease =
            validRelease.copy(
                body = "   ",
                htmlUrl = "https://evil.example.com/awara.apk",
            )
        val fallbackResolved =
            resolveAvailableUpdate(currentVersion = "1.3.0", release = untrustedUrlRelease)
        assertNotNull(fallbackResolved)
        assertEquals("Release 1.3.1", fallbackResolved?.changes)
        assertEquals(DEFAULT_GITHUB_RELEASES_URL, fallbackResolved?.downloadUrl)
    }
}
