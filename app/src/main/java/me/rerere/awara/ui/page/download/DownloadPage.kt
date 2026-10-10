package me.rerere.awara.ui.page.download

import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.Intent
import android.content.pm.ActivityInfo
import android.net.Uri
import android.os.Environment
import android.util.Log
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.FitScreen
import androidx.compose.material.icons.outlined.Fullscreen
import androidx.compose.material.icons.outlined.FullscreenExit
import androidx.compose.material.icons.outlined.HelpOutline
import androidx.compose.material.icons.outlined.Undo
import androidx.compose.material3.Card
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LargeTopAppBar
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SwipeToDismiss
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberDismissState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import androidx.paging.compose.collectAsLazyPagingItems
import androidx.paging.compose.items
import coil.compose.AsyncImage
import com.google.android.exoplayer2.ExoPlayer
import com.google.android.exoplayer2.MediaItem
import com.google.android.exoplayer2.ui.AspectRatioFrameLayout
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import me.rerere.awara.R
import me.rerere.awara.data.entity.DownloadItem
import me.rerere.awara.ui.LocalMessageProvider
import me.rerere.awara.ui.component.common.BackButton
import me.rerere.awara.ui.component.ext.plus
import me.rerere.awara.ui.component.player.Player
import me.rerere.awara.ui.component.player.PlayerScaffold
import me.rerere.awara.ui.component.player.PlayerState
import me.rerere.awara.ui.component.player.rememberPlayerState
import me.rerere.awara.ui.hooks.rememberRequestedScreenOrientation
import me.rerere.awara.util.prettyFileSize
import me.rerere.awara.util.toLocalDateTimeString
import me.rerere.compose_setting.preference.rememberStringPreference
import org.koin.androidx.compose.koinViewModel

@Composable
fun DownloadPage(vm: DownloadVM = koinViewModel()) {
    val items = vm.downloadedItems.collectAsLazyPagingItems()
    val appBarScrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()
    val context = LocalContext.current
    val message = LocalMessageProvider.current
    // 下载视频播放器偏好：internal=内置播放器，external=第三方播放器
    val internalPlayer by
        rememberStringPreference(key = "setting.download_player_internal", default = "internal")

    val state = rememberPlayerState { ExoPlayer.Builder(it).build() }
    var requestOrientation by rememberRequestedScreenOrientation()
    var fullscreen by remember { mutableStateOf(false) }
    fun enterFullScreen() {
        Log.i("DownloadPage", "enterFullScreen: ${state.videoSize.width}")
        if (state.videoSize.width > state.videoSize.height) {
            fullscreen = true
            requestOrientation = ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
        } else {
            fullscreen = true
            requestOrientation = ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
        }
    }

    LaunchedEffect(state.videoSize) {
        if (state.videoSize != PlayerState.VideoSize.UNKNOWN) {
            enterFullScreen()
            state.play()
        }
    }

    fun exitFullScreen() {
        requestOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
        fullscreen = false
        state.pause()
    }

    BackHandler(fullscreen) { exitFullScreen() }

    PlayerScaffold(
        fullscreen = fullscreen,
        player = {
            Player(
                state = state,
                fullscreen = fullscreen,
                modifier = Modifier.fillMaxSize(),
                navigationIcon = { BackButton() },
                actions = {
                    if (fullscreen) {
                        IconButton(
                            onClick = {
                                state.resizeMode =
                                    if (state.resizeMode == AspectRatioFrameLayout.RESIZE_MODE_FIT)
                                        AspectRatioFrameLayout.RESIZE_MODE_ZOOM
                                    else AspectRatioFrameLayout.RESIZE_MODE_FIT
                            }
                        ) {
                            Icon(Icons.Outlined.FitScreen, null)
                        }
                    }
                },
                controllerTrail = {
                    IconButton(
                        onClick = { if (!fullscreen) enterFullScreen() else exitFullScreen() }
                    ) {
                        Icon(
                            if (!fullscreen) Icons.Outlined.Fullscreen
                            else Icons.Outlined.FullscreenExit,
                            "Fullscreen",
                        )
                    }
                },
            )
        },
    ) { player ->
        Scaffold(
            topBar = {
                LargeTopAppBar(
                    title = { Text(stringResource(R.string.download)) },
                    navigationIcon = { BackButton() },
                    actions = {
                        // 问号按钮：显示下载文件保存目录
                        IconButton(
                            onClick = {
                                val dir =
                                    context
                                        .getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS)
                                        ?.absolutePath ?: "--"
                                message.info {
                                    Text(stringResource(R.string.download_directory_path, dir))
                                }
                            }
                        ) {
                            Icon(
                                Icons.Outlined.HelpOutline,
                                stringResource(R.string.download_show_directory),
                            )
                        }
                    },
                    scrollBehavior = appBarScrollBehavior,
                )
            }
        ) {
            LazyColumn(
                contentPadding = it + PaddingValues(8.dp),
                modifier =
                    Modifier.fillMaxSize()
                        .nestedScroll(appBarScrollBehavior.nestedScrollConnection),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                items(items, key = { item -> item.id }) { item ->
                    if (item != null) {
                        DownloadItem(item = item, onDelete = { vm.delete(item) }) {
                            when (internalPlayer) {
                                // 外部播放器：FileProvider 授予临时读权限后交由系统播放
                                "external" -> {
                                    val file = File(item.path)
                                    if (!file.exists()) {
                                        message.error {
                                            Text(stringResource(R.string.download_file_missing))
                                        }
                                    } else {
                                        try {
                                            val uri =
                                                FileProvider.getUriForFile(
                                                    context,
                                                    "${context.packageName}.provider",
                                                    file,
                                                )
                                            val intent =
                                                Intent(Intent.ACTION_VIEW).apply {
                                                    setDataAndType(uri, "video/*")
                                                    // 仅授予读取权限，不申请写权限；clipData 确保授权跟随 URI 传递
                                                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                                                    clipData = ClipData.newRawUri(null, uri)
                                                }
                                            context.startActivity(intent)
                                        } catch (e: Exception) {
                                            // 覆盖 ActivityNotFoundException、IllegalArgumentException
                                            // 等边界错误
                                            message.error {
                                                Text(
                                                    if (e is ActivityNotFoundException) {
                                                        stringResource(R.string.download_no_player)
                                                    } else {
                                                        stringResource(
                                                            R.string.download_open_external_failed,
                                                            e.message ?: e.javaClass.simpleName,
                                                        )
                                                    }
                                                )
                                            }
                                        }
                                    }
                                }
                                // 内置播放器：维持既有 ExoPlayer 全屏播放
                                else -> {
                                    state.player.setMediaItem(
                                        MediaItem.Builder()
                                            .setUri(Uri.fromFile(File(item.path)))
                                            .build()
                                    )
                                    state.player.prepare()
                                    fullscreen = true
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun DownloadItem(item: DownloadItem, onDelete: () -> Unit, onClick: () -> Unit) {
    // 文件大小需在 IO 线程读取，避免主线程文件 IO
    val fileSize by
        produceState(initialValue = "--", key1 = item.path) {
            value =
                withContext(Dispatchers.IO) {
                    val file = File(item.path)
                    if (file.exists()) prettyFileSize(file.length()) else "--"
                }
        }
    // 滑动仅展开删除/恢复操作，显式点击删除按钮才真正删除
    val dismissState = rememberDismissState()
    val scope = rememberCoroutineScope()
    SwipeToDismiss(
        state = dismissState,
        background = {
            Row(
                modifier = Modifier.fillMaxSize(),
                horizontalArrangement = Arrangement.spacedBy(16.dp, Alignment.End),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(onClick = onDelete) {
                    Icon(Icons.Outlined.Delete, stringResource(R.string.download_delete))
                }
                // 恢复操作：收起滑动并恢复卡片显示
                IconButton(onClick = { scope.launch { dismissState.reset() } }) {
                    Icon(Icons.Outlined.Undo, stringResource(R.string.download_restore))
                }
            }
        },
        dismissContent = {
            Card(onClick = onClick, modifier = Modifier.fillMaxWidth()) {
                Column(
                    modifier = Modifier.padding(16.dp).fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        AsyncImage(
                            model = item.thumbnail,
                            contentDescription = null,
                            modifier =
                                Modifier.clip(RoundedCornerShape(8.dp))
                                    .height(64.dp)
                                    .aspectRatio(16f / 9f),
                            contentScale = ContentScale.Crop,
                        )

                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = item.title,
                                style = MaterialTheme.typography.titleLarge,
                                maxLines = 1,
                            )

                            Text(
                                text = item.time.toLocalDateTimeString(),
                                style = MaterialTheme.typography.labelMedium,
                                maxLines = 1,
                            )

                            Text(
                                text = fileSize,
                                style = MaterialTheme.typography.labelMedium,
                                maxLines = 1,
                            )
                        }
                    }
                }
            }
        },
    )
}
