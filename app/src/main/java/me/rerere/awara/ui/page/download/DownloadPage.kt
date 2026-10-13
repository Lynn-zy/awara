package me.rerere.awara.ui.page.download

import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.Intent
import android.content.pm.ActivityInfo
import android.net.Uri
import android.util.Log
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.FitScreen
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.outlined.Fullscreen
import androidx.compose.material.icons.outlined.FullscreenExit
import androidx.compose.material.icons.outlined.Sort
import androidx.compose.material.icons.outlined.Undo
import androidx.compose.material3.Card
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Divider
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.airbnb.lottie.compose.LottieAnimation
import com.airbnb.lottie.compose.LottieCompositionSpec
import com.airbnb.lottie.compose.LottieConstants
import com.airbnb.lottie.compose.animateLottieCompositionAsState
import com.airbnb.lottie.compose.rememberLottieComposition
import com.google.android.exoplayer2.ExoPlayer
import com.google.android.exoplayer2.MediaItem
import com.google.android.exoplayer2.ui.AspectRatioFrameLayout
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import me.rerere.awara.R
import me.rerere.awara.data.download.DownloadStorage
import me.rerere.awara.data.entity.DownloadItem
import me.rerere.awara.ui.LocalMessageProvider
import me.rerere.awara.ui.component.common.BackButton
import me.rerere.awara.ui.component.common.UiStateBox
import me.rerere.awara.ui.component.ext.plus
import me.rerere.awara.ui.component.iwara.DownloadDirectoryDialog
import me.rerere.awara.ui.component.player.Player
import me.rerere.awara.ui.component.player.PlayerScaffold
import me.rerere.awara.ui.component.player.PlayerState
import me.rerere.awara.ui.component.player.rememberPlayerState
import me.rerere.awara.ui.hooks.rememberRequestedScreenOrientation
import me.rerere.awara.util.prettyFileSize
import me.rerere.awara.util.toLocalDateTimeString
import me.rerere.compose_setting.preference.rememberBooleanPreference
import me.rerere.compose_setting.preference.rememberStringPreference
import org.koin.androidx.compose.koinViewModel
import org.koin.compose.koinInject

@Composable
fun DownloadPage(vm: DownloadVM = koinViewModel()) {
    val downloadedItems = vm.state.items
    val downloadStorage: DownloadStorage = koinInject()
    val appBarScrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()
    val context = LocalContext.current
    val message = LocalMessageProvider.current
    val scope = rememberCoroutineScope()

    val internalPlayer by
        rememberStringPreference(key = "setting.download_player_internal", default = "internal")
    var sortKey by
        rememberStringPreference(
            key = PREFERENCE_KEY_DOWNLOAD_SORT,
            default = DownloadSortMode.TIME.key,
        )
    var groupByAuthors by
        rememberBooleanPreference(key = PREFERENCE_KEY_DOWNLOAD_GROUP_AUTHORS, default = false)

    var showDirectoryDialog by remember { mutableStateOf(false) }
    var showSortMenu by remember { mutableStateOf(false) }

    val sortedItems =
        remember(downloadedItems, sortKey) { sortDownloadedItems(downloadedItems, sortKey) }
    val authorGroups =
        remember(downloadedItems, sortKey, groupByAuthors) {
            if (groupByAuthors) {
                groupDownloadedItemsByAuthor(downloadedItems, sortKey)
            } else {
                emptyList()
            }
        }

    LaunchedEffect(vm.state.deleteError) {
        val reason = vm.state.deleteError ?: return@LaunchedEffect
        message.error { Text(stringResource(R.string.download_delete_failed, reason)) }
        vm.clearDeleteError()
    }

    if (showDirectoryDialog) {
        DownloadDirectoryDialog(onDismiss = { showDirectoryDialog = false })
    }

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

    fun playDownloadedItem(item: DownloadItem) {
        val rawUri = item.uri.trim()
        if (rawUri.isEmpty()) {
            message.error { Text(stringResource(R.string.download_file_missing)) }
            return
        }
        val contentUri = runCatching { Uri.parse(rawUri) }.getOrNull()
        if (contentUri == null) {
            message.error { Text(stringResource(R.string.download_file_missing)) }
            return
        }
        scope.launch {
            // 在 IO 线程校验 content URI 可访问性，统一保护内置与第三方播放
            val exists =
                try {
                    withContext(Dispatchers.IO) { downloadStorage.exists(contentUri) }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Throwable) {
                    val reason =
                        e.localizedMessage?.takeIf { it.isNotBlank() } ?: e.javaClass.simpleName
                    message.error { Text(stringResource(R.string.download_play_failed, reason)) }
                    return@launch
                }

            if (!exists) {
                message.error { Text(stringResource(R.string.download_file_missing)) }
                return@launch
            }

            when (internalPlayer) {
                "external" -> {
                    try {
                        val intent =
                            Intent(Intent.ACTION_VIEW).apply {
                                setDataAndType(contentUri, "video/*")
                                // 仅授予读取权限并通过 ClipData 传递 URI 临时授权
                                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                                clipData = ClipData.newRawUri(null, contentUri)
                            }
                        context.startActivity(intent)
                    } catch (_: ActivityNotFoundException) {
                        message.error { Text(stringResource(R.string.download_no_player)) }
                    } catch (e: Throwable) {
                        val reason =
                            e.localizedMessage?.takeIf { it.isNotBlank() } ?: e.javaClass.simpleName
                        message.error {
                            Text(stringResource(R.string.download_open_external_failed, reason))
                        }
                    }
                }
                else -> {
                    try {
                        state.player.setMediaItem(MediaItem.Builder().setUri(contentUri).build())
                        state.player.prepare()
                        fullscreen = true
                    } catch (e: Throwable) {
                        val reason =
                            e.localizedMessage?.takeIf { it.isNotBlank() } ?: e.javaClass.simpleName
                        message.error {
                            Text(stringResource(R.string.download_play_failed, reason))
                        }
                    }
                }
            }
        }
    }

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
                        IconButton(onClick = { showDirectoryDialog = true }) {
                            Icon(
                                imageVector = Icons.Outlined.Folder,
                                contentDescription =
                                    stringResource(R.string.download_directory_title),
                            )
                        }

                        Box {
                            IconButton(onClick = { showSortMenu = true }) {
                                Icon(
                                    imageVector = Icons.Outlined.Sort,
                                    contentDescription = stringResource(R.string.download_sort),
                                )
                            }
                            DropdownMenu(
                                expanded = showSortMenu,
                                onDismissRequest = { showSortMenu = false },
                            ) {
                                val currentSortMode = DownloadSortMode.fromKey(sortKey)
                                DropdownMenuItem(
                                    text = { Text(stringResource(R.string.download_sort_time)) },
                                    trailingIcon = {
                                        if (currentSortMode == DownloadSortMode.TIME) {
                                            Icon(Icons.Outlined.Check, contentDescription = null)
                                        }
                                    },
                                    onClick = {
                                        sortKey = DownloadSortMode.TIME.key
                                        showSortMenu = false
                                    },
                                )
                                DropdownMenuItem(
                                    text = { Text(stringResource(R.string.download_sort_name)) },
                                    trailingIcon = {
                                        if (currentSortMode == DownloadSortMode.NAME) {
                                            Icon(Icons.Outlined.Check, contentDescription = null)
                                        }
                                    },
                                    onClick = {
                                        sortKey = DownloadSortMode.NAME.key
                                        showSortMenu = false
                                    },
                                )
                                DropdownMenuItem(
                                    text = { Text(stringResource(R.string.download_sort_size)) },
                                    trailingIcon = {
                                        if (currentSortMode == DownloadSortMode.SIZE) {
                                            Icon(Icons.Outlined.Check, contentDescription = null)
                                        }
                                    },
                                    onClick = {
                                        sortKey = DownloadSortMode.SIZE.key
                                        showSortMenu = false
                                    },
                                )
                                Divider()
                                DropdownMenuItem(
                                    text = {
                                        Text(stringResource(R.string.download_group_authors))
                                    },
                                    trailingIcon = {
                                        Checkbox(checked = groupByAuthors, onCheckedChange = null)
                                    },
                                    onClick = {
                                        groupByAuthors = !groupByAuthors
                                        showSortMenu = false
                                    },
                                )
                            }
                        }
                    },
                    scrollBehavior = appBarScrollBehavior,
                )
            }
        ) { innerPadding ->
            UiStateBox(
                modifier = Modifier.fillMaxSize(),
                state = vm.state.uiState,
                emptyStatus = {
                    val composition by
                        rememberLottieComposition(LottieCompositionSpec.RawRes(R.raw.coffee))
                    val progress by
                        animateLottieCompositionAsState(
                            composition = composition,
                            iterations = LottieConstants.IterateForever,
                        )
                    Column(
                        modifier = Modifier.padding(innerPadding).align(Alignment.Center),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        LottieAnimation(
                            composition = composition,
                            progress = { progress },
                            modifier = Modifier.size(200.dp),
                            contentScale = ContentScale.Crop,
                        )
                        Text(
                            text = stringResource(R.string.download_empty),
                            style = MaterialTheme.typography.titleLarge,
                        )
                    }
                },
                onErrorRetry = vm::loadDownloadedItems,
            ) {
                LazyColumn(
                    contentPadding = innerPadding + PaddingValues(8.dp),
                    modifier =
                        Modifier.fillMaxSize()
                            .nestedScroll(appBarScrollBehavior.nestedScrollConnection),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    if (groupByAuthors) {
                        authorGroups.forEach { group ->
                            item(
                                key = "author_group_${group.groupKey}",
                                contentType = "author_group_header",
                            ) {
                                val authorLabel =
                                    group.authorName.ifBlank {
                                        stringResource(R.string.download_unknown_author)
                                    }
                                Text(
                                    text =
                                        stringResource(
                                            R.string.download_author_group,
                                            authorLabel,
                                            group.items.size,
                                        ),
                                    style = MaterialTheme.typography.titleMedium,
                                    color = MaterialTheme.colorScheme.primary,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    modifier =
                                        Modifier.fillMaxWidth()
                                            .padding(horizontal = 4.dp, vertical = 4.dp),
                                )
                            }
                            items(
                                items = group.items,
                                key = { item -> item.id },
                                contentType = { "download_item" },
                            ) { item ->
                                DownloadItem(
                                    item = item,
                                    onDelete = { vm.delete(item) },
                                    onClick = { playDownloadedItem(item) },
                                )
                            }
                        }
                    } else {
                        items(
                            items = sortedItems,
                            key = { item -> item.id },
                            contentType = { "download_item" },
                        ) { item ->
                            DownloadItem(
                                item = item,
                                onDelete = { vm.delete(item) },
                                onClick = { playDownloadedItem(item) },
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun DownloadItem(item: DownloadItem, onDelete: () -> Unit, onClick: () -> Unit) {
    val fileSize = prettyFileSize(item.sizeBytes)
    val displayTitle = item.title.ifBlank { item.resourceId }
    val dismissState = rememberDismissState()
    val scope = rememberCoroutineScope()
    // 滑动只展开操作，点击删除按钮才移除文件。
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
                                text = displayTitle,
                                style = MaterialTheme.typography.titleLarge,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )

                            Text(
                                text = item.time.toLocalDateTimeString(),
                                style = MaterialTheme.typography.labelMedium,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )

                            Text(
                                text = fileSize,
                                style = MaterialTheme.typography.labelMedium,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                }
            }
        },
    )
}
