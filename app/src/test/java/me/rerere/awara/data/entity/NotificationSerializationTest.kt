package me.rerere.awara.data.entity

import kotlinx.serialization.decodeFromString
import me.rerere.awara.data.source.Pager
import me.rerere.awara.util.JsonInstance
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class NotificationSerializationTest {
    @Test
    fun notificationWithNullVideoUserPreservesContentAndTarget() {
        val notifications =
            JsonInstance.decodeFromString<Pager<Notification>>(notificationResponse("null"))
        val notification = notifications.results.single()

        assertEquals("通知中的回复内容", notification.comment?.body)
        assertEquals("video-id", notification.video?.id)
        assertNull(notification.video?.user)
    }

    @Test
    fun notificationWithVideoUserPreservesAuthorAndTarget() {
        val videoUser =
            """
        {
          "id": "author-id",
          "username": "author",
          "name": "视频作者",
          "createdAt": "2026-10-11T00:00:00Z",
          "deletedAt": null,
          "followedBy": false,
          "following": false,
          "friend": false,
          "premium": false,
          "role": "user",
          "seenAt": null,
          "status": "active",
          "updatedAt": "2026-10-11T00:00:00Z",
          "avatar": null
        }
      """
                .trimIndent()
        val notifications =
            JsonInstance.decodeFromString<Pager<Notification>>(notificationResponse(videoUser))
        val notification = notifications.results.single()

        assertEquals("视频作者", notification.video?.user?.name)
        assertEquals("video-id", notification.video?.id)
        assertEquals("通知中的回复内容", notification.comment?.body)
    }

    @Test
    fun notificationWithNestedCommentParentPreservesParentHierarchy() {
        val json =
            """
            {
              "count": 1,
              "limit": 32,
              "page": 0,
              "results": [{
                "id": "notification-nested",
                "read": false,
                "type": "newReply",
                "createdAt": "2026-10-11T00:00:00Z",
                "updatedAt": "2026-10-11T00:00:00Z",
                "profile": null,
                "comment": {
                  "id": "child-reply-id",
                  "body": "二级回复正文",
                  "createdAt": "2026-10-11T00:00:00Z",
                  "updatedAt": "2026-10-11T00:00:00Z",
                  "numReplies": 0,
                  "user": null,
                  "parent": {
                    "id": "top-parent-id",
                    "body": "顶级楼层正文",
                    "createdAt": "2026-10-11T00:00:00Z",
                    "updatedAt": "2026-10-11T00:00:00Z",
                    "numReplies": 1,
                    "user": null,
                    "parent": null
                  }
                },
                "video": {
                  "id": "video-nested-id",
                  "title": "视频标题",
                  "liked": false,
                  "numComments": 2,
                  "numLikes": 0,
                  "numViews": 1,
                  "body": null,
                  "createdAt": "2026-10-11T00:00:00Z",
                  "updatedAt": "2026-10-11T00:00:00Z",
                  "deletedAt": null,
                  "slug": null,
                  "user": null,
                  "tags": [],
                  "private": false,
                  "rating": "general",
                  "status": "active",
                  "embedUrl": null,
                  "file": null,
                  "fileUrl": null,
                  "thumbnail": 0,
                  "customThumbnail": null,
                  "siteId": "iwara"
                }
              }]
            }
            """
                .trimIndent()
        val notifications = JsonInstance.decodeFromString<Pager<Notification>>(json)
        val notification = notifications.results.single()

        assertEquals("child-reply-id", notification.comment?.id)
        assertEquals("top-parent-id", notification.comment?.parent?.id)
        assertEquals("video-nested-id", notification.video?.id)
    }

    // 根据报错截图重建脱敏响应，不调用生产接口。
    private fun notificationResponse(videoUser: String): String =
        """
            {
              "count": 1,
              "limit": 32,
              "page": 0,
              "results": [{
                "id": "notification-id",
                "read": false,
                "type": "newReply",
                "createdAt": "2026-10-11T00:00:00Z",
                "updatedAt": "2026-10-11T00:00:00Z",
                "profile": null,
                "comment": {
                  "id": "comment-id",
                  "body": "通知中的回复内容",
                  "createdAt": "2026-10-11T00:00:00Z",
                  "updatedAt": "2026-10-11T00:00:00Z",
                  "numReplies": 0,
                  "user": null,
                  "parent": null
                },
                "video": {
                  "id": "video-id",
                  "title": "通知关联的视频",
                  "liked": false,
                  "numComments": 1,
                  "numLikes": 0,
                  "numViews": 1,
                  "body": null,
                  "createdAt": "2026-10-11T00:00:00Z",
                  "updatedAt": "2026-10-11T00:00:00Z",
                  "deletedAt": null,
                  "slug": null,
                  "user": $videoUser,
                  "tags": [],
                  "private": false,
                  "rating": "general",
                  "status": "active",
                  "embedUrl": null,
                  "file": null,
                  "fileUrl": null,
                  "thumbnail": 0,
                  "customThumbnail": null,
                  "siteId": "iwara"
                }
              }]
            }
        """
            .trimIndent()
}
