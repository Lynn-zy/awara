package me.rerere.awara.data.entity

import kotlinx.serialization.decodeFromString
import me.rerere.awara.util.JsonInstance
import org.junit.Assert.assertEquals
import org.junit.Test

class PlaylistSerializationTest {
    @Test
    fun compositeThumbnailDecodesAndBuildsCoverUrl() {
        val playlist =
            JsonInstance.decodeFromString<Playlist>(
                """
                {
                  "id": "playlist-1",
                  "numVideos": 3,
                  "title": "测试播单",
                  "user": null,
                  "added": false,
                  "thumbnail": {
                    "file": {
                      "id": "thumbnail-file",
                      "mime": "video/mp4",
                      "name": "video.mp4",
                      "path": "/video.mp4",
                      "type": "video"
                    },
                    "thumbnail": 2
                  }
                }
                """
                    .trimIndent()
            )

        assertEquals(
            "https://i.iwara.tv/image/thumbnail/thumbnail-file/thumbnail-02.jpg",
            playlist.coverUrl(),
        )
    }
}
