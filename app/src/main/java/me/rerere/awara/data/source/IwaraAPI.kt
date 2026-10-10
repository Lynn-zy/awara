package me.rerere.awara.data.source

import me.rerere.awara.data.dto.AccessTokenRes
import me.rerere.awara.data.dto.FriendRequestDto
import me.rerere.awara.data.dto.FriendStatusDto
import me.rerere.awara.data.dto.LoginReq
import me.rerere.awara.data.dto.LoginRes
import me.rerere.awara.data.dto.NotificationCounts
import me.rerere.awara.data.dto.ProfileDto
import me.rerere.awara.data.dto.Self
import me.rerere.awara.data.entity.Comment
import me.rerere.awara.data.entity.CommentCreationDto
import me.rerere.awara.data.entity.Conversation
import me.rerere.awara.data.entity.ConversationCreatedDto
import me.rerere.awara.data.entity.ConversationCreationDto
import me.rerere.awara.data.entity.FavoriteImage
import me.rerere.awara.data.entity.FavoriteVideo
import me.rerere.awara.data.entity.Follower
import me.rerere.awara.data.entity.Following
import me.rerere.awara.data.entity.Image
import me.rerere.awara.data.entity.Message
import me.rerere.awara.data.entity.MessageCreationDto
import me.rerere.awara.data.entity.MessageList
import me.rerere.awara.data.entity.Notification
import me.rerere.awara.data.entity.Playlist
import me.rerere.awara.data.entity.PlaylistCreationDto
import me.rerere.awara.data.entity.PlaylistPager
import me.rerere.awara.data.entity.Tag
import me.rerere.awara.data.entity.User
import me.rerere.awara.data.entity.Video
import retrofit2.Response
import retrofit2.http.Body
import retrofit2.http.DELETE
import retrofit2.http.GET
import retrofit2.http.POST
import retrofit2.http.Path
import retrofit2.http.Query
import retrofit2.http.QueryMap

interface IwaraAPI {
    @POST("/user/login") suspend fun login(@Body loginReq: LoginReq): LoginRes

    @POST("/user/token") suspend fun renewToken(): AccessTokenRes

    @GET("/user") suspend fun getSelfProfile(): Self

    @GET("/profile/{username}")
    suspend fun getProfile(@Path("username") username: String): ProfileDto

    @GET("/videos") suspend fun getVideoList(@QueryMap queryMap: Map<String, String>): Pager<Video>

    @GET("/images") suspend fun getImageList(@QueryMap queryMap: Map<String, String>): Pager<Image>

    @GET("/video/{id}") suspend fun getVideo(@Path("id") id: String): Video

    @GET("/video/{id}/related") suspend fun getRelatedVideo(@Path("id") id: String): Pager<Video>

    @POST("/video/{id}/like") suspend fun likeVideo(@Path("id") id: String): Response<Unit>

    @DELETE("/video/{id}/like") suspend fun unlikeVideo(@Path("id") id: String): Response<Unit>

    @POST("/image/{id}/like") suspend fun likeImage(@Path("id") id: String): Response<Unit>

    @DELETE("/image/{id}/like") suspend fun unlikeImage(@Path("id") id: String): Response<Unit>

    @GET("/video/{id}/comments")
    suspend fun getVideoComments(
        @Path("id") id: String,
        @QueryMap queryMap: Map<String, String>,
    ): Pager<Comment>

    @POST("/video/{id}/comments")
    suspend fun postVideoComment(@Path("id") id: String, @Body comment: CommentCreationDto): Comment

    /**
     * 获取用户资料的评论或回复分页列表。
     *
     * @param id 用户资料 ID（对应 ProfileDto 的 user.id）
     * @param params 分页与二级回复筛选参数，如 page、parent
     * @return 评论分页数据
     */
    @GET("/profile/{id}/comments")
    suspend fun getProfileComments(
        @Path("id") id: String,
        @QueryMap params: Map<String, String>,
    ): Pager<Comment>

    /**
     * 在用户资料主页发表新留言或二级回复。
     *
     * @param id 用户资料 ID（对应 ProfileDto 的 user.id）
     * @param dto 包含正文与可选父评论 ID 的数据传输对象
     * @return 发布成功的评论实体
     */
    @POST("/profile/{id}/comments")
    suspend fun postProfileComment(@Path("id") id: String, @Body dto: CommentCreationDto): Comment

    @GET("/image/{id}") suspend fun getImage(@Path("id") id: String): Image

    @POST("/user/{id}/followers") suspend fun followUser(@Path("id") id: String): Response<Unit>

    @DELETE("/user/{id}/followers") suspend fun unfollowUser(@Path("id") id: String): Response<Unit>

    @GET("/autocomplete/tags")
    suspend fun autoCompleteTags(@Query("query") query: String): Pager<Tag>

    @GET("/playlists")
    suspend fun getPlaylists(@QueryMap queryMap: Map<String, String>): Pager<Playlist>

    @GET("/playlist/{id}")
    suspend fun getPlaylist(@Path("id") id: String, @Query("page") page: Int): PlaylistPager

    @GET("/light/playlists") suspend fun getLightPlaylists(@Query("id") id: String): List<Playlist>

    @POST("/playlist/{id}/{videoId}")
    suspend fun addVideoToPlaylist(
        @Path("id") id: String,
        @Path("videoId") videoId: String,
    ): Response<Unit>

    @DELETE("/playlist/{id}/{videoId}")
    suspend fun removeVideoFromPlaylist(
        @Path("id") id: String,
        @Path("videoId") videoId: String,
    ): Response<Unit>

    @POST("/playlists") suspend fun createPlaylist(@Body dto: PlaylistCreationDto): Playlist

    @GET("/user/counts") suspend fun getNotificationCount(): NotificationCounts

    /** 目标版本仅查询通知，不提供删除或标记已读操作。 */
    @GET("/user/{userId}/notifications")
    suspend fun getNotifications(@Path("userId") userId: String): Pager<Notification>

    @GET("/user/{userId}/followers")
    suspend fun getUserFollowers(
        @Path("userId") userId: String,
        @QueryMap queryMap: Map<String, String>,
    ): Pager<Follower>

    @GET("/user/{userId}/following")
    suspend fun getUserFollowing(
        @Path("userId") userId: String,
        @QueryMap queryMap: Map<String, String>,
    ): Pager<Following>

    @GET("/user/{userId}/friends")
    suspend fun getUserFriends(
        @Path("userId") userId: String,
        @QueryMap queryMap: Map<String, String>,
    ): Pager<User>

    @POST("user/{userId}/conversations")
    suspend fun createConversation(
        @Path("userId") userId: String,
        @Body body: ConversationCreationDto,
    ): ConversationCreatedDto

    @GET("user/{userId}/conversations")
    suspend fun getConversations(
        @Path("userId") userId: String,
        @Query("page") page: Int = 0,
    ): Pager<Conversation>

    @GET("conversation/{id}/messages")
    suspend fun getMessages(
        @Path("id") conversationId: String,
        @Query("before") before: String? = null,
    ): MessageList

    @POST("conversation/{id}/messages")
    suspend fun sendMessage(
        @Path("id") conversationId: String,
        @Body body: MessageCreationDto,
    ): Message

    @GET("/user/{userId}/friends/requests")
    suspend fun getUserFriendRequests(
        @Path("userId") userId: String,
        @QueryMap queryMap: Map<String, String>,
    ): Pager<FriendRequestDto>

    @POST("/user/{userId}/friends")
    suspend fun addFriend(@Path("userId") userId: String): Response<Unit>

    @DELETE("/user/{userId}/friends")
    suspend fun removeFriend(@Path("userId") userId: String): Response<Unit>

    @GET("/user/{userId}/friends/status")
    suspend fun getFriendStatus(@Path("userId") userId: String): FriendStatusDto

    @GET("/favorites/videos")
    suspend fun getFavoriteVideos(@Query("page") page: Int): Pager<FavoriteVideo>

    @GET("/favorites/images")
    suspend fun getFavoriteImages(@Query("page") page: Int): Pager<FavoriteImage>

    @GET("/search")
    suspend fun searchVideo(
        @Query("type") type: String,
        @Query("query") query: String,
        @Query("page") page: Int,
    ): Pager<Video>

    @GET("/search")
    suspend fun searchImage(
        @Query("type") type: String,
        @Query("query") query: String,
        @Query("page") page: Int,
    ): Pager<Image>

    @GET("/search")
    suspend fun searchUser(
        @Query("type") type: String,
        @Query("query") query: String,
        @Query("page") page: Int,
    ): Pager<User>
}
