package me.rerere.awara.ui.component.common

import android.Manifest
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.SystemUpdate
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Divider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.surfaceColorAtElevation
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.ExperimentalTextApi
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withAnnotation
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import me.rerere.awara.R
import me.rerere.awara.data.download.AppUpdateEnqueueOutcome
import me.rerere.awara.data.download.CompletedAppUpdate
import me.rerere.awara.data.download.DownloadManager
import me.rerere.awara.data.download.DownloadNotifications
import me.rerere.awara.data.download.DownloadStorage
import me.rerere.awara.data.download.appUpdateResourceId
import me.rerere.awara.data.download.canRequestPackageInstalls
import me.rerere.awara.data.download.createUnknownSourcesSettingsIntent
import me.rerere.awara.data.download.isTrustedAppUpdateUri
import me.rerere.awara.data.download.launchApkInstaller
import me.rerere.awara.data.source.AppUpdateInfo
import me.rerere.awara.data.source.normalizeVersionTag
import me.rerere.awara.ui.LocalMessageProvider
import me.rerere.awara.ui.component.iwara.findLinkAnnotationAt
import org.koin.compose.koinInject

private const val LINK_ANNOTATION_TAG = "link"

/**
 * 记住一个统一的应用内更新下载触发器，处理通知权限校验、防重入与下载管理入队反馈。
 *
 * 供首页自动更新弹窗与设置页版本信息弹窗复用，保证行为一致。
 */
@Composable
fun rememberAppUpdateDownloader(
    downloadManager: DownloadManager = koinInject(),
): (AppUpdateInfo) -> Unit {
    val context = LocalContext.current
    val message = LocalMessageProvider.current
    val scope = rememberCoroutineScope()
    var enqueueing by remember { mutableStateOf(false) }
    var pendingAfterPermission by remember { mutableStateOf<AppUpdateInfo?>(null) }

    fun launchEnqueue(updateInfo: AppUpdateInfo) {
        if (enqueueing) return
        if (!NotificationManagerCompat.from(context).areNotificationsEnabled()) {
            val disabledText = context.getString(R.string.notification_is_not_enabled)
            message.error { Text(disabledText) }
            return
        }
        enqueueing = true
        scope.launch {
            try {
                when (downloadManager.enqueueAppUpdate(updateInfo)) {
                    AppUpdateEnqueueOutcome.STARTED -> {
                        val startedText = context.getString(R.string.update_download_started)
                        message.info { Text(startedText) }
                    }
                    AppUpdateEnqueueOutcome.RESUMED -> {
                        val resumedText = context.getString(R.string.update_download_resumed)
                        message.info { Text(resumedText) }
                    }
                    AppUpdateEnqueueOutcome.ALREADY_ACTIVE -> {
                        val activeText = context.getString(R.string.update_download_already_running)
                        message.info { Text(activeText) }
                    }
                    AppUpdateEnqueueOutcome.ALREADY_DOWNLOADED -> Unit
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                val failedText =
                    context.getString(
                        R.string.download_enqueue_failed,
                        error.message ?: error.javaClass.simpleName,
                    )
                message.error { Text(failedText) }
            } finally {
                enqueueing = false
            }
        }
    }

    val notificationPermissionLauncher =
        rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            val pending = pendingAfterPermission
            pendingAfterPermission = null
            if (granted && pending != null) {
                launchEnqueue(pending)
            } else if (!granted) {
                val permissionText =
                    context.getString(R.string.please_grant_notification_permission)
                message.error { Text(permissionText) }
            }
        }

    return { updateInfo ->
        if (updateInfo.apkAsset == null) {
            val unavailableText = context.getString(R.string.update_no_compatible_apk)
            message.error { Text(unavailableText) }
        } else if (
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                ContextCompat.checkSelfPermission(
                    context,
                    Manifest.permission.POST_NOTIFICATIONS,
                ) != PackageManager.PERMISSION_GRANTED
        ) {
            pendingAfterPermission = updateInfo
            notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        } else {
            launchEnqueue(updateInfo)
        }
    }
}

/**
 * 挂载于主 Activity 的应用更新安装宿主组件。
 *
 * 响应用户主动点击完成横幅通知、下载管理页「安装」按钮或已下载版本的更新入口：
 * 校验安装包存在性；若尚未授予“安装未知应用”权限，则先弹窗引导前往系统设置页，并在授权返回后继续安装。
 */
@Composable
fun AppUpdateInstallHost(
    externalRequest: CompletedAppUpdate?,
    onExternalRequestConsumed: () -> Unit,
    downloadManager: DownloadManager = koinInject(),
    downloadStorage: DownloadStorage = koinInject(),
) {
    val context = LocalContext.current
    val message = LocalMessageProvider.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val scope = rememberCoroutineScope()
    val queuedUpdate by downloadManager.pendingAppUpdateInstall.collectAsState()
    var isResumed by remember {
        mutableStateOf(lifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED))
    }
    var permissionPromptUpdate by remember { mutableStateOf<CompletedAppUpdate?>(null) }
    var awaitingPermissionResult by remember { mutableStateOf<CompletedAppUpdate?>(null) }

    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_RESUME -> isResumed = true
                Lifecycle.Event.ON_PAUSE -> isResumed = false
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    fun consumeRequest(update: CompletedAppUpdate) {
        downloadManager.consumePendingAppUpdateInstall(update.apkUriString)
        if (externalRequest?.apkUriString == update.apkUriString) {
            onExternalRequestConsumed()
        }
    }

    fun handleMissingApk(update: CompletedAppUpdate) {
        consumeRequest(update)
        scope.launch { downloadManager.pruneMissingCompletedAppUpdate(update.apkUriString) }
        val missingText = context.getString(R.string.update_install_file_missing)
        message.error { Text(missingText) }
    }

    suspend fun verifyReadyApk(update: CompletedAppUpdate): Uri? {
        val apkUri = runCatching { Uri.parse(update.apkUriString) }.getOrNull() ?: return null
        if (!isTrustedAppUpdateUri(context, apkUri)) return null
        val exists =
            withContext(Dispatchers.IO) {
                runCatching { downloadStorage.exists(apkUri) }.getOrDefault(false)
            }
        return apkUri.takeIf { exists }
    }

    fun launchSystemInstall(update: CompletedAppUpdate, apkUri: Uri) {
        consumeRequest(update)
        DownloadNotifications.cancelSuccessNotification(context, appUpdateResourceId(update.version))
        if (!launchApkInstaller(context, apkUri)) {
            val failedText = context.getString(R.string.update_install_failed)
            message.error { Text(failedText) }
        }
    }

    val unknownSourcesLauncher =
        rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) {
            val pending = awaitingPermissionResult ?: return@rememberLauncherForActivityResult
            awaitingPermissionResult = null
            if (canRequestPackageInstalls(context)) {
                scope.launch {
                    val verifiedUri = verifyReadyApk(pending)
                    if (verifiedUri != null) {
                        launchSystemInstall(pending, verifiedUri)
                    } else {
                        handleMissingApk(pending)
                    }
                }
            } else {
                consumeRequest(pending)
            }
        }

    val activeRequest = externalRequest ?: queuedUpdate
    LaunchedEffect(activeRequest, isResumed, permissionPromptUpdate, awaitingPermissionResult) {
        val request = activeRequest ?: return@LaunchedEffect
        if (!isResumed || permissionPromptUpdate != null || awaitingPermissionResult != null) {
            return@LaunchedEffect
        }
        val verifiedUri = verifyReadyApk(request)
        if (verifiedUri == null) {
            handleMissingApk(request)
            return@LaunchedEffect
        }
        if (canRequestPackageInstalls(context)) {
            launchSystemInstall(request, verifiedUri)
        } else {
            permissionPromptUpdate = request
        }
    }

    permissionPromptUpdate?.let { pendingUpdate ->
        AlertDialog(
            onDismissRequest = {
                permissionPromptUpdate = null
                consumeRequest(pendingUpdate)
            },
            title = { Text(stringResource(R.string.update_install_permission_title)) },
            text = {
                Text(
                    stringResource(
                        R.string.update_install_permission_message,
                        pendingUpdate.version,
                    )
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        permissionPromptUpdate = null
                        awaitingPermissionResult = pendingUpdate
                        runCatching {
                                unknownSourcesLauncher.launch(
                                    createUnknownSourcesSettingsIntent(context.packageName)
                                )
                            }
                            .onFailure {
                                awaitingPermissionResult = null
                                consumeRequest(pendingUpdate)
                                val failedText = context.getString(R.string.update_install_failed)
                                message.error { Text(failedText) }
                            }
                    }
                ) {
                    Text(stringResource(R.string.update_install_permission_action))
                }
            },
            dismissButton = {
                TextButton(
                    onClick = {
                        permissionPromptUpdate = null
                        consumeRequest(pendingUpdate)
                    }
                ) {
                    Text(stringResource(R.string.cancel))
                }
            },
        )
    }
}

/**
 * 格式化更新弹窗顶部的版本跨度标签。
 *
 * 当存在有效当前版本且与目标版本不同时，拼接为 `v1.3.3 → v1.3.4`；否则仅展示目标版本号。
 */
internal fun formatUpdateVersionRange(
    currentVersion: String?,
    targetVersion: String,
): String {
    val normalizedTarget = normalizeVersionTag(targetVersion).ifEmpty { targetVersion.trim() }
    val normalizedCurrent = currentVersion?.let(::normalizeVersionTag).orEmpty()
    return if (normalizedCurrent.isNotEmpty() && normalizedCurrent != normalizedTarget) {
        "$normalizedCurrent → $normalizedTarget"
    } else {
        normalizedTarget
    }
}

/**
 * 应用更新弹窗，固定顶部版本标题与底部操作栏，中间更新日志区域支持结构化排版与垂直滚动。
 *
 * @param updateInfo 待展示的新版本元数据
 * @param currentVersion 当前本地版本名（用于渲染 `当前 → 目标` 版本跨度胶囊，可为空）
 * @param onDismiss 关闭弹窗（临时关闭，不写入忽略状态）
 * @param onDownload 点击“下载更新”时的回调
 * @param onLinkClick 点击更新日志中安全链接时的回调
 * @param onIgnoreVersion 点击“不再提醒此版本”时的回调；为 `null` 时隐藏该操作（例如手动检查已忽略版本）
 */
@Composable
fun AppUpdateDialog(
    updateInfo: AppUpdateInfo,
    currentVersion: String? = null,
    onDismiss: () -> Unit,
    onDownload: () -> Unit,
    onLinkClick: (String) -> Unit,
    onIgnoreVersion: (() -> Unit)? = null,
) {
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Box(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 28.dp),
            contentAlignment = Alignment.Center,
        ) {
            AppUpdateCard(
                updateInfo = updateInfo,
                currentVersion = currentVersion,
                onDismiss = onDismiss,
                onDownload = onDownload,
                onLinkClick = onLinkClick,
                onIgnoreVersion = onIgnoreVersion,
            )
        }
    }
}

/**
 * 应用更新卡片主体组件（无窗口包装），供 [AppUpdateDialog] 承载。
 */
@Composable
internal fun AppUpdateCard(
    updateInfo: AppUpdateInfo,
    currentVersion: String?,
    onDismiss: () -> Unit,
    onDownload: () -> Unit,
    onLinkClick: (String) -> Unit,
    onIgnoreVersion: (() -> Unit)?,
    modifier: Modifier = Modifier,
) {
    val containerColor = MaterialTheme.colorScheme.surfaceColorAtElevation(6.dp)
    val dividerColor = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.45f)
    val scrollbarColor = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.35f)
    val scrollState = rememberScrollState()
    val blocks = remember(updateInfo.changes) { parseReleaseNoteBlocks(updateInfo.changes) }
    val versionBadgeText =
        remember(currentVersion, updateInfo.version) {
            formatUpdateVersionRange(
                currentVersion = currentVersion,
                targetVersion = updateInfo.version,
            )
        }
    val hasTopOverflow = scrollState.value > 0
    val hasBottomOverflow =
        scrollState.maxValue in 1 until Int.MAX_VALUE && scrollState.value < scrollState.maxValue

    Surface(
        modifier = modifier.fillMaxWidth().widthIn(max = 420.dp),
        shape = RoundedCornerShape(24.dp),
        color = containerColor,
    ) {
        Column(modifier = Modifier.fillMaxWidth()) {
            UpdateDialogHeader(versionBadgeText = versionBadgeText)

            Divider(thickness = 0.5.dp, color = dividerColor)

            // 使用非填充权重让短内容贴合包裹、长内容占据剩余高度并在内部平滑滚动
            Box(
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .weight(1f, fill = false)
                        .verticalScrollbar(scrollState = scrollState, color = scrollbarColor),
            ) {
                Column(
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .verticalScroll(scrollState)
                            .padding(horizontal = 22.dp, vertical = 16.dp),
                ) {
                    if (blocks.isEmpty()) {
                        Text(
                            text = stringResource(R.string.update_no_changelog),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    } else {
                        ReleaseNotesBlockList(
                            blocks = blocks,
                            onLinkClick = onLinkClick,
                        )
                    }
                }

                if (hasTopOverflow) {
                    Box(
                        modifier =
                            Modifier
                                .fillMaxWidth()
                                .height(18.dp)
                                .align(Alignment.TopCenter)
                                .background(
                                    Brush.verticalGradient(
                                        colors = listOf(containerColor, Color.Transparent),
                                    )
                                ),
                    )
                }

                if (hasBottomOverflow) {
                    Box(
                        modifier =
                            Modifier
                                .fillMaxWidth()
                                .height(24.dp)
                                .align(Alignment.BottomCenter)
                                .background(
                                    Brush.verticalGradient(
                                        colors = listOf(Color.Transparent, containerColor),
                                    )
                                ),
                    )
                }
            }

            Divider(thickness = 0.5.dp, color = dividerColor)

            UpdateDialogActions(
                onDismiss = onDismiss,
                onDownload = onDownload,
                onIgnoreVersion = onIgnoreVersion,
            )
        }
    }
}

private fun Modifier.verticalScrollbar(scrollState: ScrollState, color: Color): Modifier =
    drawWithContent {
        drawContent()
        val maxScroll = scrollState.maxValue
        if (maxScroll !in 1 until Int.MAX_VALUE) return@drawWithContent
        val viewportHeight = size.height
        val totalHeight = viewportHeight + maxScroll
        val trackPadding = 10.dp.toPx()
        val trackHeight = (viewportHeight - trackPadding * 2f).coerceAtLeast(0f)
        if (trackHeight <= 0f || totalHeight <= 0f) return@drawWithContent

        val minThumbHeight = 24.dp.toPx().coerceAtMost(trackHeight)
        val thumbHeight =
            (viewportHeight / totalHeight * trackHeight).coerceIn(minThumbHeight, trackHeight)
        val progress = (scrollState.value.toFloat() / maxScroll.toFloat()).coerceIn(0f, 1f)
        val thumbTop = trackPadding + (trackHeight - thumbHeight) * progress
        val thumbWidth = 3.dp.toPx()
        val thumbLeft = size.width - thumbWidth - 6.dp.toPx()
        drawRoundRect(
            color = color,
            topLeft = Offset(thumbLeft, thumbTop),
            size = Size(thumbWidth, thumbHeight),
            cornerRadius = CornerRadius(thumbWidth / 2f, thumbWidth / 2f),
        )
    }

@Composable
private fun UpdateDialogHeader(versionBadgeText: String) {
    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .padding(start = 22.dp, top = 20.dp, end = 22.dp, bottom = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Box(
            modifier =
                Modifier
                    .size(42.dp)
                    .background(
                        color = MaterialTheme.colorScheme.primaryContainer,
                        shape = RoundedCornerShape(12.dp),
                    ),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = Icons.Outlined.SystemUpdate,
                contentDescription = null,
                modifier = Modifier.size(22.dp),
                tint = MaterialTheme.colorScheme.onPrimaryContainer,
            )
        }

        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Text(
                text = stringResource(R.string.update_dialog_title),
                style =
                    MaterialTheme.typography.titleLarge.copy(
                        fontSize = 19.sp,
                        fontWeight = FontWeight.SemiBold,
                        lineHeight = 24.sp,
                    ),
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )

            Surface(
                shape = RoundedCornerShape(50),
                color = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.85f),
            ) {
                Text(
                    text = versionBadgeText,
                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 3.dp),
                    style =
                        MaterialTheme.typography.labelMedium.copy(
                            fontWeight = FontWeight.Medium,
                            fontFeatureSettings = "tnum",
                        ),
                    color = MaterialTheme.colorScheme.onSecondaryContainer,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

@Composable
private fun UpdateDialogActions(
    onDismiss: () -> Unit,
    onDownload: () -> Unit,
    onIgnoreVersion: (() -> Unit)?,
) {
    Column(
        modifier =
            Modifier
                .fillMaxWidth()
                .padding(
                    start = 20.dp,
                    top = 14.dp,
                    end = 20.dp,
                    bottom = if (onIgnoreVersion != null) 8.dp else 16.dp,
                ),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            OutlinedButton(
                onClick = onDismiss,
                modifier = Modifier.weight(1f),
                shape = RoundedCornerShape(12.dp),
            ) {
                Text(
                    text = stringResource(R.string.close),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Button(
                onClick = onDownload,
                modifier = Modifier.weight(1f),
                shape = RoundedCornerShape(12.dp),
            ) {
                Text(
                    text = stringResource(R.string.update_confirm),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }

        if (onIgnoreVersion != null) {
            Spacer(modifier = Modifier.height(2.dp))
            TextButton(
                onClick = onIgnoreVersion,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(
                    text = stringResource(R.string.update_ignore_version),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

@Immutable
private data class ReleaseNoteInlinePalette(
    val bold: SpanStyle,
    val italic: SpanStyle,
    val code: SpanStyle,
    val link: SpanStyle,
)

@Composable
private fun rememberReleaseNoteInlinePalette(): ReleaseNoteInlinePalette {
    val colorScheme = MaterialTheme.colorScheme
    return remember(colorScheme) {
        ReleaseNoteInlinePalette(
            bold =
                SpanStyle(
                    fontWeight = FontWeight.SemiBold,
                    color = colorScheme.onSurface,
                ),
            italic = SpanStyle(fontStyle = FontStyle.Italic),
            code =
                SpanStyle(
                    fontFamily = FontFamily.Monospace,
                    fontSize = 12.5.sp,
                    color = colorScheme.onSecondaryContainer,
                    background = colorScheme.secondaryContainer.copy(alpha = 0.55f),
                ),
            link =
                SpanStyle(
                    color = colorScheme.primary,
                    textDecoration = TextDecoration.Underline,
                ),
        )
    }
}

@Composable
private fun ReleaseNotesBlockList(
    blocks: List<ReleaseNoteBlock>,
    onLinkClick: (String) -> Unit,
    nestedLevel: Int = 0,
) {
    val bodyStyle =
        MaterialTheme.typography.bodyMedium.copy(
            fontSize = 13.5.sp,
            lineHeight = 21.sp,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.86f),
        )
    val palette = rememberReleaseNoteInlinePalette()

    Column(modifier = Modifier.fillMaxWidth()) {
        blocks.forEachIndexed { index, block ->
            if (index > 0) {
                val spacing =
                    when (block) {
                        is ReleaseNoteBlock.Heading -> 16.dp
                        is ReleaseNoteBlock.Divider -> 10.dp
                        else -> 8.dp
                    }
                Spacer(modifier = Modifier.height(spacing))
            }

            when (block) {
                is ReleaseNoteBlock.Heading -> {
                    ReleaseNoteHeadingView(
                        block = block,
                        palette = palette,
                        onLinkClick = onLinkClick,
                    )
                }

                is ReleaseNoteBlock.Paragraph -> {
                    ReleaseNoteRichText(
                        spans = block.spans,
                        textStyle = bodyStyle,
                        palette = palette,
                        onLinkClick = onLinkClick,
                    )
                }

                is ReleaseNoteBlock.BulletList -> {
                    ReleaseNoteListView(
                        items = block.items,
                        bodyStyle = bodyStyle,
                        palette = palette,
                        onLinkClick = onLinkClick,
                        nestedLevel = nestedLevel,
                    )
                }

                is ReleaseNoteBlock.Quote -> {
                    Row(
                        modifier =
                            Modifier
                                .fillMaxWidth()
                                .height(IntrinsicSize.Min)
                                .padding(vertical = 2.dp),
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        Box(
                            modifier =
                                Modifier
                                    .width(3.dp)
                                    .fillMaxHeight()
                                    .background(
                                        color = MaterialTheme.colorScheme.outlineVariant,
                                        shape = RoundedCornerShape(2.dp),
                                    ),
                        )
                        Box(modifier = Modifier.weight(1f)) {
                            ReleaseNotesBlockList(
                                blocks = block.blocks,
                                onLinkClick = onLinkClick,
                                nestedLevel = nestedLevel + 1,
                            )
                        }
                    }
                }

                is ReleaseNoteBlock.CodeBlock -> {
                    Surface(
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(10.dp),
                        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f),
                    ) {
                        Text(
                            text = block.code,
                            modifier =
                                Modifier
                                    .fillMaxWidth()
                                    .horizontalScroll(rememberScrollState())
                                    .padding(horizontal = 12.dp, vertical = 10.dp),
                            style =
                                MaterialTheme.typography.bodySmall.copy(
                                    fontFamily = FontFamily.Monospace,
                                    fontSize = 12.sp,
                                    lineHeight = 18.sp,
                                ),
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }

                ReleaseNoteBlock.Divider -> {
                    Divider(
                        modifier = Modifier.padding(vertical = 4.dp),
                        thickness = 0.5.dp,
                        color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.45f),
                    )
                }
            }
        }
    }
}

@Composable
private fun ReleaseNoteHeadingView(
    block: ReleaseNoteBlock.Heading,
    palette: ReleaseNoteInlinePalette,
    onLinkClick: (String) -> Unit,
) {
    val headingStyle =
        if (block.level <= 2) {
            MaterialTheme.typography.titleMedium.copy(
                fontSize = 15.5.sp,
                lineHeight = 22.sp,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface,
            )
        } else {
            MaterialTheme.typography.titleSmall.copy(
                fontSize = 14.sp,
                lineHeight = 20.sp,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface,
            )
        }
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Box(
            modifier =
                Modifier
                    .width(3.dp)
                    .height(if (block.level <= 2) 16.dp else 14.dp)
                    .background(
                        color = MaterialTheme.colorScheme.primary,
                        shape = RoundedCornerShape(2.dp),
                    ),
        )
        ReleaseNoteRichText(
            spans = block.spans,
            textStyle = headingStyle,
            palette = palette,
            onLinkClick = onLinkClick,
            modifier = Modifier.weight(1f),
        )
    }
}

@Composable
private fun ReleaseNoteListView(
    items: List<ReleaseNoteListItem>,
    bodyStyle: TextStyle,
    palette: ReleaseNoteInlinePalette,
    onLinkClick: (String) -> Unit,
    nestedLevel: Int,
) {
    val indentPadding = if (nestedLevel > 0) 14.dp else 0.dp
    Column(
        modifier = Modifier.fillMaxWidth().padding(start = indentPadding),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        items.forEach { item ->
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalAlignment = Alignment.Top,
            ) {
                if (item.marker == null) {
                    Box(
                        modifier =
                            Modifier
                                .padding(top = 8.dp)
                                .size(5.dp)
                                .background(
                                    color =
                                        MaterialTheme.colorScheme.primary.copy(
                                            alpha = if (nestedLevel == 0) 0.8f else 0.55f,
                                        ),
                                    shape = CircleShape,
                                ),
                    )
                } else {
                    Text(
                        text = item.marker,
                        style =
                            bodyStyle.copy(
                                fontWeight = FontWeight.SemiBold,
                                fontFeatureSettings = "tnum",
                                color = MaterialTheme.colorScheme.primary,
                            ),
                    )
                }

                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    if (item.spans.isNotEmpty()) {
                        ReleaseNoteRichText(
                            spans = item.spans,
                            textStyle = bodyStyle,
                            palette = palette,
                            onLinkClick = onLinkClick,
                        )
                    }
                    if (item.children.isNotEmpty()) {
                        ReleaseNotesBlockList(
                            blocks = item.children,
                            onLinkClick = onLinkClick,
                            nestedLevel = nestedLevel + 1,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun ReleaseNoteRichText(
    spans: List<ReleaseNoteSpan>,
    textStyle: TextStyle,
    palette: ReleaseNoteInlinePalette,
    onLinkClick: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val annotatedString =
        remember(spans, palette) {
            buildAnnotatedString { appendReleaseNoteSpans(spans, palette) }
        }
    val hasLinks =
        remember(annotatedString) {
            annotatedString.getStringAnnotations(LINK_ANNOTATION_TAG, 0, annotatedString.length)
                .isNotEmpty()
        }
    val layoutResult = remember { mutableStateOf<TextLayoutResult?>(null) }

    // 仅在包含安全链接时挂载轻击识别；垂直拖动由外层 verticalScroll 接管，不产生手势冲突
    val gestureModifier =
        if (hasLinks) {
            modifier.pointerInput(annotatedString, onLinkClick) {
                detectTapGestures { offset ->
                    layoutResult.value?.let { layout ->
                        findLinkAnnotationAt(offset, layout, annotatedString)?.let(onLinkClick)
                    }
                }
            }
        } else {
            modifier
        }

    Text(
        text = annotatedString,
        style = textStyle,
        modifier = gestureModifier,
        onTextLayout = { layoutResult.value = it },
    )
}

@OptIn(ExperimentalTextApi::class)
private fun AnnotatedString.Builder.appendReleaseNoteSpans(
    spans: List<ReleaseNoteSpan>,
    palette: ReleaseNoteInlinePalette,
) {
    for (span in spans) {
        when (span) {
            is ReleaseNoteSpan.Text -> append(span.text)
            is ReleaseNoteSpan.Bold -> {
                withStyle(palette.bold) { appendReleaseNoteSpans(span.children, palette) }
            }
            is ReleaseNoteSpan.Italic -> {
                withStyle(palette.italic) { appendReleaseNoteSpans(span.children, palette) }
            }
            is ReleaseNoteSpan.Code -> {
                // 前后添加窄空格为行内代码背景块提供轻微呼吸边距
                withStyle(palette.code) { append("\u2009${span.code}\u2009") }
            }
            is ReleaseNoteSpan.Link -> {
                withStyle(palette.link) {
                    withAnnotation(LINK_ANNOTATION_TAG, span.url) { append(span.text) }
                }
            }
        }
    }
}
