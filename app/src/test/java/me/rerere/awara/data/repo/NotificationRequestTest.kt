package me.rerere.awara.data.repo

import java.util.concurrent.TimeUnit
import kotlinx.coroutines.runBlocking
import me.rerere.awara.data.source.IwaraAPI
import me.rerere.awara.util.SerializationConverterFactory
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import retrofit2.Retrofit

/** 通知标记已读网络请求测试。 使用真实的 Retrofit + MockWebServer 验证接口路径、HTTP 动词、请求体为空以及非 2xx 响应处理。 */
class NotificationRequestTest {

    /** 验证单条通知标记已读接口发送规范的 POST 请求，路径匹配且请求体为空。 */
    @Test
    fun markNotificationAsReadSendsPostWithEmptyBodyAndTargetId() =
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
                val repo = UserRepo(api)

                server.enqueue(MockResponse().setResponseCode(200))
                repo.markNotificationAsRead("notif-123")

                val request = server.takeRequest(2, TimeUnit.SECONDS)
                assertNotNull("未接收到 MockWebServer 请求", request)
                assertEquals("POST", request!!.method)
                assertEquals("/notifications/notif-123/read", request.requestUrl!!.encodedPath)
                assertEquals(0L, request.bodySize)
            } finally {
                server.shutdown()
            }
        }

    /** 验证单条通知标记已读在服务端返回 non-2xx 时被仓库拒绝并抛出异常，绝不假装成功。 */
    @Test
    fun markNotificationAsReadFailsOnNon2xxResponse() =
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
                val repo = UserRepo(api)

                server.enqueue(
                    MockResponse().setResponseCode(404).setBody("""{"message":"Not found"}""")
                )
                try {
                    repo.markNotificationAsRead("notif-404")
                    fail("预期请求失败抛出异常，但返回了成功")
                } catch (e: IllegalStateException) {
                    assertTrue("异常信息应包含标记通知已读失败", e.message?.contains("标记通知已读失败") == true)
                }

                val request = server.takeRequest(2, TimeUnit.SECONDS)
                assertNotNull(request)
                assertEquals("POST", request!!.method)
                assertEquals("/notifications/notif-404/read", request.requestUrl!!.encodedPath)
            } finally {
                server.shutdown()
            }
        }

    /** 验证全部通知标记已读接口发送规范的 POST 请求，路径匹配且请求体为空。 */
    @Test
    fun markAllNotificationsAsReadSendsPostWithEmptyBodyAndPath() =
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
                val repo = UserRepo(api)

                server.enqueue(MockResponse().setResponseCode(200))
                repo.markAllNotificationsAsRead()

                val request = server.takeRequest(2, TimeUnit.SECONDS)
                assertNotNull("未接收到 MockWebServer 请求", request)
                assertEquals("POST", request!!.method)
                assertEquals("/notifications/all/read", request.requestUrl!!.encodedPath)
                assertEquals(0L, request.bodySize)
            } finally {
                server.shutdown()
            }
        }

    /** 验证全部通知标记已读在服务端返回 non-2xx 时被仓库拒绝并抛出异常，绝不假装成功。 */
    @Test
    fun markAllNotificationsAsReadFailsOnNon2xxResponse() =
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
                val repo = UserRepo(api)

                server.enqueue(
                    MockResponse()
                        .setResponseCode(500)
                        .setBody("""{"message":"Internal Server Error"}""")
                )
                try {
                    repo.markAllNotificationsAsRead()
                    fail("预期请求失败抛出异常，但返回了成功")
                } catch (e: IllegalStateException) {
                    assertTrue("异常信息应包含全部标记已读失败", e.message?.contains("全部标记已读失败") == true)
                }

                val request = server.takeRequest(2, TimeUnit.SECONDS)
                assertNotNull(request)
                assertEquals("POST", request!!.method)
                assertEquals("/notifications/all/read", request.requestUrl!!.encodedPath)
            } finally {
                server.shutdown()
            }
        }
}
