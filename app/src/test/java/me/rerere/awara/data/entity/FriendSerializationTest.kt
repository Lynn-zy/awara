package me.rerere.awara.data.entity

import kotlinx.serialization.decodeFromString
import me.rerere.awara.data.dto.FriendRequestDto
import me.rerere.awara.data.source.Pager
import me.rerere.awara.util.JsonInstance
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class FriendSerializationTest {
    @Test
    fun friendsWithNullNamesKeepTheWholePageAndStableIds() {
        val pager =
            JsonInstance.decodeFromString<Pager<User>>(page(user("deleted-id", "null", "null")))
        assertEquals(1, pager.count)
        assertEquals(32, pager.limit)
        assertEquals("deleted-id", pager.results.single().id)
        assertNull(pager.results.single().name)
        assertNull(pager.results.single().username)
        assertNull(pager.results.single().profileIdentifier)
    }

    @Test
    fun requestsWithNullTargetNamesKeepCleanupIdentity() {
        val request =
            """{"id":"request-id","createdAt":"2026-10-16T00:00:00Z","user":${user("self-id", "\"self\"", "\"自己\"")},"target":${user("deleted-target", "null", "null")}}"""
        val pager = JsonInstance.decodeFromString<Pager<FriendRequestDto>>(page(request))
        assertEquals("deleted-target", pager.results.single().target.id)
        assertNull(pager.results.single().target.name)
        assertNull(pager.results.single().target.username)
        assertEquals("自己", pager.results.single().user.name)
        assertNull(pager.results.single().target.profileIdentifier)
    }

    @Test
    fun requestsWithNullSenderNamesKeepBothParticipants() {
        val request =
            """{"id":"request-id","createdAt":"2026-10-16T00:00:00Z","user":${user("deleted-sender", "null", "null")},"target":${user("self-id", "\"self\"", "\"自己\"")}}"""
        val pager = JsonInstance.decodeFromString<Pager<FriendRequestDto>>(page(request))
        assertEquals("deleted-sender", pager.results.single().user.id)
        assertNull(pager.results.single().user.name)
        assertEquals("self-id", pager.results.single().target.id)
    }

    private fun page(result: String) = """{"count":1,"limit":32,"page":0,"results":[$result]}"""

    @Test
    fun nullableStatusDoesNotInvalidateUserIdentity() {
        val raw =
            user("retained-id", "\"retained\"", "\"保留姓名\"")
                .replace("\"status\":\"disabled\"", "\"status\":null")
                .replace("\"deletedAt\":\"2026-10-16T00:00:00Z\"", "\"deletedAt\":null")
        val value = JsonInstance.decodeFromString<User>(raw)
        assertNull(value.status)
        assertEquals("保留姓名", value.name)
        assertEquals("retained", value.profileIdentifier)
    }

    private fun user(id: String, username: String, name: String) =
        """
        {
          "id":"$id","name":$name,"username":$username,"status":"disabled",
          "createdAt":"2023-01-01T00:00:00Z","updatedAt":"2026-10-16T00:00:00Z",
          "deletedAt":"2026-10-16T00:00:00Z","seenAt":null,"avatar":null,
          "followedBy":false,"following":false,"friend":false,"premium":false,"role":"user"
        }
    """
            .trimIndent()
}
