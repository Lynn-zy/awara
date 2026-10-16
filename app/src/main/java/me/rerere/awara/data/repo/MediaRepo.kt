package me.rerere.awara.data.repo

import java.io.IOException
import kotlinx.serialization.decodeFromString
import me.rerere.awara.data.entity.FavoriteImage
import me.rerere.awara.data.entity.FavoriteVideo
import me.rerere.awara.data.entity.Image
import me.rerere.awara.data.entity.PlaylistCreationDto
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

    suspend fun likeVideo(id: String) = iwaraAPI.likeVideo(id)

    suspend fun unlikeVideo(id: String) = iwaraAPI.unlikeVideo(id)

    suspend fun likeImage(id: String) = iwaraAPI.likeImage(id)

    suspend fun unlikeImage(id: String) = iwaraAPI.unlikeImage(id)

    suspend fun getImage(id: String) = iwaraAPI.getImage(id)

    suspend fun getTagsSuggestions(query: String) = iwaraAPI.autoCompleteTags(query)

    suspend fun getPlaylists(userId: String, page: Int) =
        iwaraAPI.getPlaylists(mapOf("page" to page.toString(), "user" to userId))

    suspend fun getPlaylistContent(playlistId: String, page: Int) =
        iwaraAPI.getPlaylist(id = playlistId, page = page)

    suspend fun getLightPlaylist(videoId: String) = iwaraAPI.getLightPlaylists(videoId)

    suspend fun addVideoToPlaylist(videoId: String, playlistId: String) =
        iwaraAPI.addVideoToPlaylist(videoId, playlistId)

    suspend fun removeVideoFromPlaylist(videoId: String, playlistId: String) =
        iwaraAPI.removeVideoFromPlaylist(videoId, playlistId)

    suspend fun createPlaylist(title: String) =
        iwaraAPI.createPlaylist(PlaylistCreationDto(title = title))

    suspend fun getFavoriteVideos(page: Int): Pager<FavoriteVideo> =
        iwaraAPI.getFavoriteVideos(page = page)

    suspend fun getFavoriteImages(page: Int): Pager<FavoriteImage> =
        iwaraAPI.getFavoriteImages(page = page)

    suspend fun searchVideo(query: String, page: Int, sort: String): Pager<Video> =
        iwaraAPI.searchVideo(query = query, page = page, type = "videos", sort = sort)

    suspend fun searchImage(query: String, page: Int, sort: String): Pager<Image> =
        iwaraAPI.searchImage(query = query, page = page, type = "images", sort = sort)

    suspend fun searchUser(query: String, page: Int, sort: String): Pager<User> =
        iwaraAPI.searchUser(query = query, page = page, type = "users", sort = sort)
}
