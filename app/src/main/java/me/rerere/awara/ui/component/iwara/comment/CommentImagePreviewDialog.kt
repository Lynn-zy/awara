package me.rerere.awara.ui.component.iwara.comment

import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import me.rerere.awara.R
import me.rerere.awara.ui.component.common.zoomable.rememberZoomState
import me.rerere.awara.ui.component.common.zoomable.zoomable
import okhttp3.OkHttpClient
import org.koin.compose.koinInject

/**
 * 评论图片全屏预览弹窗
 * - 采用系统全屏 Dialog，无 decorative 装饰卡片包裹
 * - 支持缩放拖动手势（复用已有 zoomable 手势）
 * - 提供关闭和本地保存图标
 * - 基于 ActivityResultContracts.CreateDocument 实现保存，无需申请新存储权限
 *
 * @param imageUrl 待预览与保存的图片网络地址
 * @param alt 图片替代文本/标题
 * @param onDismiss 弹窗关闭回调
 */
@Composable
fun CommentImagePreviewDialog(
    imageUrl: String,
    alt: String = "",
    onDismiss: () -> Unit,
    okHttpClient: OkHttpClient = koinInject(),
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var isSaving by remember { mutableStateOf(false) }
    var isChoosingFile by rememberSaveable { mutableStateOf(false) }

    val suggestedName = remember(imageUrl) { getSuggestedImageFileName(imageUrl) }
    val mimeType = remember(suggestedName) { getImageMimeType(suggestedName) }

    // 系统文件创建选择器，不新增存储权限
    val createDocumentLauncher =
        rememberLauncherForActivityResult(
            contract = ActivityResultContracts.CreateDocument(mimeType)
        ) { targetUri: Uri? ->
            isChoosingFile = false
            if (targetUri == null || isSaving) return@rememberLauncherForActivityResult
            isSaving = true
            scope.launch {
                try {
                    saveImageToUri(
                        context = context,
                        client = okHttpClient,
                        url = imageUrl,
                        targetUri = targetUri,
                    )
                    Toast.makeText(
                            context,
                            context.getString(R.string.comment_image_save_success),
                            Toast.LENGTH_SHORT,
                        )
                        .show()
                } catch (cancellation: CancellationException) {
                    throw cancellation
                } catch (e: Exception) {
                    val message =
                        when (e) {
                            is IllegalArgumentException ->
                                context.getString(R.string.comment_image_invalid_type)
                            is ImageTooLargeException ->
                                context.getString(R.string.comment_image_too_large)
                            else -> context.getString(R.string.comment_image_save_failed)
                        }
                    Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
                } finally {
                    isSaving = false
                }
            }
        }

    Dialog(
        onDismissRequest = onDismiss,
        properties =
            DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false),
    ) {
        Box(modifier = Modifier.fillMaxSize().background(Color.Black)) {
            // 图片主视区：ContentScale.Fit 并挂载缩放拖动手势
            val zoomState = rememberZoomState()
            CommentImageContent(
                imageUrl = imageUrl,
                alt = alt,
                modifier = Modifier.fillMaxSize().zoomable(zoomState),
            )

            // 顶部操作工具栏：关闭与保存图标
            Row(
                modifier =
                    Modifier.fillMaxWidth()
                        .background(Color.Black.copy(alpha = 0.45f))
                        .statusBarsPadding()
                        .padding(horizontal = 8.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(onClick = onDismiss) {
                    Icon(
                        imageVector = Icons.Outlined.Close,
                        contentDescription = stringResource(R.string.close),
                        tint = Color.White,
                    )
                }

                Text(
                    text = alt.ifBlank { stringResource(R.string.comment_image_preview) },
                    style = MaterialTheme.typography.titleMedium,
                    color = Color.White,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f).padding(horizontal = 8.dp),
                )

                IconButton(
                    onClick = {
                        if (!isSaving && !isChoosingFile) {
                            isChoosingFile = true
                            try {
                                createDocumentLauncher.launch(suggestedName)
                            } catch (exception: Exception) {
                                isChoosingFile = false
                                Toast.makeText(
                                        context,
                                        context.getString(R.string.comment_image_save_failed),
                                        Toast.LENGTH_SHORT,
                                    )
                                    .show()
                            }
                        }
                    },
                    enabled = !isSaving && !isChoosingFile,
                ) {
                    if (isSaving) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(20.dp),
                            strokeWidth = 2.dp,
                            color = Color.White,
                        )
                    } else {
                        Icon(
                            imageVector = Icons.Outlined.Download,
                            contentDescription = stringResource(R.string.comment_image_save),
                            tint = Color.White,
                        )
                    }
                }
            }
        }
    }
}
