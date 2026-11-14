package me.rerere.awara.data.repo

import java.io.IOException
import kotlinx.serialization.decodeFromString
import me.rerere.awara.data.dto.PlaylistCreationDto
import me.rerere.awara.data.entity.FavoriteImage
import me.rerere.awara.data.entity.FavoriteVideo
import me.rerere.awara.data.entity.Image
import me.rerere.awara.data.entity.User
import me.rerere.awara.data.entity.Video
import me.rerere.awara.data.entity.VideoFile
import me.rerere.awara.data.entity.availableVideoFiles
import me.rerere.awara.data.entity.signature
import me.rerere.awara.data.source.IwaraAPI
import me.rerere.awara.data.source.Pager
import me.rerere.awara.data.source.isIwaraSourceEndpoint
import me.rerere.awara.util.JsonInstance
import me.rerere.awara.util.await
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request

/** 搜索目标类型，对应 `/search` 接口的 `type` 查询参数。 */
enum class SearchType(val apiValue: String) {
    VIDEO("videos"),
    IMAGE("images"),
    USER("users");

    /** 返回当前搜索类型在主站下支持的排序方式。 */
    fun availableSorts(): List<SearchSort> =
        if (this == USER) listOf(SearchSort.RELEVANCE, SearchSort.DATE)
        else listOf(SearchSort.RELEVANCE, SearchSort.DATE, SearchSort.VIEWS, SearchSort.LIKES)
}

/** 搜索排序方式，对应 `/search` 接口的 `sort` 查询参数。 */
enum class SearchSort(val apiValue: String) {
    RELEVANCE("relevance"),
    DATE("date"),
    VIEWS("views"),
    LIKES("likes");

    companion object {
        /** 根据接口参数值解析搜索排序枚举，未匹配时返回 `null`。 */
        fun fromApiValue(value: String): SearchSort? = values().firstOrNull { it.apiValue == value }
    }
}

class MediaRepo(private val okHttpClient: OkHttpClient, private val iwaraAPI: IwaraAPI) {
    suspend fun getVideoList(queryMap: Map<String, String>) = iwaraAPI.getVideoList(queryMap)

    suspend fun getImageList(queryMap: Map<String, String>) = iwaraAPI.getImageList(queryMap)

    suspend fun getVideo(id: String) = iwaraAPI.getVideo(id)

    suspend fun parseVideoUrl(video: Video): List<VideoFile> {
        val fileUrl = (video.fileUrl ?: error("No file url")).toHttpUrl()
        require(fileUrl.isIwaraSourceEndpoint()) { "不受信任的视频源接口" }
        val hash = video.signature
        val request = Request.Builder().url(fileUrl).header("x-version", hash).get().build()
        return okHttpClient.newCall(request).await().use { response ->
            if (!response.isSuccessful) {
                throw IOException("获取视频清晰度失败（HTTP ${response.code}）")
            }
            val body = response.body ?: throw IOException("视频源响应为空")
            JsonInstance.decodeFromString<List<VideoFile>>(body.string()).availableVideoFiles()
        }
    }

    suspend fun getRelatedVideos(id: String) = iwaraAPI.getRelatedVideo(id)

    /** 点赞视频；服务端返回非 2xx 时抛出异常。 */
    suspend fun likeVideo(id: String) = iwaraAPI.likeVideo(id).requireSuccess()

    /** 取消点赞视频；服务端返回非 2xx 时抛出异常。 */
    suspend fun unlikeVideo(id: String) = iwaraAPI.unlikeVideo(id).requireSuccess()

    /** 点赞图片；服务端返回非 2xx 时抛出异常。 */
    suspend fun likeImage(id: String) = iwaraAPI.likeImage(id).requireSuccess()

    /** 取消点赞图片；服务端返回非 2xx 时抛出异常。 */
    suspend fun unlikeImage(id: String) = iwaraAPI.unlikeImage(id).requireSuccess()

    suspend fun getImage(id: String) = iwaraAPI.getImage(id)

    suspend fun getTagsSuggestions(query: String) = iwaraAPI.autoCompleteTags(query)

    suspend fun getPlaylists(userId: String, page: Int) =
        iwaraAPI.getPlaylists(mapOf("page" to page.toString(), "user" to userId))

    suspend fun getPlaylistContent(playlistId: String, page: Int) =
        iwaraAPI.getPlaylist(id = playlistId, page = page)

    suspend fun getLightPlaylist(videoId: String) = iwaraAPI.getLightPlaylists(videoId)

    /** 将视频添加到播单；服务端返回非 2xx 时抛出异常。 */
    suspend fun addVideoToPlaylist(videoId: String, playlistId: String) =
        iwaraAPI.addVideoToPlaylist(videoId, playlistId).requireSuccess()

    /** 从播单移除视频；服务端返回非 2xx 时抛出异常。 */
    suspend fun removeVideoFromPlaylist(videoId: String, playlistId: String) =
        iwaraAPI.removeVideoFromPlaylist(videoId, playlistId).requireSuccess()

    suspend fun createPlaylist(title: String) =
        iwaraAPI.createPlaylist(PlaylistCreationDto(title = title))

    suspend fun getFavoriteVideos(page: Int): Pager<FavoriteVideo> =
        iwaraAPI.getFavoriteVideos(page = page)

    suspend fun getFavoriteImages(page: Int): Pager<FavoriteImage> =
        iwaraAPI.getFavoriteImages(page = page)

    /** 使用强类型搜索排序参数检索视频。 */
    suspend fun searchVideo(query: String, page: Int, sort: SearchSort): Pager<Video> =
        iwaraAPI.searchVideo(
            query = query,
            page = page,
            type = SearchType.VIDEO.apiValue,
            sort = sort.apiValue,
        )

    /** 使用强类型搜索排序参数检索图片。 */
    suspend fun searchImage(query: String, page: Int, sort: SearchSort): Pager<Image> =
        iwaraAPI.searchImage(
            query = query,
            page = page,
            type = SearchType.IMAGE.apiValue,
            sort = sort.apiValue,
        )

    /** 使用强类型搜索排序参数检索用户；非用户支持排序会自动归一化为相关性排序。 */
    suspend fun searchUser(query: String, page: Int, sort: SearchSort): Pager<User> {
        val safeSort = if (sort in SearchType.USER.availableSorts()) sort else SearchSort.RELEVANCE
        return iwaraAPI.searchUser(
            query = query,
            page = page,
            type = SearchType.USER.apiValue,
            sort = safeSort.apiValue,
        )
    }
}
