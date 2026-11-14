package me.rerere.awara.data.entity

import kotlinx.serialization.Serializable

@Serializable data class PlaylistThumbnail(val file: File, val thumbnail: Int)

@Serializable
data class Playlist(
    val id: String,
    val numVideos: Int,
    val title: String,
    val user: User?,
    val added: Boolean?,
    val thumbnail: PlaylistThumbnail? = null,
)

fun Playlist.coverUrl(): String? {
    val value = thumbnail ?: return null
    val index = value.thumbnail.toString().padStart(2, '0')
    return "https://i.iwara.tv/image/thumbnail/${value.file.id}/thumbnail-$index.jpg"
}

@Serializable
data class PlaylistPager(
    val count: Int,
    val limit: Int,
    val page: Int,
    val results: List<Video>,
    val playlist: Playlist,
)
