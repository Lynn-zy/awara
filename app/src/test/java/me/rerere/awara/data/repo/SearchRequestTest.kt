package me.rerere.awara.data.repo

import java.net.InetAddress
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.runBlocking
import me.rerere.awara.data.source.IwaraAPI
import me.rerere.awara.util.SerializationConverterFactory
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test
import retrofit2.Retrofit

class SearchRequestTest {
    @Test
    fun searchRequestsUseServerCollectionTypesAndPreserveQueryAndPage() =
        runBlocking<Unit> {
            val server = MockWebServer()
            server.start(InetAddress.getByName("127.0.0.1"), 0)
            try {
                val api =
                    Retrofit.Builder()
                        .baseUrl(server.url("/"))
                        .addConverterFactory(SerializationConverterFactory.create())
                        .build()
                        .create(IwaraAPI::class.java)
                val repo = MediaRepo(OkHttpClient(), api)
                val query = "日文 空格 &+"
                for (type in SearchType.values()) {
                    server.enqueue(
                        MockResponse()
                            .setHeader("Content-Type", "application/json")
                            .setBody("""{"count":0,"limit":32,"page":2,"results":[]}""")
                    )
                    val sort =
                        if (type == SearchType.USER) SearchSort.DATE else SearchSort.LIKES
                    when (type) {
                        SearchType.VIDEO -> repo.searchVideo(query, 2, sort)
                        SearchType.IMAGE -> repo.searchImage(query, 2, sort)
                        SearchType.USER -> repo.searchUser(query, 2, sort)
                    }
                    val request = server.takeRequest(2, TimeUnit.SECONDS)
                    assertNotNull(request)
                    val url = request!!.requestUrl!!
                    assertEquals("/search", url.encodedPath)
                    assertEquals(type.apiValue, url.queryParameter("type"))
                    assertEquals(query, url.queryParameter("query"))
                    assertEquals("2", url.queryParameter("page"))
                    assertEquals(sort.apiValue, url.queryParameter("sort"))
                }
            } finally {
                server.shutdown()
            }
        }
}
