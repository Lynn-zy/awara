package me.rerere.awara.ui.component.iwara

import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RichTextTest {
    private val theme =
        RichTextTheme(
            title1 = SpanStyle(),
            title2 = SpanStyle(),
            title3 = SpanStyle(),
            link = SpanStyle(),
        )

    @Test
    fun plainDescriptionUrlsRemainClickableAfterMarkdownParsing() {
        val description = "地址：https://iwara.tv/video/abc123。\n邮箱 mailto:hello@example.com"
        val result = buildAnnotatedString { toAnnotatedString(description, theme) }
        assertEquals(description, result.text)
        assertEquals(
            listOf("https://iwara.tv/video/abc123", "mailto:hello@example.com"),
            result.getStringAnnotations("link", 0, result.length).map { it.item },
        )
    }

    @Test
    fun angleBracketUrlsRemainVisibleAndClickable() {
        val result = buildAnnotatedString {
            toAnnotatedString("<https://iwara.tv/video/abc123>", theme)
        }
        assertEquals("https://iwara.tv/video/abc123", result.text)
        assertEquals(
            "https://iwara.tv/video/abc123",
            result.getStringAnnotations("link", 0, result.length).single().item,
        )
    }

    @Test
    fun testSafeUrlValidation() {
        // 验证允许的合法安全协议头
        assertTrue(isSafeUrl("http://example.com"))
        assertTrue(isSafeUrl("https://iwara.tv/video/123"))
        assertTrue(isSafeUrl("mailto:support@iwara.tv"))
        assertTrue(isSafeUrl("HTTPS://EXAMPLE.COM/UPPER"))
        assertTrue(isSafeUrl("  https://example.com/spaced  "))

        // 验证必须拦截的高危与非标准协议头
        assertFalse(isSafeUrl("javascript:alert(1)"))
        assertFalse(isSafeUrl("JAVASCRIPT:alert(document.cookie)"))
        assertFalse(isSafeUrl("file:///system/bin/sh"))
        assertFalse(isSafeUrl("intent://scan/#Intent;scheme=zxing;end"))
        assertFalse(isSafeUrl("content://media/external/images/media/1"))
        assertFalse(isSafeUrl("data:text/html;base64,PHNjcmlwdD4="))
        assertFalse(isSafeUrl("about:blank"))
        assertFalse(isSafeUrl("plain_string_without_scheme"))
        assertFalse(isSafeUrl(""))
    }

    @Test
    fun testAppendSafeTextAndUrlsPreservesOriginalCharacters() {
        val raw = "访问 https://iwara.tv/ 即可，或联系 mailto:admin@iwara.tv。还有javascript:test无效链接。"
        val builder = buildAnnotatedString {
            appendSafeTextAndUrls(this, AnnotatedString(raw), theme)
        }
        val resultText = builder.text
        // 确保无论链接是否有效，原始文本字符均完整保留不被吞没
        assertEquals(raw, resultText)

        val linkAnnotations = builder.getStringAnnotations("link", 0, builder.length)
        val annotatedUrls = linkAnnotations.map { it.item }
        assertTrue(annotatedUrls.contains("https://iwara.tv/"))
        assertTrue(annotatedUrls.contains("mailto:admin@iwara.tv"))
        assertFalse(annotatedUrls.any { it.startsWith("javascript") })
    }

    @Test
    fun testTrailingPunctuationSanitization() {
        val raw = "参见(https://iwara.tv/help)."
        val builder = buildAnnotatedString {
            appendSafeTextAndUrls(this, AnnotatedString(raw), theme)
        }
        assertEquals(raw, builder.text)
        val links = builder.getStringAnnotations("link", 0, builder.length)
        assertEquals(1, links.size)
        // 链接后部的括号和句号被剥离为普通文本，不进入跳转URL
        assertEquals("https://iwara.tv/help", links.first().item)
    }

    @Test
    fun testMarkdownInlineLinkSafeAndUnsafe() {
        val markdown = "[安全链接](https://iwara.tv) 与 [危险脚本](javascript:alert(1))"
        val builder = buildAnnotatedString { toAnnotatedString(markdown, theme) }
        val links = builder.getStringAnnotations("link", 0, builder.length)
        assertEquals(1, links.size)
        assertEquals("https://iwara.tv", links.first().item)
        // 不安全的危险链接不挂载注解，但展示文案完整保留
        assertTrue(builder.text.contains("安全链接"))
        assertTrue(builder.text.contains("危险脚本"))
    }
}
