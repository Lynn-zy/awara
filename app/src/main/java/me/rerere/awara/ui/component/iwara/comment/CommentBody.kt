package me.rerere.awara.ui.component.iwara.comment

import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.DisableSelection
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Image
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImagePainter
import coil.compose.rememberAsyncImagePainter
import coil.request.ImageRequest
import java.net.URI
import me.rerere.awara.R
import me.rerere.awara.ui.component.iwara.RichText
import me.rerere.compose_setting.preference.rememberBooleanPreference
import org.intellij.markdown.ast.ASTNode
import org.intellij.markdown.ast.getTextInNode
import org.intellij.markdown.flavours.commonmark.CommonMarkFlavourDescriptor
import org.intellij.markdown.parser.MarkdownParser

/** 评论图片自动加载偏好；默认关闭，与设置页共用同一个存储键。 */
const val PREFERENCE_KEY_COMMENT_IMAGE_AUTO_LOAD = "setting.comment_image_auto_load"

/** 评论内容切分段落模型 */
sealed interface CommentBodySegment {
    /** 纯文本或 Markdown 富文本内容 */
    data class Text(val value: String) : CommentBodySegment

    /** Markdown 内嵌图片 */
    data class Image(val alt: String, val url: String) : CommentBodySegment
}

// 仅允许安全的 HTTP/HTTPS 协议图片
private val ALLOWED_IMAGE_SCHEMES = setOf("http", "https")
private val REJECTED_IMAGE_SCHEMES =
    setOf("javascript", "file", "intent", "content", "data", "about")

/**
 * 校验图片 URL 是否为安全合规的 HTTP/HTTPS 网络地址 严格拒绝 file, content, data, javascript 等高危或本地协议
 *
 * @param url 待校验的图片 URL
 * @return 若安全有效则返回 true
 */
internal fun isSafeImageUrl(url: String): Boolean {
    val trimmed = url.trim()
    val colonIdx = trimmed.indexOf(':')
    if (colonIdx <= 0) return false
    val scheme = trimmed.substring(0, colonIdx).lowercase()
    if (scheme in REJECTED_IMAGE_SCHEMES) return false
    if (scheme !in ALLOWED_IMAGE_SCHEMES) return false
    return try {
        val uri = URI(trimmed)
        val uriScheme = uri.scheme?.lowercase()
        (uriScheme == "http" || uriScheme == "https") && !uri.host.isNullOrBlank()
    } catch (_: Exception) {
        false
    }
}

private data class ImageNodeRange(
    val startOffset: Int,
    val endOffset: Int,
    val alt: String,
    val url: String,
)

/**
 * 使用 IntelliJ Markdown AST 解析评论正文，提取安全的内嵌图片并保留原序正文
 * - 普通链接与代码块（含行内代码）中的语法不作为图片提取
 * - 拒绝非安全协议头的图片，保留原文本段中
 * - 严格保留图片前后的文本内容与先后顺序
 *
 * @param body 评论正文
 * @return 保持原始顺序的 Text 与 Image 段落列表
 */
fun splitCommentBody(body: String): List<CommentBodySegment> {
    if (body.isEmpty()) return emptyList()

    val parser = MarkdownParser(CommonMarkFlavourDescriptor())
    val root = parser.buildMarkdownTreeFromString(body)

    val imageNodes = mutableListOf<ImageNodeRange>()

    fun collectImages(node: ASTNode) {
        if (node.type.name == "IMAGE") {
            val linkNode = node.children.firstOrNull { it.type.name == "INLINE_LINK" }
            val textNode = linkNode?.children?.firstOrNull { it.type.name == "LINK_TEXT" }
            val destNode = linkNode?.children?.firstOrNull { it.type.name == "LINK_DESTINATION" }
            if (destNode != null) {
                val rawUrl =
                    destNode.getTextInNode(body).toString().trim().removeSurrounding("<", ">")
                val alt =
                    textNode
                        ?.getTextInNode(body)
                        ?.toString()
                        ?.removePrefix("[")
                        ?.removeSuffix("]")
                        .orEmpty()
                if (isSafeImageUrl(rawUrl)) {
                    imageNodes.add(
                        ImageNodeRange(
                            startOffset = node.startOffset,
                            endOffset = node.endOffset,
                            alt = alt,
                            url = rawUrl,
                        )
                    )
                    return // 已识别为有效图片，不再递归其内部子节点
                }
            }
        }
        for (child in node.children) {
            collectImages(child)
        }
    }

    collectImages(root)

    if (imageNodes.isEmpty()) {
        return listOf(CommentBodySegment.Text(body))
    }

    val segments = mutableListOf<CommentBodySegment>()
    var cursor = 0
    for (img in imageNodes) {
        if (img.startOffset > cursor) {
            val text = body.substring(cursor, img.startOffset)
            if (text.isNotEmpty()) {
                segments.add(CommentBodySegment.Text(text))
            }
        }
        segments.add(CommentBodySegment.Image(alt = img.alt, url = img.url))
        cursor = img.endOffset
    }
    if (cursor < body.length) {
        val text = body.substring(cursor, body.length)
        if (text.isNotEmpty()) {
            segments.add(CommentBodySegment.Text(text))
        }
    }

    return segments
}

/**
 * 评论正文组件
 * - 替换原先单一的 RichText 调用
 * - 文本部分继续复用 RichText，完整保留超链接跳转与父级 SelectionContainer 文本长按选择能力
 * - 内嵌图片渲染为受限尺寸图，带有 loading、error 与点击重试
 * - 点击图片弹出无装饰全屏预览与保存 Dialog
 *
 * @param body 评论 Markdown 文本
 * @param modifier 修饰符
 * @param onLinkClick 链接点击跳转回调
 */
@Composable
fun CommentBody(body: String, modifier: Modifier = Modifier, onLinkClick: (String) -> Unit) {
    val segments = remember(body) { splitCommentBody(body) }
    val autoLoadImages by
        rememberBooleanPreference(key = PREFERENCE_KEY_COMMENT_IMAGE_AUTO_LOAD, default = false)
    var previewImageUrl by rememberSaveable { mutableStateOf<String?>(null) }
    var previewImageAlt by rememberSaveable { mutableStateOf("") }

    Column(modifier = modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        for (segment in segments) {
            when (segment) {
                is CommentBodySegment.Text -> {
                    RichText(text = segment.value, onLinkClick = onLinkClick)
                }

                is CommentBodySegment.Image -> {
                    // 图片包裹 DisableSelection，避免在外部 SelectionContainer 下出现文本光标干扰
                    DisableSelection {
                        CommentEmbeddedImage(
                            image = segment,
                            autoLoad = autoLoadImages,
                            onClick = {
                                previewImageUrl = segment.url
                                previewImageAlt = segment.alt
                            },
                        )
                    }
                }
            }
        }
    }

    previewImageUrl?.let { url ->
        CommentImagePreviewDialog(
            imageUrl = url,
            alt = previewImageAlt,
            onDismiss = { previewImageUrl = null },
        )
    }
}

/**
 * 评论卡片内嵌图片展示组件
 * - 限制稳定尺寸，避免占位突变引起列表滚动抖动
 * - 展示加载中与失败重试状态
 * - 点击触发大图全屏预览
 *
 * @param autoLoad 是否自动加载；关闭时仅显式点击按钮才授予当前图片加载权限。
 */
@Composable
fun CommentEmbeddedImage(
    image: CommentBodySegment.Image,
    autoLoad: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    // 只保存用户对当前 URL 的手动授权，自动加载不能在关闭设置后留下授权。
    var manuallyRequested by rememberSaveable(image.url) { mutableStateOf(false) }
    if (!autoLoad && !manuallyRequested) {
        TextButton(onClick = { manuallyRequested = true }, modifier = modifier) {
            Icon(Icons.Outlined.Image, contentDescription = null)
            Text(
                stringResource(R.string.comment_image_load),
                modifier = Modifier.padding(start = 6.dp),
            )
        }
        return
    }

    Surface(
        shape = RoundedCornerShape(8.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
        modifier =
            modifier
                .padding(vertical = 2.dp)
                .size(width = 240.dp, height = 180.dp)
                .clip(RoundedCornerShape(8.dp)),
    ) {
        CommentImageContent(
            imageUrl = image.url,
            alt = image.alt,
            modifier = Modifier.fillMaxSize(),
            onClick = onClick,
        )
    }
}

/** 图片始终保持在布局中提供真实绘制尺寸；加载提示和失败重试仅作为覆盖层。 */
@Composable
internal fun CommentImageContent(
    imageUrl: String,
    alt: String,
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
) {
    var retryKey by remember(imageUrl) { mutableStateOf(0) }
    val context = LocalContext.current
    val request =
        remember(context, imageUrl) {
            ImageRequest.Builder(context).data(imageUrl).crossfade(true).build()
        }
    val painter = key(imageUrl, retryKey) { rememberAsyncImagePainter(model = request) }
    Box(modifier = modifier, contentAlignment = Alignment.Center) {
        Image(
            painter = painter,
            contentDescription = alt.ifBlank { stringResource(R.string.comment_image_preview) },
            contentScale = ContentScale.Fit,
            modifier =
                Modifier.fillMaxSize()
                    .then(
                        if (onClick != null) {
                            Modifier.clickable(
                                enabled = painter.state is AsyncImagePainter.State.Success
                            ) {
                                onClick()
                            }
                        } else Modifier
                    ),
        )
        when (painter.state) {
            is AsyncImagePainter.State.Empty,
            is AsyncImagePainter.State.Loading ->
                CircularProgressIndicator(modifier = Modifier.size(24.dp), strokeWidth = 2.dp)
            is AsyncImagePainter.State.Error -> {
                TextButton(onClick = { retryKey++ }) {
                    Icon(
                        Icons.Outlined.Refresh,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp),
                    )
                    Text(
                        text = stringResource(R.string.comment_image_load_failed),
                        modifier = Modifier.padding(start = 6.dp),
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
            is AsyncImagePainter.State.Success -> Unit
        }
    }
}
