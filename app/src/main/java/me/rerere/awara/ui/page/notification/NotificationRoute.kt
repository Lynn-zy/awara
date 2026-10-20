package me.rerere.awara.ui.page.notification

import java.net.URLEncoder
import me.rerere.awara.data.entity.Notification

/** 只合并成功确认的通知，不能将批量操作之后的新消息也自动视为已读。 */
internal fun mergeNotificationReadState(
    notifications: List<Notification>,
    confirmedReadIds: Set<String>,
): List<Notification> =
    notifications.map { notification ->
        if (!notification.read && notification.id in confirmedReadIds)
            notification.copy(read = true)
        else notification
    }

/**
 * 安全的 URL 参数编码纯函数。 使用 Java 标准库避免在 JVM 纯单元测试环境中依赖 Android SDK stub。
 *
 * @param value 待编码的原始字符串
 * @return UTF-8 URL 编码后的字符串
 */
internal fun safeUrlEncode(value: String): String =
    URLEncoder.encode(value, "UTF-8").replace("+", "%20")

/**
 * 根据通知类型和关联对象计算导航目标路由。
 * - 当通知关联有效视频时，普通跳转到视频详情，不自动查找评论。
 * - 当通知类型为 newComment 且无视频时：跳转用户个人主页 `user/{profileIdentifier}`；
 * - 其它情况无明确有效目标，返回 null 避免无效跳转。
 *
 * @param notification 通知实体对象
 * @return 目标路由字符串，若无合法目标则返回 null
 */
fun notificationDestination(notification: Notification): String? {
    val videoId = notification.video?.id?.takeIf { it.isNotBlank() }
    if (videoId != null) {
        return "video/${safeUrlEncode(videoId)}"
    }
    return when (notification.type) {
        "newComment" ->
            notification.profile
                ?.profileIdentifier
                ?.takeIf { it.isNotBlank() }
                ?.let { "user/${safeUrlEncode(it)}" }
        else -> null
    }
}
