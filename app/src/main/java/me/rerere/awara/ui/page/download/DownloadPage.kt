package me.rerere.awara.ui.page.download

import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.Intent
import android.content.pm.ActivityInfo
import android.net.Uri
import android.util.Log
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ArrowBack
import androidx.compose.material.icons.outlined.ArrowDownward
import androidx.compose.material.icons.outlined.ArrowUpward
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.FitScreen
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.outlined.Fullscreen
import androidx.compose.material.icons.outlined.FullscreenExit
import androidx.compose.material.icons.outlined.KeyboardArrowRight
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material.icons.outlined.Person
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material.icons.outlined.Sort
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Divider
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
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
import me.rerere.awara.ui.component.iwara.DownloadDirectoryDialog
import me.rerere.awara.ui.component.player.Player
import me.rerere.awara.ui.component.player.PlayerScaffold
import me.rerere.awara.ui.component.player.PlayerState
import me.rerere.awara.ui.component.player.rememberPlayerState
import me.rerere.awara.ui.hooks.rememberRequestedScreenOrientation
import me.rerere.awara.util.prettyFileSize
import me.rerere.awara.util.toLocalDateString
import me.rerere.compose_setting.preference.rememberBooleanPreference
import me.rerere.compose_setting.preference.rememberStringPreference
import org.koin.androidx.compose.koinViewModel
import org.koin.compose.koinInject

@Composable
fun DownloadPage(vm: DownloadVM = koinViewModel()) {
    val downloadedItems = vm.state.items
    val downloadStorage: DownloadStorage = koinInject()
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
    var sortAscending by
        rememberBooleanPreference(
            key = PREFERENCE_KEY_DOWNLOAD_SORT_ASCENDING,
            default = DownloadSortMode.fromKey(sortKey).defaultAscending,
        )
    var groupByAuthors by
        rememberBooleanPreference(key = PREFERENCE_KEY_DOWNLOAD_GROUP_AUTHORS, default = false)

    // 选中作者ID：null 表示在根目录，"" 表示未知作者分组
    var selectedAuthorId by rememberSaveable { mutableStateOf<String?>(null) }

    LaunchedEffect(groupByAuthors) {
        if (!groupByAuthors) {
            selectedAuthorId = null
        }
    }

    var showDirectoryDialog by remember { mutableStateOf(false) }
    var showSortMenu by remember { mutableStateOf(false) }

    val sortedItems =
        remember(downloadedItems, sortKey, sortAscending) {
            sortDownloadedItems(downloadedItems, sortKey, sortAscending)
        }
    val authorGroups =
        remember(downloadedItems, sortKey, sortAscending, groupByAuthors) {
            if (groupByAuthors) {
                groupDownloadedItemsByAuthor(downloadedItems, sortKey, sortAscending)
            } else {
                emptyList()
            }
        }
    val selectedGroup =
        remember(authorGroups, selectedAuthorId) {
            authorGroups.firstOrNull { it.authorId == selectedAuthorId }
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

    BackHandler(!fullscreen && selectedAuthorId != null) { selectedAuthorId = null }

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
                navigationIcon = {
                    if (fullscreen) {
                        IconButton(onClick = { exitFullScreen() }) {
                            Icon(Icons.Outlined.ArrowBack, contentDescription = null)
                        }
                    } else {
                        BackButton()
                    }
                },
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
    ) {
        Scaffold(
            topBar = {
                TopAppBar(
                    title = {
                        val titleText =
                            if (selectedAuthorId != null) {
                                selectedGroup?.authorName?.ifBlank { null }
                                    ?: stringResource(R.string.download_unknown_author)
                            } else {
                                stringResource(R.string.download_completed)
                            }
                        Text(text = titleText, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    },
                    navigationIcon = {
                        if (selectedAuthorId != null) {
                            IconButton(onClick = { selectedAuthorId = null }) {
                                Icon(
                                    imageVector = Icons.Outlined.ArrowBack,
                                    contentDescription = stringResource(R.string.download_back),
                                )
                            }
                        } else {
                            BackButton()
                        }
                    },
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
                                DownloadSortMode.values().forEach { mode ->
                                    val isSelected = currentSortMode == mode
                                    val modeLabel =
                                        when (mode) {
                                            DownloadSortMode.TIME ->
                                                stringResource(R.string.download_sort_time)
                                            DownloadSortMode.NAME ->
                                                stringResource(R.string.download_sort_name)
                                            DownloadSortMode.SIZE ->
                                                stringResource(R.string.download_sort_size)
                                        }
                                    DropdownMenuItem(
                                        text = { Text(modeLabel) },
                                        trailingIcon = {
                                            if (isSelected) {
                                                val directionText =
                                                    stringResource(
                                                        if (sortAscending)
                                                            R.string.download_sort_ascending
                                                        else R.string.download_sort_descending
                                                    )
                                                Row(
                                                    verticalAlignment = Alignment.CenterVertically,
                                                    horizontalArrangement =
                                                        Arrangement.spacedBy(4.dp),
                                                ) {
                                                    Text(
                                                        text = directionText,
                                                        style =
                                                            MaterialTheme.typography.labelMedium,
                                                        color = MaterialTheme.colorScheme.primary,
                                                    )
                                                    Icon(
                                                        imageVector =
                                                            if (sortAscending)
                                                                Icons.Outlined.ArrowUpward
                                                            else Icons.Outlined.ArrowDownward,
                                                        contentDescription = directionText,
                                                        tint = MaterialTheme.colorScheme.primary,
                                                    )
                                                }
                                            }
                                        },
                                        onClick = {
                                            if (isSelected) {
                                                sortAscending = !sortAscending
                                            } else {
                                                sortKey = mode.key
                                                sortAscending = mode.defaultAscending
                                            }
                                            showSortMenu = false
                                        },
                                    )
                                }
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
                )
            }
        ) { innerPadding ->
            UiStateBox(
                modifier = Modifier.padding(innerPadding).fillMaxSize(),
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
                        modifier = Modifier.align(Alignment.Center),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        LottieAnimation(
                            composition = composition,
                            progress = { progress },
                            modifier = Modifier.size(200.dp),
                            contentScale = ContentScale.Crop,
                        )
                        Text(
                            text =
                                if (selectedAuthorId != null) {
                                    stringResource(R.string.download_folder_empty)
                                } else {
                                    stringResource(R.string.download_empty)
                                },
                            style = MaterialTheme.typography.titleLarge,
                        )
                    }
                },
                onErrorRetry = vm::loadDownloadedItems,
            ) {
                if (groupByAuthors && selectedAuthorId == null) {
                    LazyColumn(
                        contentPadding = PaddingValues(vertical = 8.dp),
                        modifier = Modifier.fillMaxSize(),
                        verticalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        items(
                            items = authorGroups,
                            key = { group -> "author_folder_${group.groupKey}" },
                            contentType = { "author_folder" },
                        ) { group ->
                            AuthorFolderRow(
                                group = group,
                                onClick = { selectedAuthorId = group.authorId },
                            )
                        }
                    }
                } else if (groupByAuthors && selectedAuthorId != null) {
                    val folderItems = selectedGroup?.items.orEmpty()
                    if (folderItems.isEmpty()) {
                        Box(
                            modifier = Modifier.fillMaxSize(),
                            contentAlignment = Alignment.Center,
                        ) {
                            Text(
                                text = stringResource(R.string.download_folder_empty),
                                style = MaterialTheme.typography.bodyLarge,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    } else {
                        LazyColumn(
                            contentPadding = PaddingValues(vertical = 8.dp),
                            modifier = Modifier.fillMaxSize(),
                            verticalArrangement = Arrangement.spacedBy(4.dp),
                        ) {
                            items(
                                items = folderItems,
                                key = { item -> item.id },
                                contentType = { "download_item" },
                            ) { item ->
                                DownloadItemRow(
                                    item = item,
                                    onDelete = { vm.delete(item) },
                                    onClick = { playDownloadedItem(item) },
                                )
                            }
                        }
                    }
                } else {
                    LazyColumn(
                        contentPadding = PaddingValues(vertical = 8.dp),
                        modifier = Modifier.fillMaxSize(),
                        verticalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        items(
                            items = sortedItems,
                            key = { item -> item.id },
                            contentType = { "download_item" },
                        ) { item ->
                            DownloadItemRow(
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
fun AuthorFolderRow(group: DownloadAuthorGroup, onClick: () -> Unit) {
    val displayName = group.authorName.ifBlank { stringResource(R.string.download_unknown_author) }
    val statsText =
        stringResource(
            R.string.download_author_folder_stats,
            group.count,
            prettyFileSize(group.totalBytes),
            group.latestDownloadTime.toLocalDateString(),
        )

    Row(
        modifier =
            Modifier.fillMaxWidth()
                .clickable(onClick = onClick)
                .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (group.avatar.isNotBlank()) {
            AsyncImage(
                model = group.avatar,
                contentDescription = null,
                modifier = Modifier.size(48.dp).clip(CircleShape),
                contentScale = ContentScale.Crop,
            )
        } else {
            Box(
                modifier =
                    Modifier.size(48.dp)
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.surfaceVariant),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = Icons.Outlined.Person,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(28.dp),
                )
            }
        }

        Column(
            modifier = Modifier.weight(1f).padding(start = 16.dp, end = 8.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text(
                text = displayName,
                style = MaterialTheme.typography.titleMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = statsText,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }

        IconButton(onClick = onClick, modifier = Modifier.size(48.dp)) {
            Icon(
                imageVector = Icons.Outlined.KeyboardArrowRight,
                contentDescription = stringResource(R.string.download_author_folder_open),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun DownloadItemRow(item: DownloadItem, onDelete: () -> Unit, onClick: () -> Unit) {
    val displayTitle = item.title.ifBlank { item.resourceId }
    var showMenu by remember { mutableStateOf(false) }
    var showDeleteConfirm by remember { mutableStateOf(false) }

    if (showDeleteConfirm) {
        AlertDialog(
            onDismissRequest = { showDeleteConfirm = false },
            title = { Text(text = stringResource(R.string.download_delete_confirm_title)) },
            text = {
                Text(text = stringResource(R.string.download_delete_confirm_message, displayTitle))
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        showDeleteConfirm = false
                        onDelete()
                    }
                ) {
                    Text(
                        text = stringResource(R.string.download_delete),
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteConfirm = false }) {
                    Text(stringResource(R.string.cancel))
                }
            },
        )
    }

    BoxWithConstraints(modifier = Modifier.fillMaxWidth()) {
        val thumbWidth = if (maxWidth >= 600.dp) 132.dp else 112.dp

        Row(
            modifier =
                Modifier.fillMaxWidth()
                    .clickable(onClick = onClick)
                    .padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            AsyncImage(
                model = item.thumbnail,
                contentDescription = null,
                modifier =
                    Modifier.width(thumbWidth).aspectRatio(16f / 9f).clip(RoundedCornerShape(8.dp)),
                contentScale = ContentScale.Crop,
            )

            Column(
                modifier = Modifier.weight(1f).padding(horizontal = 12.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Text(
                    text = displayTitle,
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis,
                )

                if (item.authorName.isNotBlank()) {
                    Text(
                        text = item.authorName.trim(),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }

                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    DownloadBadge(prettyFileSize(item.sizeBytes))
                    DownloadBadge(item.time.toLocalDateString())
                }
            }

            Box {
                IconButton(onClick = { showMenu = true }, modifier = Modifier.size(48.dp)) {
                    Icon(
                        imageVector = Icons.Outlined.MoreVert,
                        contentDescription = stringResource(R.string.download_more_actions),
                    )
                }

                DropdownMenu(expanded = showMenu, onDismissRequest = { showMenu = false }) {
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.download_play)) },
                        leadingIcon = { Icon(Icons.Outlined.PlayArrow, contentDescription = null) },
                        onClick = {
                            showMenu = false
                            onClick()
                        },
                    )
                    DropdownMenuItem(
                        text = {
                            Text(
                                text = stringResource(R.string.download_delete),
                                color = MaterialTheme.colorScheme.error,
                            )
                        },
                        leadingIcon = {
                            Icon(
                                imageVector = Icons.Outlined.Delete,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.error,
                            )
                        },
                        onClick = {
                            showMenu = false
                            showDeleteConfirm = true
                        },
                    )
                }
            }
        }
    }
}

@Composable
private fun DownloadBadge(text: String) {
    Box(
        modifier =
            Modifier.clip(RoundedCornerShape(4.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant)
                .padding(horizontal = 6.dp, vertical = 2.dp)
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}
