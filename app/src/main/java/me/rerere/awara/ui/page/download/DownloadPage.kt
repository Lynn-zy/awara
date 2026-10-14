package me.rerere.awara.ui.page.download

import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.Intent
import android.content.pm.ActivityInfo
import android.net.Uri
import android.util.Log
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
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
import androidx.compose.material3.TriStateCheckbox
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
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.semantics.toggleableState
import androidx.compose.ui.state.ToggleableState
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
import me.rerere.awara.ui.component.common.UiState
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

@OptIn(ExperimentalFoundationApi::class)
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
    // 多选集合：记录当前选中的下载记录 ID
    var selectedIds by rememberSaveable { mutableStateOf(emptySet<Int>()) }
    val isSelectionMode = selectedIds.isNotEmpty()

    LaunchedEffect(groupByAuthors) {
        if (!groupByAuthors) {
            selectedAuthorId = null
        }
    }

    var showDirectoryDialog by remember { mutableStateOf(false) }
    var showSortMenu by remember { mutableStateOf(false) }
    var showBatchDeleteConfirm by remember { mutableStateOf(false) }

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

    // 当前视图可见的视频列表
    val visibleItems =
        remember(groupByAuthors, selectedAuthorId, sortedItems, selectedGroup) {
            if (groupByAuthors) {
                if (selectedAuthorId != null) {
                    selectedGroup?.items.orEmpty()
                } else {
                    emptyList()
                }
            } else {
                sortedItems
            }
        }
    val visibleVideoIds = remember(visibleItems) { visibleItems.map { it.id }.toSet() }
    val selectedVisibleIds =
        remember(selectedIds, visibleVideoIds) { selectedIds.intersect(visibleVideoIds) }
    val allVisibleSelected = isAllVideosSelected(selectedIds, visibleVideoIds)
    val isDeleting = vm.state.deletingIds.isNotEmpty()

    LaunchedEffect(visibleVideoIds, vm.state.uiState, selectedAuthorId) {
        if (vm.state.uiState == UiState.Success || vm.state.uiState == UiState.Empty) {
            selectedIds = selectedIds.intersect(visibleVideoIds)
            if (groupByAuthors && selectedAuthorId != null && selectedGroup == null) {
                selectedAuthorId = null
            }
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

    if (showBatchDeleteConfirm) {
        val selectedItemsToDelete = visibleItems.filter { it.id in selectedVisibleIds }
        AlertDialog(
            onDismissRequest = { if (!isDeleting) showBatchDeleteConfirm = false },
            title = { Text(text = stringResource(R.string.download_delete_selected_title)) },
            text = {
                Text(
                    text =
                        stringResource(
                            R.string.download_delete_selected_message,
                            selectedItemsToDelete.size,
                        )
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        showBatchDeleteConfirm = false
                        vm.delete(selectedItemsToDelete) { successIds, _ ->
                            selectedIds = selectedIds - successIds
                        }
                    },
                    enabled = !isDeleting,
                ) {
                    Text(
                        text = stringResource(R.string.download_delete),
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            },
            dismissButton = {
                TextButton(onClick = { showBatchDeleteConfirm = false }, enabled = !isDeleting) {
                    Text(stringResource(R.string.cancel))
                }
            },
        )
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

    // 多选模式下按返回键优先退出选择，不退出作者目录或页面
    BackHandler(!fullscreen && isSelectionMode) { selectedIds = emptySet() }

    BackHandler(!fullscreen && !isSelectionMode && selectedAuthorId != null) {
        selectedAuthorId = null
    }

    fun playExternal(item: DownloadItem) {
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

            try {
                val mimeType =
                    withContext(Dispatchers.IO) {
                        context.contentResolver.getType(contentUri)?.takeIf {
                            it.startsWith("video/")
                        } ?: "video/*"
                    }
                val intent =
                    Intent(Intent.ACTION_VIEW).apply {
                        setDataAndType(contentUri, mimeType)
                        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                        clipData = ClipData.newRawUri(null, contentUri)
                    }
                context.startActivity(intent)
            } catch (_: ActivityNotFoundException) {
                message.error { Text(stringResource(R.string.download_no_player)) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                val reason =
                    e.localizedMessage?.takeIf { it.isNotBlank() } ?: e.javaClass.simpleName
                message.error {
                    Text(stringResource(R.string.download_open_external_failed, reason))
                }
            }
        }
    }

    fun playDownloadedItem(item: DownloadItem) {
        if (internalPlayer == "external") {
            playExternal(item)
            return
        }
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

            try {
                state.player.setMediaItem(MediaItem.Builder().setUri(contentUri).build())
                state.player.prepare()
                fullscreen = true
            } catch (e: Exception) {
                val reason =
                    e.localizedMessage?.takeIf { it.isNotBlank() } ?: e.javaClass.simpleName
                message.error { Text(stringResource(R.string.download_play_failed, reason)) }
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
                        if (isSelectionMode) {
                            Text(
                                text =
                                    stringResource(
                                        R.string.download_selection_count,
                                        selectedVisibleIds.size,
                                    ),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        } else {
                            val titleText =
                                if (selectedAuthorId != null) {
                                    selectedGroup?.authorName?.ifBlank { null }
                                        ?: stringResource(R.string.download_unknown_author)
                                } else {
                                    stringResource(R.string.download_completed)
                                }
                            Text(text = titleText, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                    },
                    navigationIcon = {
                        if (isSelectionMode) {
                            val selectAllDesc =
                                stringResource(
                                    if (allVisibleSelected) R.string.download_clear_selection
                                    else R.string.download_select_all
                                )
                            TriStateCheckbox(
                                state =
                                    when {
                                        allVisibleSelected -> ToggleableState.On
                                        selectedVisibleIds.isNotEmpty() ->
                                            ToggleableState.Indeterminate
                                        else -> ToggleableState.Off
                                    },
                                enabled = !isDeleting,
                                onClick = {
                                    selectedIds =
                                        if (!allVisibleSelected) {
                                            selectAllVideos(selectedIds, visibleVideoIds)
                                        } else {
                                            deselectAllVideos(selectedIds, visibleVideoIds)
                                        }
                                },
                                modifier = Modifier.semantics { contentDescription = selectAllDesc },
                            )
                        } else if (selectedAuthorId != null) {
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
                        if (isSelectionMode) {
                            IconButton(
                                onClick = { showBatchDeleteConfirm = true },
                                enabled = selectedVisibleIds.isNotEmpty() && !isDeleting,
                            ) {
                                Icon(
                                    imageVector = Icons.Outlined.Delete,
                                    contentDescription =
                                        stringResource(R.string.download_delete_selected),
                                    tint =
                                        if (selectedVisibleIds.isNotEmpty() && !isDeleting)
                                            MaterialTheme.colorScheme.error
                                        else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f),
                                )
                            }
                        } else {
                            IconButton(onClick = { showDirectoryDialog = true }) {
                                Icon(
                                    imageVector = Icons.Outlined.Folder,
                                    contentDescription =
                                        stringResource(R.string.download_directory_title),
                                )
                            }

                            Box {
                                IconButton(onClick = { showSortMenu = !showSortMenu }) {
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
                                                        verticalAlignment =
                                                            Alignment.CenterVertically,
                                                        horizontalArrangement =
                                                            Arrangement.spacedBy(4.dp),
                                                    ) {
                                                        Text(
                                                            text = directionText,
                                                            style =
                                                                MaterialTheme.typography
                                                                    .labelMedium,
                                                            color =
                                                                MaterialTheme.colorScheme.primary,
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
                                                selectedIds = emptySet()
                                                if (isSelected) {
                                                    sortAscending = !sortAscending
                                                } else {
                                                    sortKey = mode.key
                                                    sortAscending = mode.defaultAscending
                                                }
                                            },
                                        )
                                    }
                                    Divider()
                                    DropdownMenuItem(
                                        text = {
                                            Text(stringResource(R.string.download_group_authors))
                                        },
                                        trailingIcon = {
                                            Checkbox(
                                                checked = groupByAuthors,
                                                onCheckedChange = null,
                                            )
                                        },
                                        onClick = {
                                            groupByAuthors = !groupByAuthors
                                            selectedAuthorId = null
                                            selectedIds = emptySet()
                                        },
                                    )
                                }
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
                                onClick = {
                                    selectedAuthorId = group.authorId
                                    selectedIds = emptySet()
                                },
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
                                    isSelectionMode = isSelectionMode,
                                    isSelected = item.id in selectedIds,
                                    isDeleting = item.id in vm.state.deletingIds,
                                    onToggleSelect = {
                                        selectedIds = toggleSelection(selectedIds, item.id)
                                    },
                                    onDelete = { vm.delete(listOf(item)) },
                                    onClick = { playDownloadedItem(item) },
                                    onPlayExternal = { playExternal(item) },
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
                                isSelectionMode = isSelectionMode,
                                isSelected = item.id in selectedIds,
                                isDeleting = item.id in vm.state.deletingIds,
                                onToggleSelect = {
                                    selectedIds = toggleSelection(selectedIds, item.id)
                                },
                                onDelete = { vm.delete(listOf(item)) },
                                onClick = { playDownloadedItem(item) },
                                onPlayExternal = { playExternal(item) },
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

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun DownloadItemRow(
    item: DownloadItem,
    isSelectionMode: Boolean,
    isSelected: Boolean,
    isDeleting: Boolean,
    onToggleSelect: () -> Unit,
    onDelete: () -> Unit,
    onClick: () -> Unit,
    onPlayExternal: () -> Unit,
) {
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
        val thumbWidth = if (maxWidth >= 600.dp) 112.dp else 96.dp
        val rowBackground =
            if (isSelected) MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.5f)
            else MaterialTheme.colorScheme.surface
        val selectedStateDesc = stringResource(R.string.download_selected_accessibility)

        Row(
            modifier =
                Modifier.fillMaxWidth()
                    .background(rowBackground)
                    .alpha(if (isDeleting) 0.38f else 1f)
                    .combinedClickable(
                        enabled = !isDeleting,
                        role = if (isSelectionMode) Role.Checkbox else null,
                        onClick = {
                            if (isSelectionMode) {
                                onToggleSelect()
                            } else {
                                onClick()
                            }
                        },
                        onLongClick = {
                            if (!isSelectionMode) {
                                onToggleSelect()
                            }
                        },
                    )
                    .semantics {
                        if (isSelectionMode) {
                            stateDescription = if (isSelected) selectedStateDesc else ""
                            toggleableState =
                                if (isSelected) ToggleableState.On else ToggleableState.Off
                        }
                    }
                    .padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (isSelectionMode) {
                Checkbox(
                    checked = isSelected,
                    onCheckedChange = null,
                    modifier = Modifier.padding(end = 4.dp),
                )
            }

            AsyncImage(
                model = item.thumbnail,
                contentDescription = null,
                modifier =
                    Modifier.width(thumbWidth).aspectRatio(16f / 9f).clip(RoundedCornerShape(8.dp)),
                contentScale = ContentScale.Crop,
            )

            Column(
                modifier = Modifier.weight(1f).padding(start = 12.dp, end = 4.dp),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                Text(
                    text = displayTitle,
                    style = MaterialTheme.typography.bodyMedium,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )

                if (item.authorName.isNotBlank()) {
                    Text(
                        text = item.authorName.trim(),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text(
                        text = prettyFileSize(item.sizeBytes),
                        modifier = Modifier.weight(0.42f),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        text = item.time.toLocalDateString(),
                        modifier = Modifier.weight(0.58f),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }

            if (!isSelectionMode) {
                Box {
                    IconButton(
                        onClick = { showMenu = true },
                        enabled = !isDeleting,
                        modifier = Modifier.size(48.dp),
                    ) {
                        Icon(
                            imageVector = Icons.Outlined.MoreVert,
                            contentDescription = stringResource(R.string.download_more_actions),
                        )
                    }

                    DropdownMenu(expanded = showMenu, onDismissRequest = { showMenu = false }) {
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.download_play_external)) },
                            leadingIcon = {
                                Icon(Icons.Outlined.PlayArrow, contentDescription = null)
                            },
                            onClick = {
                                showMenu = false
                                onPlayExternal()
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
}

// 纯状态选择管理函数，便于测试与复用
fun toggleSelection(selectedIds: Set<Int>, id: Int): Set<Int> =
    if (id in selectedIds) selectedIds - id else selectedIds + id

fun selectAllVideos(selectedIds: Set<Int>, visibleIds: Set<Int>): Set<Int> =
    selectedIds + visibleIds

fun deselectAllVideos(selectedIds: Set<Int>, visibleIds: Set<Int>): Set<Int> =
    selectedIds - visibleIds

fun isAllVideosSelected(selectedIds: Set<Int>, visibleIds: Set<Int>): Boolean =
    visibleIds.isNotEmpty() && visibleIds.all { it in selectedIds }
