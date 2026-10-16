package me.rerere.awara.ui.component.iwara

import android.net.Uri
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.FavoriteBorder
import androidx.compose.material3.Badge
import androidx.compose.material3.Card
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImagePainter
import coil.compose.rememberAsyncImagePainter
import me.rerere.awara.R
import me.rerere.awara.data.entity.Image
import me.rerere.awara.data.entity.Media
import me.rerere.awara.data.entity.Video
import me.rerere.awara.data.entity.formattedDuration
import me.rerere.awara.data.entity.thumbnailUrl
import me.rerere.awara.data.entity.toAvatarUrl
import me.rerere.awara.ui.LocalRouterProvider
import me.rerere.awara.ui.component.common.SkeletonBox
import me.rerere.awara.util.formatRelativeTimeText
import me.rerere.compose_setting.preference.rememberBooleanPreference

@Composable
fun MediaCard(modifier: Modifier = Modifier, media: Media) {
    val router = LocalRouterProvider.current
    val workMode by rememberBooleanPreference(key = "setting.work_mode", default = false)
    val painter = rememberAsyncImagePainter(model = media.thumbnailUrl())
    Card(
        modifier = modifier,
        onClick = {
            when (media) {
                is Video -> router.navigate("video/${media.id}")
                is Image -> router.navigate("image/${media.id}")
            }
        },
    ) {
        Column {
            Box(modifier = Modifier.fillMaxWidth().aspectRatio(220f / 160f)) {
                SkeletonBox(
                    show = painter.state is AsyncImagePainter.State.Loading,
                    modifier = Modifier.fillMaxSize(),
                ) {
                    Image(
                        painter = painter,
                        contentDescription = "Media Cover",
                        contentScale = ContentScale.Crop,
                        modifier =
                            Modifier.fillMaxSize()
                                .then(if (workMode) Modifier.blur(8.dp) else Modifier),
                    )
                }

                if (media is Video && media.private) {
                    Badge(modifier = Modifier.padding(4.dp).align(Alignment.TopStart)) {
                        Text(
                            text = stringResource(R.string.private_badge),
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                }

                Surface(
                    shape = RoundedCornerShape(4.dp),
                    color = Color.Black.copy(alpha = 0.65f),
                    contentColor = Color.White,
                    modifier = Modifier.padding(4.dp).align(Alignment.TopEnd),
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.padding(horizontal = 4.dp, vertical = 2.dp),
                    ) {
                        Icon(
                            imageVector = Icons.Outlined.FavoriteBorder,
                            contentDescription = null,
                            modifier = Modifier.size(12.dp),
                        )
                        Spacer(modifier = Modifier.width(2.dp))
                        Text(
                            text = media.numLikes.toString(),
                            style = MaterialTheme.typography.labelSmall,
                        )
                    }
                }

                val durationText = (media as? Video)?.formattedDuration
                if (!durationText.isNullOrEmpty()) {
                    Surface(
                        shape = RoundedCornerShape(4.dp),
                        color = Color.Black.copy(alpha = 0.65f),
                        contentColor = Color.White,
                        modifier = Modifier.padding(4.dp).align(Alignment.BottomEnd),
                    ) {
                        Text(
                            text = durationText,
                            style = MaterialTheme.typography.labelSmall,
                            modifier = Modifier.padding(horizontal = 4.dp, vertical = 2.dp),
                        )
                    }
                }
            }

            Column(modifier = Modifier.padding(horizontal = 6.dp, vertical = 4.dp)) {
                Text(
                    text = media.title.trim(),
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    style = MaterialTheme.typography.labelLarge,
                )

                Spacer(modifier = Modifier.height(4.dp))

                val authorUsername =
                    media.user?.username?.takeIf { it.isNotBlank() } ?: media.user?.id.orEmpty()
                val onAuthorClick: () -> Unit = {
                    if (authorUsername.isNotEmpty()) {
                        router.navigate("user/${Uri.encode(authorUsername)}")
                    }
                }

                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    me.rerere.awara.ui.component.common.Avatar(
                        model = media.user?.avatar.toAvatarUrl(),
                        modifier =
                            Modifier.size(28.dp).clip(CircleShape).clickable(
                                enabled = authorUsername.isNotEmpty()
                            ) {
                                onAuthorClick()
                            },
                    )

                    Spacer(modifier = Modifier.width(6.dp))

                    Column(
                        modifier =
                            Modifier.weight(1f).clickable(enabled = authorUsername.isNotEmpty()) {
                                onAuthorClick()
                            },
                        verticalArrangement =
                            androidx.compose.foundation.layout.Arrangement.spacedBy(1.dp),
                    ) {
                        Text(
                            text = media.user?.name.orEmpty().ifEmpty { authorUsername },
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            style = MaterialTheme.typography.labelMedium,
                            color = LocalContentColor.current.copy(alpha = 0.85f),
                        )
                        Text(
                            text = formatRelativeTimeText(media.createdAt),
                            maxLines = 1,
                            style = MaterialTheme.typography.labelSmall,
                            color = LocalContentColor.current.copy(alpha = 0.6f),
                        )
                    }
                }
            }
        }
    }
}
