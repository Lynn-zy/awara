package me.rerere.awara.data.user

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import me.rerere.awara.data.entity.User
import me.rerere.awara.util.JsonInstance

/** 本机保存的用户快照，仅用于名单展示和主页导航，不保存账号凭据。 */
@Serializable data class BlockedUser(val user: User, val blockedAt: Long)

/** 本地拉黑与远端取关分别报告结果，不能将网络失败伪装成完全成功。 */
sealed interface BlockUserResult {
    data object Blocked : BlockUserResult

    data class UnfollowFailed(val cause: Exception) : BlockUserResult
}

/**
 * 本机所有账号共用的名单；退出登录不清除，主页及播放器不会在此处被限制访问。
 *
 * @param readText 读取已有 JSON；空字符串表示尚未保存过名单
 * @param writeText 持久化完整快照；失败必须抛出异常，不能发布未保存状态
 * @param now 提供首次拉黑时间，便于稳定排序和确定性测试
 */
class UserBlocklist(
    private val readText: () -> String,
    private val writeText: (String) -> Unit,
    private val now: () -> Long = System::currentTimeMillis,
) {
    private val mutableEntries = MutableStateFlow(load())
    val entries: StateFlow<List<BlockedUser>> = mutableEntries.asStateFlow()
    val blockedIds: Set<String>
        get() = entries.value.mapTo(mutableSetOf()) { it.user.id }

    /** 作者缺失时不猜测身份，只有非空用户 ID 精确相同才匹配。 */
    fun isBlocked(userId: String?): Boolean =
        !userId.isNullOrBlank() && entries.value.any { it.user.id == userId }

    /** 重复拉黑只更新展示资料，保留原始时间，不生成重复条目。 */
    @Synchronized
    fun block(user: User) {
        require(user.id.isNotBlank())
        val previous = mutableEntries.value
        val existing = previous.firstOrNull { it.user.id == user.id }
        val entry = BlockedUser(user, existing?.blockedAt ?: now())
        val updated =
            if (existing == null) listOf(entry) + previous
            else previous.map { if (it.user.id == user.id) entry else it }
        save(updated)
    }

    /** 解除本地拉黑不执行关注请求，也不修改历史或已下载文件。 */
    @Synchronized
    fun unblock(userId: String) {
        val updated = mutableEntries.value.filterNot { it.user.id == userId }
        if (updated.size != mutableEntries.value.size) save(updated)
    }

    /** 本地状态先落盘，取关异常保留名单；协程取消仍交还调用方处理。 */
    suspend fun blockAndUnfollow(user: User, unfollow: suspend (String) -> Unit): BlockUserResult {
        block(user)
        if (!user.following) return BlockUserResult.Blocked
        return try {
            unfollow(user.id)
            BlockUserResult.Blocked
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            BlockUserResult.UnfollowFailed(error)
        }
    }

    /** 先保存再通知，存储异常时保持旧快照，让调用方显示失败并允许重试。 */
    private fun save(updated: List<BlockedUser>) {
        writeText(JsonInstance.encodeToString(updated))
        mutableEntries.value = updated
    }

    /** 损坏快照不覆盖原文；下次正常操作才写入新名单。 */
    private fun load(): List<BlockedUser> {
        val stored = readText()
        if (stored.isBlank()) return emptyList()
        return try {
            JsonInstance.decodeFromString<List<BlockedUser>>(stored)
                .filter { it.user.id.isNotBlank() }
                .distinctBy { it.user.id }
        } catch (_: SerializationException) {
            emptyList()
        } catch (_: IllegalArgumentException) {
            emptyList()
        }
    }
}

/** 在线作品列表统一按作者过滤；调用方保留原始分页与计数，不在主页或本地列表使用。 */
fun <Item> filterBlockedWorks(
    items: List<Item>,
    blockedIds: Set<String>,
    authorId: (Item) -> String?,
): List<Item> =
    if (blockedIds.isEmpty()) items
    else items.filter { item -> authorId(item).let { it.isNullOrBlank() || it !in blockedIds } }
