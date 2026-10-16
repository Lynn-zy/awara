package me.rerere.awara.ui.component.iwara

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalViewConfiguration
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.ExperimentalTextApi
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withAnnotation
import androidx.compose.ui.text.withStyle
import kotlinx.coroutines.withTimeoutOrNull
import me.rerere.awara.ui.theme.info
import org.intellij.markdown.ast.ASTNode
import org.intellij.markdown.ast.acceptChildren
import org.intellij.markdown.ast.getTextInNode
import org.intellij.markdown.ast.visitors.Visitor
import org.intellij.markdown.flavours.commonmark.CommonMarkFlavourDescriptor
import org.intellij.markdown.parser.MarkdownParser

private val ALLOWED_SCHEMES = setOf("http", "https", "mailto")
private val REJECTED_SCHEMES = setOf("javascript", "file", "intent", "content", "data", "about")
private val BARE_URL_REGEX = Regex("""(?i)\b(?:https?://|mailto:)[^\s<>"'`，。；！？、（）【】]+""")

// 校验URL协议头是否安全，防范恶意URI调用
internal fun isSafeUrl(url: String): Boolean {
    val trimmed = url.trim()
    val colonIdx = trimmed.indexOf(':')
    if (colonIdx <= 0) return false
    val scheme = trimmed.substring(0, colonIdx).lowercase()
    if (scheme in REJECTED_SCHEMES) return false
    return scheme in ALLOWED_SCHEMES
}

// 剔除URL尾部标点并将安全链接高亮，非安全或普通字符按原样输出
@OptIn(ExperimentalTextApi::class)
internal fun appendSafeTextAndUrls(
    builder: AnnotatedString.Builder,
    rawText: AnnotatedString,
    theme: RichTextTheme,
) {
    val baseOffset = builder.length
    builder.append(rawText)
    val matches = BARE_URL_REGEX.findAll(rawText)
    for (match in matches) {
        val start = match.range.first
        var matchedUrl = match.value
        while (
            matchedUrl.isNotEmpty() &&
                (matchedUrl.endsWith('.') ||
                    matchedUrl.endsWith(',') ||
                    matchedUrl.endsWith(';') ||
                    (matchedUrl.endsWith(')') &&
                        matchedUrl.count { it == ')' } > matchedUrl.count { it == '(' }) ||
                    (matchedUrl.endsWith(']') &&
                        matchedUrl.count { it == ']' } > matchedUrl.count { it == '[' }) ||
                    matchedUrl.endsWith('"') ||
                    matchedUrl.endsWith('\''))
        ) {
            matchedUrl = matchedUrl.dropLast(1)
        }
        val end = start + matchedUrl.length
        if (
            matchedUrl.isNotEmpty() &&
                isSafeUrl(matchedUrl) &&
                rawText.getStringAnnotations("link", start, end).isEmpty()
        ) {
            builder.addStyle(theme.link, baseOffset + start, baseOffset + end)
            builder.addStringAnnotation("link", matchedUrl, baseOffset + start, baseOffset + end)
        }
    }
}

// 严格限定在文本绘制字符区域内查询链接，避免点击空白处或offset越界误触
internal fun findLinkAnnotationAt(
    pos: Offset,
    layoutResult: TextLayoutResult,
    annotatedString: AnnotatedString,
): String? {
    if (annotatedString.isEmpty()) return null
    if (pos.y < 0f || pos.y > layoutResult.size.height) return null
    val line = layoutResult.getLineForVerticalPosition(pos.y)
    if (line < 0 || line >= layoutResult.lineCount) return null

    val lineLeft = layoutResult.getLineLeft(line)
    val lineRight = layoutResult.getLineRight(line)
    if (pos.x < lineLeft || pos.x > lineRight) return null

    val offset = layoutResult.getOffsetForPosition(pos)
    if (offset !in 0 until annotatedString.length) return null

    val annotations = annotatedString.getStringAnnotations("link", offset, offset)
    return annotations.firstOrNull()?.item
}

@Composable
fun RichText(
    text: String,
    modifier: Modifier = Modifier,
    maxLines: Int = Int.MAX_VALUE,
    overflow: TextOverflow = TextOverflow.Clip,
    onLinkClick: (String) -> Unit,
    style: TextStyle = LocalTextStyle.current,
) {
    val richTextTheme =
        RichTextTheme(
            title1 = MaterialTheme.typography.titleLarge.toSpanStyle(),
            title2 = MaterialTheme.typography.titleMedium.toSpanStyle(),
            title3 = MaterialTheme.typography.titleSmall.toSpanStyle(),
            link = SpanStyle(color = MaterialTheme.colorScheme.info),
        )
    val annotatedString =
        remember(text, richTextTheme) {
            buildAnnotatedString { toAnnotatedString(text, richTextTheme) }
        }
    val layoutResult = remember { mutableStateOf<TextLayoutResult?>(null) }
    val longPressTimeoutMillis = LocalViewConfiguration.current.longPressTimeoutMillis
    val hasLinks =
        remember(annotatedString) {
            annotatedString.getStringAnnotations("link", 0, annotatedString.length).isNotEmpty()
        }
    // 仅在存在链接时附加点击检测，且长按时让度给父级SelectionContainer
    val pressIndicator =
        if (hasLinks) {
            modifier.pointerInput(annotatedString, onLinkClick) {
                awaitEachGesture {
                    awaitFirstDown(requireUnconsumed = false)
                    val up = withTimeoutOrNull(longPressTimeoutMillis) { waitForUpOrCancellation() }
                    if (up != null) {
                        layoutResult.value?.let { layout ->
                            findLinkAnnotationAt(up.position, layout, annotatedString)?.let { url ->
                                up.consume()
                                onLinkClick(url)
                            }
                        }
                    }
                }
            }
        } else {
            modifier
        }
    Text(
        text = annotatedString,
        maxLines = maxLines,
        overflow = overflow,
        modifier = pressIndicator,
        onTextLayout = { layoutResult.value = it },
        style = style,
    )
}

@Stable
data class RichTextTheme(
    val title1: SpanStyle,
    val title2: SpanStyle,
    val title3: SpanStyle,
    val link: SpanStyle,
)

internal fun AnnotatedString.Builder.toAnnotatedString(text: String, theme: RichTextTheme) {
    val parser = MarkdownParser(CommonMarkFlavourDescriptor())
    val syntaxTree = parser.buildMarkdownTreeFromString(text)
    val rendered = buildAnnotatedString { handle(this, syntaxTree, text, theme) }
    appendSafeTextAndUrls(this, rendered, theme)
}

@OptIn(ExperimentalTextApi::class)
private fun AnnotatedString.Builder.handle(
    ctx: AnnotatedString.Builder,
    node: ASTNode,
    text: String,
    theme: RichTextTheme,
) {
    when (node.type.toString()) {
        "WHITE_SPACE" -> {
            append(" ")
        }

        "Markdown:EOL" -> {
            append("\n")
        }

        "Markdown::",
        "Markdown:(",
        "Markdown:)",
        "Markdown:[",
        "Markdown:]",
        "Markdown:!" -> append(node.getTextInNode(text))

        "Markdown:AUTOLINK" -> {
            append(node.getTextInNode(text).toString().removeSurrounding("<", ">"))
        }

        "Markdown:ATX_1" -> {
            ctx.pushStyle(theme.title1)
            node.acceptChildren(
                object : Visitor {
                    override fun visitNode(node: ASTNode) {
                        handle(ctx, node, text, theme)
                    }
                }
            )
            pop()
        }

        "Markdown:ATX_2" -> {
            ctx.pushStyle(theme.title2)
            node.acceptChildren(
                object : Visitor {
                    override fun visitNode(node: ASTNode) {
                        handle(ctx, node, text, theme)
                    }
                }
            )
            pop()
        }

        "Markdown:ATX_3" -> {
            ctx.pushStyle(theme.title3)
            node.acceptChildren(
                object : Visitor {
                    override fun visitNode(node: ASTNode) {
                        handle(ctx, node, text, theme)
                    }
                }
            )
            pop()
        }

        "Markdown:INLINE_LINK" -> {
            val textNode = node.children.firstOrNull { it.type.name == "LINK_TEXT" }
            val destinationNode = node.children.firstOrNull { it.type.name == "LINK_DESTINATION" }
            val displayText =
                textNode?.let {
                    val raw = it.getTextInNode(text).toString()
                    if (raw.startsWith("[") && raw.endsWith("]")) raw.substring(1, raw.length - 1)
                    else raw
                } ?: ""
            val destination =
                destinationNode
                    ?.getTextInNode(text)
                    ?.toString()
                    ?.trim()
                    ?.removeSurrounding("<", ">") ?: ""
            if (destination.isNotEmpty() && isSafeUrl(destination)) {
                withStyle(theme.link) {
                    withAnnotation("link", destination) { append(displayText) }
                }
            } else {
                append(displayText)
            }
        }

        "Markdown:STRONG" -> {
            ctx.pushStyle(SpanStyle(fontWeight = FontWeight.Bold))
            node.acceptChildren(
                object : Visitor {
                    override fun visitNode(node: ASTNode) {
                        handle(ctx, node, text, theme)
                    }
                }
            )
            pop()
        }

        "Markdown:TEXT" -> {
            append(node.getTextInNode(text))
        }

        else -> {
            if (node.children.isNotEmpty()) {
                node.acceptChildren(
                    object : Visitor {
                        override fun visitNode(node: ASTNode) {
                            handle(ctx, node, text, theme)
                        }
                    }
                )
            }
        }
    }
}
