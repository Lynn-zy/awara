package me.rerere.awara.ui.component.common

import me.rerere.awara.ui.component.iwara.findSafeUrlsInPlainText
import me.rerere.awara.ui.component.iwara.isSafeUrl
import me.rerere.awara.ui.component.iwara.parseCommonMarkTree
import org.intellij.markdown.MarkdownElementTypes
import org.intellij.markdown.MarkdownTokenTypes
import org.intellij.markdown.ast.ASTNode
import org.intellij.markdown.ast.getTextInNode

private val LINE_INDENT_AFTER_BREAK_REGEX = Regex("\n[ \t]+")

/**
 * 更新说明文档的块级排版节点，供更新弹窗渲染结构化标题、列表、段落与代码块。
 */
internal sealed interface ReleaseNoteBlock {
    /** 标题块（例如 `### 新增`、`### 变更`、`### 优化与修复`）。 */
    data class Heading(val level: Int, val spans: List<ReleaseNoteSpan>) : ReleaseNoteBlock

    /** 普通段落块。 */
    data class Paragraph(val spans: List<ReleaseNoteSpan>) : ReleaseNoteBlock

    /** 列表块（无序列表或有序列表）。 */
    data class BulletList(val items: List<ReleaseNoteListItem>) : ReleaseNoteBlock

    /** 引用块（`> ...`）。 */
    data class Quote(val blocks: List<ReleaseNoteBlock>) : ReleaseNoteBlock

    /** 代码块（围栏或缩进代码块）。 */
    data class CodeBlock(val code: String) : ReleaseNoteBlock

    /** 水平分割线（`---`）。 */
    data object Divider : ReleaseNoteBlock
}

/**
 * 列表中的单个条目。
 *
 * @property marker 列表前缀标记；无序列表为 `null`（渲染圆点），有序列表为规范化序号（如 `"1."`）
 * @property spans 条目主段落的行内富文本片段
 * @property children 嵌套子块（例如子列表、附加段落或代码块）
 */
internal data class ReleaseNoteListItem(
    val marker: String?,
    val spans: List<ReleaseNoteSpan>,
    val children: List<ReleaseNoteBlock> = emptyList(),
)

/**
 * 更新说明中的行内富文本片段。
 */
internal sealed interface ReleaseNoteSpan {
    data class Text(val text: String) : ReleaseNoteSpan

    data class Bold(val children: List<ReleaseNoteSpan>) : ReleaseNoteSpan

    data class Italic(val children: List<ReleaseNoteSpan>) : ReleaseNoteSpan

    data class Code(val code: String) : ReleaseNoteSpan

    data class Link(val text: String, val url: String) : ReleaseNoteSpan
}

/**
 * 将 GitHub Release / CHANGELOG Markdown 文本解析为结构化块级模型。
 *
 * 仅剥离已知的 Markdown 语法定界符，所有未显式分类的叶节点字符均按原文保留，
 * 避免 `->`、`<=` 等常见文本符号被静默吞并；链接与裸 URL 均经过安全协议校验。
 *
 * @param markdown 原始更新说明 Markdown 文本
 * @return 按原序排列的结构化块级节点列表；空白输入返回空列表
 */
internal fun parseReleaseNoteBlocks(markdown: String): List<ReleaseNoteBlock> {
    val normalized = markdown.replace("\r\n", "\n").replace('\r', '\n').trim()
    if (normalized.isEmpty()) return emptyList()
    val root = parseCommonMarkTree(normalized)
    return parseContainerChildren(root.children, normalized)
}

private fun parseContainerChildren(
    children: List<ASTNode>,
    source: String,
): List<ReleaseNoteBlock> {
    val blocks = mutableListOf<ReleaseNoteBlock>()
    for (child in children) {
        when (child.type) {
            MarkdownElementTypes.ATX_1 -> parseAtxHeading(child, level = 1, source)?.let(blocks::add)
            MarkdownElementTypes.ATX_2 -> parseAtxHeading(child, level = 2, source)?.let(blocks::add)
            MarkdownElementTypes.ATX_3 -> parseAtxHeading(child, level = 3, source)?.let(blocks::add)
            MarkdownElementTypes.ATX_4 -> parseAtxHeading(child, level = 4, source)?.let(blocks::add)
            MarkdownElementTypes.ATX_5 -> parseAtxHeading(child, level = 5, source)?.let(blocks::add)
            MarkdownElementTypes.ATX_6 -> parseAtxHeading(child, level = 6, source)?.let(blocks::add)
            MarkdownElementTypes.SETEXT_1 ->
                parseSetextHeading(child, level = 1, source)?.let(blocks::add)
            MarkdownElementTypes.SETEXT_2 ->
                parseSetextHeading(child, level = 2, source)?.let(blocks::add)
            MarkdownElementTypes.PARAGRAPH -> {
                val spans = parseInlineSpans(child.children, source)
                if (spans.isNotEmpty()) {
                    blocks.add(ReleaseNoteBlock.Paragraph(spans))
                }
            }
            MarkdownElementTypes.UNORDERED_LIST -> {
                parseList(child, ordered = false, source)?.let(blocks::add)
            }
            MarkdownElementTypes.ORDERED_LIST -> {
                parseList(child, ordered = true, source)?.let(blocks::add)
            }
            MarkdownElementTypes.BLOCK_QUOTE -> {
                val nested = parseContainerChildren(child.children, source)
                if (nested.isNotEmpty()) {
                    blocks.add(ReleaseNoteBlock.Quote(nested))
                }
            }
            MarkdownElementTypes.CODE_FENCE -> {
                parseCodeFence(child, source)?.let(blocks::add)
            }
            MarkdownElementTypes.CODE_BLOCK -> {
                parseIndentedCodeBlock(child, source)?.let(blocks::add)
            }
            MarkdownTokenTypes.HORIZONTAL_RULE -> {
                blocks.add(ReleaseNoteBlock.Divider)
            }
            MarkdownElementTypes.HTML_BLOCK -> {
                val raw = child.getTextInNode(source).toString().trim()
                if (raw.isNotEmpty()) {
                    blocks.add(ReleaseNoteBlock.Paragraph(linkifyPlainText(raw)))
                }
            }
            MarkdownTokenTypes.EOL,
            MarkdownTokenTypes.WHITE_SPACE,
            MarkdownTokenTypes.BLOCK_QUOTE -> Unit
            else -> {
                if (child.children.isNotEmpty()) {
                    blocks.addAll(parseContainerChildren(child.children, source))
                } else {
                    val raw = child.getTextInNode(source).toString().trim()
                    if (raw.isNotEmpty()) {
                        blocks.add(ReleaseNoteBlock.Paragraph(linkifyPlainText(raw)))
                    }
                }
            }
        }
    }
    return blocks
}

private fun parseAtxHeading(
    node: ASTNode,
    level: Int,
    source: String,
): ReleaseNoteBlock.Heading? {
    val contentNode = node.children.firstOrNull { it.type == MarkdownTokenTypes.ATX_CONTENT }
    val spans =
        if (contentNode != null) {
            parseInlineSpans(contentNode.children, source)
        } else {
            val contentNodes =
                node.children.filterNot {
                    it.type == MarkdownTokenTypes.ATX_HEADER || it.type == MarkdownTokenTypes.EOL
                }
            parseInlineSpans(contentNodes, source)
        }
    return spans.takeIf { it.isNotEmpty() }?.let { ReleaseNoteBlock.Heading(level, it) }
}

private fun parseSetextHeading(
    node: ASTNode,
    level: Int,
    source: String,
): ReleaseNoteBlock.Heading? {
    val contentNode = node.children.firstOrNull { it.type == MarkdownTokenTypes.SETEXT_CONTENT }
    val spans =
        if (contentNode != null) {
            parseInlineSpans(contentNode.children, source)
        } else {
            emptyList()
        }
    return spans.takeIf { it.isNotEmpty() }?.let { ReleaseNoteBlock.Heading(level, it) }
}

private fun parseList(
    node: ASTNode,
    ordered: Boolean,
    source: String,
): ReleaseNoteBlock.BulletList? {
    val items =
        node.children
            .filter { it.type == MarkdownElementTypes.LIST_ITEM }
            .mapNotNull { itemNode -> parseListItem(itemNode, ordered, source) }
    return items.takeIf { it.isNotEmpty() }?.let { ReleaseNoteBlock.BulletList(it) }
}

private fun parseListItem(
    itemNode: ASTNode,
    ordered: Boolean,
    source: String,
): ReleaseNoteListItem? {
    val marker =
        if (ordered) {
            itemNode.children
                .firstOrNull { it.type == MarkdownTokenTypes.LIST_NUMBER }
                ?.getTextInNode(source)
                ?.toString()
                ?.trim()
                ?.takeIf { it.isNotEmpty() } ?: "•"
        } else {
            null
        }
    val contentChildren =
        itemNode.children.filterNot {
            it.type == MarkdownTokenTypes.LIST_BULLET ||
                it.type == MarkdownTokenTypes.LIST_NUMBER ||
                it.type == MarkdownTokenTypes.WHITE_SPACE ||
                it.type == MarkdownTokenTypes.EOL
        }
    val parsedBlocks = parseContainerChildren(contentChildren, source)
    if (parsedBlocks.isEmpty()) return null

    val first = parsedBlocks.first()
    return if (first is ReleaseNoteBlock.Paragraph) {
        ReleaseNoteListItem(
            marker = marker,
            spans = first.spans,
            children = parsedBlocks.drop(1),
        )
    } else {
        ReleaseNoteListItem(
            marker = marker,
            spans = emptyList(),
            children = parsedBlocks,
        )
    }
}

private fun parseCodeFence(node: ASTNode, source: String): ReleaseNoteBlock.CodeBlock? {
    val builder = StringBuilder()
    var seenContentStart = false
    for (child in node.children) {
        when (child.type) {
            MarkdownTokenTypes.CODE_FENCE_CONTENT -> {
                seenContentStart = true
                builder.append(child.getTextInNode(source))
            }
            MarkdownTokenTypes.EOL -> {
                if (seenContentStart) {
                    builder.append('\n')
                }
            }
        }
    }
    val code = builder.toString().trimEnd('\n')
    return code.takeIf { it.isNotEmpty() }?.let { ReleaseNoteBlock.CodeBlock(it) }
}

private fun parseIndentedCodeBlock(node: ASTNode, source: String): ReleaseNoteBlock.CodeBlock? {
    val builder = StringBuilder()
    for (child in node.children) {
        when (child.type) {
            MarkdownTokenTypes.CODE_LINE -> builder.append(child.getTextInNode(source))
            MarkdownTokenTypes.EOL -> builder.append('\n')
        }
    }
    val code = builder.toString().trim('\n')
    return code.takeIf { it.isNotEmpty() }?.let { ReleaseNoteBlock.CodeBlock(it) }
}

private fun parseInlineSpans(
    nodes: List<ASTNode>,
    source: String,
): List<ReleaseNoteSpan> {
    val rawSpans = mutableListOf<ReleaseNoteSpan>()
    for (node in nodes) {
        collectInlineNode(node, source, rawSpans)
    }
    return trimSpans(expandBareUrls(mergeAdjacentTextSpans(rawSpans, unescape = true)))
}

private fun unescapeCommonMarkText(text: String): String {
    if ('\\' !in text) return text
    val builder = StringBuilder(text.length)
    var index = 0
    while (index < text.length) {
        val current = text[index]
        val next = text.getOrNull(index + 1)
        if (current == '\\' && next != null && isCommonMarkEscapablePunctuation(next)) {
            builder.append(next)
            index += 2
        } else {
            builder.append(current)
            index++
        }
    }
    return builder.toString()
}

private fun isCommonMarkEscapablePunctuation(ch: Char): Boolean =
    ch in "!\"#$%&'()*+,-./:;<=>?@[\\]^_`{|}~"

private fun extractLinkDisplayText(textNode: ASTNode?, source: String): String {
    if (textNode == null) return ""
    val innerNodes =
        textNode.children.filterNot {
            it.type == MarkdownTokenTypes.LBRACKET || it.type == MarkdownTokenTypes.RBRACKET
        }
    if (innerNodes.isEmpty()) {
        val raw = textNode.getTextInNode(source).toString()
        return if (raw.startsWith('[') && raw.endsWith(']')) raw.substring(1, raw.length - 1) else raw
    }
    val innerSpans = collectInlineWithoutTrim(innerNodes, source)
    return flattenSpansToPlainText(innerSpans).trim()
}

private fun flattenSpansToPlainText(spans: List<ReleaseNoteSpan>): String = buildString {
    for (span in spans) {
        when (span) {
            is ReleaseNoteSpan.Text -> append(span.text)
            is ReleaseNoteSpan.Bold -> append(flattenSpansToPlainText(span.children))
            is ReleaseNoteSpan.Italic -> append(flattenSpansToPlainText(span.children))
            is ReleaseNoteSpan.Code -> append(span.code)
            is ReleaseNoteSpan.Link -> append(span.text)
        }
    }
}

private fun collectInlineNode(
    node: ASTNode,
    source: String,
    output: MutableList<ReleaseNoteSpan>,
) {
    when (node.type) {
        MarkdownElementTypes.STRONG -> {
            val inner = stripBoundaryTokens(node.children, MarkdownTokenTypes.EMPH)
            val parsed = collectInlineWithoutTrim(inner, source)
            if (parsed.isNotEmpty()) {
                output.add(ReleaseNoteSpan.Bold(parsed))
            }
        }
        MarkdownElementTypes.EMPH -> {
            val inner = stripBoundaryTokens(node.children, MarkdownTokenTypes.EMPH)
            val parsed = collectInlineWithoutTrim(inner, source)
            if (parsed.isNotEmpty()) {
                output.add(ReleaseNoteSpan.Italic(parsed))
            }
        }
        MarkdownElementTypes.CODE_SPAN -> {
            val inner = stripBoundaryTokens(node.children, MarkdownTokenTypes.BACKTICK)
            val rawCode =
                inner.joinToString(separator = "") { it.getTextInNode(source).toString() }
            val normalizedCode =
                if (
                    rawCode.length >= 2 &&
                        rawCode.startsWith(' ') &&
                        rawCode.endsWith(' ') &&
                        rawCode.any { it != ' ' }
                ) {
                    rawCode.substring(1, rawCode.length - 1)
                } else {
                    rawCode
                }
            if (normalizedCode.isNotEmpty()) {
                output.add(ReleaseNoteSpan.Code(normalizedCode))
            }
        }
        MarkdownElementTypes.INLINE_LINK -> {
            val textNode = node.children.firstOrNull { it.type == MarkdownElementTypes.LINK_TEXT }
            val destinationNode =
                node.children.firstOrNull { it.type == MarkdownElementTypes.LINK_DESTINATION }
            val destination =
                destinationNode
                    ?.getTextInNode(source)
                    ?.toString()
                    ?.trim()
                    ?.removeSurrounding("<", ">")
                    .orEmpty()
            val displayText = extractLinkDisplayText(textNode, source).ifEmpty { destination }
            if (displayText.isNotEmpty()) {
                if (destination.isNotEmpty() && isSafeUrl(destination)) {
                    output.add(ReleaseNoteSpan.Link(text = displayText, url = destination))
                } else {
                    output.add(ReleaseNoteSpan.Text(displayText))
                }
            }
        }
        MarkdownElementTypes.AUTOLINK -> {
            val rawUrl = node.getTextInNode(source).toString().removeSurrounding("<", ">").trim()
            if (rawUrl.isNotEmpty()) {
                if (isSafeUrl(rawUrl)) {
                    output.add(ReleaseNoteSpan.Link(text = rawUrl, url = rawUrl))
                } else {
                    output.add(ReleaseNoteSpan.Text(rawUrl))
                }
            }
        }
        MarkdownElementTypes.IMAGE -> {
            val linkNode = node.children.firstOrNull { it.type == MarkdownElementTypes.INLINE_LINK }
            val altText =
                linkNode
                    ?.children
                    ?.firstOrNull { it.type == MarkdownElementTypes.LINK_TEXT }
                    ?.getTextInNode(source)
                    ?.toString()
                    ?.removePrefix("[")
                    ?.removeSuffix("]")
                    ?.trim()
                    .orEmpty()
            if (altText.isNotEmpty()) {
                output.add(ReleaseNoteSpan.Text(altText))
            }
        }
        MarkdownTokenTypes.HARD_LINE_BREAK,
        MarkdownTokenTypes.EOL -> {
            output.add(ReleaseNoteSpan.Text("\n"))
        }
        MarkdownTokenTypes.WHITE_SPACE -> {
            output.add(ReleaseNoteSpan.Text(" "))
        }
        else -> {
            if (node.children.isNotEmpty()) {
                for (child in node.children) {
                    collectInlineNode(child, source, output)
                }
            } else {
                val rawText = node.getTextInNode(source).toString()
                if (rawText.isNotEmpty()) {
                    output.add(ReleaseNoteSpan.Text(rawText))
                }
            }
        }
    }
}

private fun collectInlineWithoutTrim(
    nodes: List<ASTNode>,
    source: String,
): List<ReleaseNoteSpan> {
    val rawSpans = mutableListOf<ReleaseNoteSpan>()
    for (node in nodes) {
        collectInlineNode(node, source, rawSpans)
    }
    return expandBareUrls(mergeAdjacentTextSpans(rawSpans, unescape = true))
}

private fun stripBoundaryTokens(
    children: List<ASTNode>,
    delimiterType: org.intellij.markdown.IElementType,
): List<ASTNode> {
    var start = 0
    var end = children.size
    while (start < end && children[start].type == delimiterType) {
        start++
    }
    while (end > start && children[end - 1].type == delimiterType) {
        end--
    }
    return children.subList(start, end)
}

private fun mergeAdjacentTextSpans(
    spans: List<ReleaseNoteSpan>,
    unescape: Boolean = false,
): List<ReleaseNoteSpan> {
    if (spans.isEmpty()) return emptyList()
    val merged = mutableListOf<ReleaseNoteSpan>()
    val textBuffer = StringBuilder()

    fun flushBuffer() {
        if (textBuffer.isNotEmpty()) {
            val indentNormalized = textBuffer.toString().replace(LINE_INDENT_AFTER_BREAK_REGEX, "\n")
            val normalized =
                if (unescape) unescapeCommonMarkText(indentNormalized) else indentNormalized
            merged.add(ReleaseNoteSpan.Text(normalized))
            textBuffer.setLength(0)
        }
    }

    for (span in spans) {
        if (span is ReleaseNoteSpan.Text) {
            textBuffer.append(span.text)
        } else {
            flushBuffer()
            merged.add(span)
        }
    }
    flushBuffer()
    return merged
}

private fun expandBareUrls(spans: List<ReleaseNoteSpan>): List<ReleaseNoteSpan> {
    if (spans.isEmpty()) return emptyList()
    val result = mutableListOf<ReleaseNoteSpan>()
    for (span in spans) {
        when (span) {
            is ReleaseNoteSpan.Text -> result.addAll(linkifyPlainText(span.text))
            is ReleaseNoteSpan.Bold -> result.add(ReleaseNoteSpan.Bold(expandBareUrls(span.children)))
            is ReleaseNoteSpan.Italic ->
                result.add(ReleaseNoteSpan.Italic(expandBareUrls(span.children)))
            is ReleaseNoteSpan.Code,
            is ReleaseNoteSpan.Link -> result.add(span)
        }
    }
    return mergeAdjacentTextSpans(result)
}

private fun linkifyPlainText(text: String): List<ReleaseNoteSpan> {
    if (text.isEmpty()) return emptyList()
    val result = mutableListOf<ReleaseNoteSpan>()
    var cursor = 0
    for (match in findSafeUrlsInPlainText(text)) {
        if (match.start > cursor) {
            result.add(ReleaseNoteSpan.Text(text.substring(cursor, match.start)))
        }
        result.add(ReleaseNoteSpan.Link(text = match.url, url = match.url))
        cursor = match.end
    }
    if (cursor < text.length) {
        result.add(ReleaseNoteSpan.Text(text.substring(cursor)))
    }
    return result
}

private fun trimSpans(spans: List<ReleaseNoteSpan>): List<ReleaseNoteSpan> {
    if (spans.isEmpty()) return emptyList()
    val mutable = spans.toMutableList()
    while (mutable.isNotEmpty()) {
        val first = mutable.first() as? ReleaseNoteSpan.Text ?: break
        val trimmed = first.text.trimStart()
        if (trimmed.isEmpty()) {
            mutable.removeAt(0)
        } else {
            mutable[0] = ReleaseNoteSpan.Text(trimmed)
            break
        }
    }
    while (mutable.isNotEmpty()) {
        val last = mutable.last() as? ReleaseNoteSpan.Text ?: break
        val trimmed = last.text.trimEnd()
        if (trimmed.isEmpty()) {
            mutable.removeAt(mutable.lastIndex)
        } else {
            mutable[mutable.lastIndex] = ReleaseNoteSpan.Text(trimmed)
            break
        }
    }
    return mutable
}
