package me.rerere.awara.ui.component.iwara

import java.util.Locale
import org.intellij.markdown.ast.ASTNode
import org.intellij.markdown.flavours.commonmark.CommonMarkFlavourDescriptor
import org.intellij.markdown.parser.MarkdownParser

internal val REJECTED_URL_SCHEMES =
    setOf("javascript", "file", "intent", "content", "data", "about")
internal val ALLOWED_LINK_URL_SCHEMES = setOf("http", "https", "mailto")
internal val ALLOWED_IMAGE_URL_SCHEMES = setOf("http", "https")

private val commonMarkFlavour = CommonMarkFlavourDescriptor()

/** 使用共享 CommonMark 解析器构建 Markdown 语法树。 */
internal fun parseCommonMarkTree(source: String): ASTNode =
    MarkdownParser(commonMarkFlavour).buildMarkdownTreeFromString(source)

/**
 * 统一校验 Markdown 链接与内嵌图片 URL 协议安全性：
 * 拒绝控制字符/空白混淆、显式黑名单协议及缺失有效主机的链接。
 */
internal fun isAllowedUrl(
    url: String,
    allowedSchemes: Set<String> = ALLOWED_LINK_URL_SCHEMES,
): Boolean {
    val trimmed = url.trim()
    if (trimmed.isEmpty() || trimmed.any { it.isISOControl() }) return false
    val colonIndex = trimmed.indexOf(':')
    if (colonIndex <= 0) return false
    val scheme = trimmed.substring(0, colonIndex).lowercase(Locale.ROOT)
    if (scheme.any { !it.isLetterOrDigit() && it != '+' && it != '-' && it != '.' }) {
        return false
    }
    if (scheme in REJECTED_URL_SCHEMES || scheme !in allowedSchemes) return false
    val remainder = trimmed.substring(colonIndex + 1)
    return when (scheme) {
        "http", "https" -> hasValidHttpAuthority(remainder)
        "mailto" -> remainder.isNotBlank() && remainder.none { it.isWhitespace() }
        else -> false
    }
}

private fun hasValidHttpAuthority(remainder: String): Boolean {
    if (!remainder.startsWith("//")) return false
    val authority =
        remainder
            .removePrefix("//")
            .substringBefore('/')
            .substringBefore('?')
            .substringBefore('#')
    val hostPort = authority.substringAfter('@')
    if (hostPort.isBlank() || hostPort.any { it.isWhitespace() }) return false
    val host =
        if (hostPort.startsWith("[")) {
            val closeBracket = hostPort.indexOf(']')
            if (closeBracket <= 1) return false
            hostPort.substring(1, closeBracket)
        } else {
            hostPort.substringBefore(':')
        }
    return host.isNotBlank()
}

/** 校验富文本外部链接是否属于允许的安全协议（http/https/mailto）。 */
internal fun isSafeUrl(url: String): Boolean =
    isAllowedUrl(url, ALLOWED_LINK_URL_SCHEMES)

/** 校验评论内嵌图片 URL 是否属于允许的安全协议（http/https）。 */
internal fun isSafeImageUrl(url: String): Boolean =
    isAllowedUrl(url, ALLOWED_IMAGE_URL_SCHEMES)
