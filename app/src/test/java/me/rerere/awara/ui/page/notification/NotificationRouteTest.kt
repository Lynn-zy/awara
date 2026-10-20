package me.rerere.awara.ui.page.notification

import java.time.Instant
import me.rerere.awara.data.entity.Comment
import me.rerere.awara.data.entity.Notification
import me.rerere.awara.data.entity.User
import me.rerere.awara.data.entity.Video
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 通知仅跳转资源详情，不再携带评论定位参数。 */
class NotificationRouteTest {

    private val testInstant = Instant.parse("2026-10-19T00:00:00Z")

    private fun createComment(id: String, body: String = "评论正文", parent: Comment? = null): Comment =
        Comment(
            id = id,
            body = body,
            createdAt = testInstant,
            updatedAt = testInstant,
            numReplies = 0,
            user = null,
            parent = parent,
        )

    private fun createNotification(
        id: String = "notif-1",
        type: String = "newReply",
        read: Boolean = false,
        comment: Comment? = null,
        video: Video? = null,
        profile: User? = null,
    ): Notification =
        Notification(
            id = id,
            type = type,
            read = read,
            createdAt = testInstant,
            updatedAt = testInstant,
            comment = comment,
            video = video,
            profile = profile,
        )

    private fun createVideo(id: String = "vid-123"): Video =
        Video(
            id = id,
            title = "测试视频",
            liked = false,
            numComments = 1,
            numLikes = 0,
            numViews = 1,
            body = null,
            createdAt = testInstant,
            updatedAt = testInstant,
            deletedAt = null,
            slug = null,
            user = null,
            tags = emptyList(),
            private = false,
            rating = "general",
            status = "active",
            embedUrl = null,
            file = null,
            fileUrl = null,
            thumbnail = 0,
        )

    private fun createUser(id: String = "user-1", username: String = "tester"): User =
        User(
            id = id,
            username = username,
            name = "测试用户",
            createdAt = testInstant,
            deletedAt = null,
            followedBy = false,
            following = false,
            friend = false,
            premium = false,
            role = "user",
            seenAt = null,
            status = "active",
            updatedAt = testInstant,
            avatar = null,
        )

    @Test
    fun readConfirmationPreservesNewUnreadNotifications() {
        val results =
            mergeNotificationReadState(
                listOf(createNotification(id = "confirmed"), createNotification(id = "new")),
                setOf("confirmed"),
            )
        assertTrue(results[0].read)
        assertFalse(results[1].read)
    }

    @Test
    fun replyNotificationOpensVideoWithoutCommentAnchor() {
        val parent = createComment(id = "root-floor-id")
        val reply = createComment(id = "reply-id", parent = parent)
        val notif =
            createNotification(type = "newReply", video = createVideo("vid-abc"), comment = reply)
        assertEquals("video/vid-abc", notificationDestination(notif))
    }

    @Test
    fun commentNotificationOpensVideoWithoutCommentAnchor() {
        val comment = createComment(id = "top-level-comment-id")
        val notif =
            createNotification(
                type = "newComment",
                video = createVideo("vid-xyz"),
                comment = comment,
            )
        assertEquals("video/vid-xyz", notificationDestination(notif))
    }

    @Test
    fun notificationDestinationWithVideoWithoutCommentResolvesToVideoOnly() {
        val notif =
            createNotification(type = "newReply", video = createVideo("vid-alone"), comment = null)
        assertEquals("video/vid-alone", notificationDestination(notif))
    }

    @Test
    fun notificationDestinationForNewCommentWithoutVideoResolvesToUser() {
        val user = createUser(id = "user-id", username = "someone")
        val notif = createNotification(type = "newComment", video = null, profile = user)
        assertEquals("user/someone", notificationDestination(notif))
    }

    @Test
    fun notificationDestinationReturnsNullForUnknownOrIncompleteNotifications() {
        val notif = createNotification(type = "unknownType", video = null, profile = null)
        assertNull(notificationDestination(notif))
    }
}
