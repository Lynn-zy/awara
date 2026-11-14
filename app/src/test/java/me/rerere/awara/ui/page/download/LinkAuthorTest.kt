package me.rerere.awara.ui.page.download

import java.io.IOException
import java.time.Instant
import kotlinx.coroutines.runBlocking
import me.rerere.awara.data.dto.ProfileDto
import me.rerere.awara.data.entity.DownloadItem
import me.rerere.awara.data.entity.DownloadType
import me.rerere.awara.data.entity.File
import me.rerere.awara.data.entity.User
import me.rerere.awara.data.entity.canLinkAuthor
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 验证未知作者与手动关联作者的权限判定、`@xxx` 输入净化及已销号作者默认头像回退逻辑。
 */
class LinkAuthorTest {

    @Test
    fun canLinkAuthorOnlyAllowsUnknownOrManuallyLinkedItems() {
        val unknownItem =
            DownloadItem(
                id = 1,
                title = "未知作者视频",
                thumbnail = "",
                uri = "content://1",
                type = DownloadType.VIDEO,
                resourceId = "res1",
                time = Instant.EPOCH,
                authorId = "",
                manualAuthor = false,
            )
        val officialAuthorItem =
            unknownItem.copy(id = 2, authorId = "official_user", manualAuthor = false)
        val manualLinkedItem =
            unknownItem.copy(id = 3, authorId = "linked_user", manualAuthor = true)

        assertTrue(unknownItem.canLinkAuthor)
        assertFalse(officialAuthorItem.canLinkAuthor)
        assertTrue(manualLinkedItem.canLinkAuthor)
    }

    @Test
    fun normalizeAuthorHandleExtractsUsernameAndRejectsInvalidInput() {
        assertEquals("mmd_creator", normalizeAuthorHandle("@mmd_creator"))
        assertEquals("mmd_creator", normalizeAuthorHandle("  @@mmd_creator  "))
        assertEquals(
            "mmd_creator",
            normalizeAuthorHandle("https://www.iwara.tv/profile/mmd_creator/videos"),
        )
        assertEquals("user.test", normalizeAuthorHandle("https://iwara.tv/user/user.test"))

        assertNull(normalizeAuthorHandle(""))
        assertNull(normalizeAuthorHandle("   @@@   "))
        assertNull(normalizeAuthorHandle("bad handle with space"))
        assertNull(normalizeAuthorHandle("bad/slash"))
    }

    @Test
    fun resolveManualAuthorUsesOnlineProfileOrFallsBackToDefaultAvatarWhenDeleted() = runBlocking {
        val now = Instant.ofEpochSecond(1700000000L)

        // 1. 存活作者：使用在线昵称与真实头像
        val activeResolved =
            resolveManualAuthorByInput(
                rawHandle = "@active_author",
                fetchProfile = { username ->
                    ProfileDto(
                        user =
                            User(
                                id = "u1",
                                username = username,
                                name = "在线真实昵称",
                                createdAt = now,
                                deletedAt = null,
                                followedBy = false,
                                following = false,
                                friend = false,
                                premium = false,
                                role = "user",
                                seenAt = null,
                                updatedAt = now,
                                avatar =
                                    File(
                                        id = "av99",
                                        mime = "image/jpeg",
                                        name = "me.jpg",
                                        path = "",
                                        type = "image",
                                    ),
                            ),
                        header = null,
                        updatedAt = now,
                        body = null,
                    )
                },
            )
        assertTrue(activeResolved.resolvedOnline)
        assertEquals("active_author", activeResolved.authorId)
        assertEquals("在线真实昵称", activeResolved.authorName)
        assertEquals("https://i.iwara.tv/image/avatar/av99/me.jpg", activeResolved.authorAvatar)

        // 2. 已销号作者（HTTP 404）：降级采用输入 ID 与 Iwara 官方默认头像
        val deletedResolved =
            resolveManualAuthorByInput(
                rawHandle = "@gone_creator",
                fetchProfile = { throw IOException("HTTP 404 Not Found") },
            )
        assertFalse(deletedResolved.resolvedOnline)
        assertEquals("gone_creator", deletedResolved.authorId)
        assertEquals("gone_creator", deletedResolved.authorName)
        assertEquals("https://iwara.tv/images/default-avatar.jpg", deletedResolved.authorAvatar)
    }
}
