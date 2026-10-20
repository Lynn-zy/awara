package me.rerere.awara.data.repo

import java.util.concurrent.TimeUnit
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import me.rerere.awara.data.source.IwaraAPI
import me.rerere.awara.util.SerializationConverterFactory
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import retrofit2.Retrofit

/** 评论网络请求测试。 使用真实的 Retrofit + MockWebServer 验证接口路径、HTTP 动词、请求体结构以及非 2xx 异常拒绝。 */
class CommentRequestTest {

    /** 验证更新评论接口发送规范的 PUT 请求到 /comment/{id}， 请求体 JSON 包含 body，且绝对严禁携带 parentId。 */
    @Test
    fun updateCommentSendsPutWithoutParentId() =
        runBlocking<Unit> {
            val server = MockWebServer()
            server.start()
            try {
                val api =
                    Retrofit.Builder()
                        .baseUrl(server.url("/"))
                        .addConverterFactory(SerializationConverterFactory.create())
                        .build()
                        .create(IwaraAPI::class.java)
                val repo = CommentRepo(api)

                server.enqueue(MockResponse().setResponseCode(200))
                repo.updateComment(id = "comm_101", content = "已更新的评论内容")

                val request = server.takeRequest(2, TimeUnit.SECONDS)
                assertNotNull("未接收到 MockWebServer 请求", request)
                assertEquals("PUT", request!!.method)
                assertEquals("/comment/comm_101", request.requestUrl!!.encodedPath)

                val bodyString = request.body.readUtf8()
                val jsonObject = Json.parseToJsonElement(bodyString).jsonObject
                assertTrue("请求体必须包含 body 字段", jsonObject.containsKey("body"))
                assertEquals("已更新的评论内容", jsonObject["body"]?.toString()?.trim('"'))
                assertFalse("更新评论请求体严禁携带 parentId 字段", jsonObject.containsKey("parentId"))
            } finally {
                server.shutdown()
            }
        }

    /** 验证删除评论接口发送规范的 DELETE 请求到 /comment/{id}，且请求体为空。 */
    @Test
    fun deleteCommentSendsDeleteRequest() =
        runBlocking<Unit> {
            val server = MockWebServer()
            server.start()
            try {
                val api =
                    Retrofit.Builder()
                        .baseUrl(server.url("/"))
                        .addConverterFactory(SerializationConverterFactory.create())
                        .build()
                        .create(IwaraAPI::class.java)
                val repo = CommentRepo(api)

                server.enqueue(MockResponse().setResponseCode(200))
                repo.deleteComment(id = "comm_202")

                val request = server.takeRequest(2, TimeUnit.SECONDS)
                assertNotNull("未接收到 MockWebServer 请求", request)
                assertEquals("DELETE", request!!.method)
                assertEquals("/comment/comm_202", request.requestUrl!!.encodedPath)
                assertEquals(0L, request.bodySize)
            } finally {
                server.shutdown()
            }
        }

    /** 验证更新评论在服务端返回 non-2xx 错误响应（如 403 Forbidden）时， 仓库绝不假装成功，必须抛出 IllegalStateException。 */
    @Test
    fun updateCommentFailsOnNon2xxResponse() =
        runBlocking<Unit> {
            val server = MockWebServer()
            server.start()
            try {
                val api =
                    Retrofit.Builder()
                        .baseUrl(server.url("/"))
                        .addConverterFactory(SerializationConverterFactory.create())
                        .build()
                        .create(IwaraAPI::class.java)
                val repo = CommentRepo(api)

                server.enqueue(
                    MockResponse().setResponseCode(403).setBody("""{"message":"Forbidden"}""")
                )
                try {
                    repo.updateComment("comm_403", "无权限修改")
                    fail("预期非 2xx 响应抛出异常，但未抛出")
                } catch (e: IllegalStateException) {
                    assertTrue("异常信息应包含更新评论失败", e.message?.contains("更新评论失败") == true)
                }

                val request = server.takeRequest(2, TimeUnit.SECONDS)
                assertNotNull(request)
                assertEquals("PUT", request!!.method)
                assertEquals("/comment/comm_403", request.requestUrl!!.encodedPath)
            } finally {
                server.shutdown()
            }
        }

    /** 验证删除评论在服务端返回 non-2xx 错误响应（如 404 Not Found）时， 仓库绝不假装成功，必须抛出 IllegalStateException。 */
    @Test
    fun deleteCommentFailsOnNon2xxResponse() =
        runBlocking<Unit> {
            val server = MockWebServer()
            server.start()
            try {
                val api =
                    Retrofit.Builder()
                        .baseUrl(server.url("/"))
                        .addConverterFactory(SerializationConverterFactory.create())
                        .build()
                        .create(IwaraAPI::class.java)
                val repo = CommentRepo(api)

                server.enqueue(
                    MockResponse()
                        .setResponseCode(404)
                        .setBody("""{"message":"Comment not found"}""")
                )
                try {
                    repo.deleteComment("comm_404")
                    fail("预期非 2xx 响应抛出异常，但未抛出")
                } catch (e: IllegalStateException) {
                    assertTrue("异常信息应包含删除评论失败", e.message?.contains("删除评论失败") == true)
                }

                val request = server.takeRequest(2, TimeUnit.SECONDS)
                assertNotNull(request)
                assertEquals("DELETE", request!!.method)
                assertEquals("/comment/comm_404", request.requestUrl!!.encodedPath)
            } finally {
                server.shutdown()
            }
        }

    /** 验证视频回复接口正确将 parentId 封装在 CommentCreationDto 中并发送 POST 请求。 */
    @Test
    fun postVideoCommentWithParentIdSendsCreationDtoWithParentId() =
        runBlocking<Unit> {
            val server = MockWebServer()
            server.start()
            try {
                val api =
                    Retrofit.Builder()
                        .baseUrl(server.url("/"))
                        .addConverterFactory(SerializationConverterFactory.create())
                        .build()
                        .create(IwaraAPI::class.java)
                val repo = CommentRepo(api)

                // 模拟服务端成功返回新建的评论实体 JSON
                val responseJson =
                    """
                    {
                        "id": "reply_new_1",
                        "body": "这是视频回复正文",
                        "createdAt": "2026-10-19T00:00:00Z",
                        "updatedAt": "2026-10-19T00:00:00Z",
                        "numReplies": 0,
                        "user": null
                    }
                    """
                        .trimIndent()
                server.enqueue(MockResponse().setResponseCode(200).setBody(responseJson))

                val result =
                    repo.postVideoComment(
                        id = "vid_999",
                        content = "这是视频回复正文",
                        parent = "parent_comm_888",
                    )
                assertEquals("reply_new_1", result.id)

                val request = server.takeRequest(2, TimeUnit.SECONDS)
                assertNotNull("未接收到 MockWebServer 请求", request)
                assertEquals("POST", request!!.method)
                assertEquals("/video/vid_999/comments", request.requestUrl!!.encodedPath)

                val bodyString = request.body.readUtf8()
                val jsonObject = Json.parseToJsonElement(bodyString).jsonObject
                assertEquals("这是视频回复正文", jsonObject["body"]?.toString()?.trim('"'))
                assertEquals("parent_comm_888", jsonObject["parentId"]?.toString()?.trim('"'))
            } finally {
                server.shutdown()
            }
        }

    /** 验证用户主页留言回复接口正确将 parentId 封装在 CommentCreationDto 中并发送 POST 请求。 */
    @Test
    fun postProfileCommentWithParentIdSendsCreationDtoWithParentId() =
        runBlocking<Unit> {
            val server = MockWebServer()
            server.start()
            try {
                val api =
                    Retrofit.Builder()
                        .baseUrl(server.url("/"))
                        .addConverterFactory(SerializationConverterFactory.create())
                        .build()
                        .create(IwaraAPI::class.java)
                val repo = CommentRepo(api)

                val responseJson =
                    """
                    {
                        "id": "profile_reply_1",
                        "body": "这是留言回复正文",
                        "createdAt": "2026-10-19T00:00:00Z",
                        "updatedAt": "2026-10-19T00:00:00Z",
                        "numReplies": 0,
                        "user": null
                    }
                    """
                        .trimIndent()
                server.enqueue(MockResponse().setResponseCode(200).setBody(responseJson))

                val result =
                    repo.postProfileComment(
                        id = "user_target_456",
                        content = "这是留言回复正文",
                        parent = "parent_comment_123",
                    )
                assertEquals("profile_reply_1", result.id)

                val request = server.takeRequest(2, TimeUnit.SECONDS)
                assertNotNull("未接收到 MockWebServer 请求", request)
                assertEquals("POST", request!!.method)
                assertEquals("/profile/user_target_456/comments", request.requestUrl!!.encodedPath)

                val bodyString = request.body.readUtf8()
                val jsonObject = Json.parseToJsonElement(bodyString).jsonObject
                assertEquals("这是留言回复正文", jsonObject["body"]?.toString()?.trim('"'))
                assertEquals("parent_comment_123", jsonObject["parentId"]?.toString()?.trim('"'))
            } finally {
                server.shutdown()
            }
        }
}
