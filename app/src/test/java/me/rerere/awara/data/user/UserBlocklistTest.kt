package me.rerere.awara.data.user

import java.io.IOException
import java.time.Instant
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import me.rerere.awara.data.entity.User
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/** 使用实际 JSON 往返验证本机名单，不连接服务器或依赖 Android 存储。 */
class UserBlocklistTest {
    @Test
    fun blockingPersistsAcrossRepositoryRecreationAndKeepsOneEntryPerUser() {
        var stored = ""
        val blocklist = UserBlocklist({ stored }, { stored = it }, now = { 100L })
        val user = user("author")
        blocklist.block(user)
        blocklist.block(user.copy(name = "updated"))

        val restored = UserBlocklist({ stored }, { stored = it })
        assertTrue(restored.isBlocked("author"))
        assertFalse(restored.isBlocked(null))
        assertFalse(restored.isBlocked(""))
        assertEquals(1, restored.entries.value.size)
        assertEquals("updated", restored.entries.value.single().user.name)
        assertEquals(100L, restored.entries.value.single().blockedAt)
        restored.unblock("author")
        assertFalse(UserBlocklist({ stored }, { stored = it }).isBlocked("author"))
    }

    @Test
    fun followingUserIsBlockedBeforeUnfollowAndFailureDoesNotUndoLocalChange() =
        runBlocking<Unit> {
            var stored = ""
            val blocklist = UserBlocklist({ stored }, { stored = it })
            val failure = IOException("offline")
            val result =
                blocklist.blockAndUnfollow(user("author", following = true)) { id ->
                    assertEquals("author", id)
                    assertTrue(blocklist.isBlocked(id))
                    assertTrue(UserBlocklist({ stored }, { stored = it }).isBlocked(id))
                    throw failure
                }
            assertSame(failure, (result as BlockUserResult.UnfollowFailed).cause)
            assertTrue(blocklist.isBlocked("author"))
            var attempts = 0
            val retried =
                blocklist.blockAndUnfollow(user("author", following = true)) { attempts++ }
            assertEquals(BlockUserResult.Blocked, retried)
            assertEquals(1, attempts)
            assertEquals(1, blocklist.entries.value.size)
        }

    @Test
    fun notFollowingUserAndUnblockingDoNotSendFollowRequests() =
        runBlocking<Unit> {
            var stored = ""
            val blocklist = UserBlocklist({ stored }, { stored = it })
            val result =
                blocklist.blockAndUnfollow(user("author")) { error("must not contact server") }
            assertEquals(BlockUserResult.Blocked, result)
            blocklist.unblock("author")
            assertFalse(blocklist.isBlocked("author"))
        }

    @Test
    fun cancellationPropagatesButAlreadyPersistedLocalBlockRemains() {
        var stored = ""
        val blocklist = UserBlocklist({ stored }, { stored = it })
        assertThrows(CancellationException::class.java) {
            runBlocking {
                blocklist.blockAndUnfollow(user("author", following = true)) {
                    throw CancellationException("cancelled")
                }
            }
        }
        assertTrue(UserBlocklist({ stored }, { stored = it }).isBlocked("author"))
    }

    @Test
    fun failedPersistenceDoesNotPublishAnUnsavedBlock() {
        val blocklist = UserBlocklist({ "" }, { throw IOException("storage full") })
        assertThrows(IOException::class.java) { blocklist.block(user("author")) }
        assertTrue(blocklist.entries.value.isEmpty())
        assertThrows(IllegalArgumentException::class.java) { blocklist.block(user(" ")) }
    }

    @Test
    fun corruptedSnapshotIsNotRewrittenDuringLoading() {
        var writes = 0
        val blocklist = UserBlocklist({ "{" }, { writes++ })
        assertTrue(blocklist.entries.value.isEmpty())
        assertEquals(0, writes)
    }

    @Test
    fun onlineFilteringUsesExactAuthorIdsAndKeepsUnknownAuthors() {
        val works = listOf("one" to "author", "two" to "other", "three" to null, "four" to "AUTHOR")
        assertEquals(
            listOf("two", "three", "four"),
            filterBlockedWorks(works, setOf("author")) { it.second }.map { it.first },
        )
        assertSame(works, filterBlockedWorks(works, emptySet()) { it.second })
        assertEquals(4, works.size)
    }

    /** 构造无凭据的用户快照，覆盖昵称与主页标识的本地恢复。 */
    private fun user(id: String, following: Boolean = false) =
        User(
            id = id,
            username = "name_$id",
            name = id,
            createdAt = Instant.EPOCH,
            updatedAt = Instant.EPOCH,
            deletedAt = null,
            followedBy = false,
            following = following,
            friend = false,
            premium = false,
            role = "user",
            seenAt = null,
            avatar = null,
        )
}
