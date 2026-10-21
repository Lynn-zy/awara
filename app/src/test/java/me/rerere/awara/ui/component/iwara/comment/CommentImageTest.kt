package me.rerere.awara.ui.component.iwara.comment

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CommentImageTest {
    @Test
    fun parsesMarkdownImageAndKeepsSurroundingText() {
        val segments = splitCommentBody("前文 ![表情](https://example.com/emoji.png) 后文")

        assertEquals(
            listOf(
                CommentBodySegment.Text("前文 "),
                CommentBodySegment.Image("表情", "https://example.com/emoji.png"),
                CommentBodySegment.Text(" 后文"),
            ),
            segments,
        )
    }

    @Test
    fun parsesMultipleImagesAndIgnoresUnsafeImageUrl() {
        val segments =
            splitCommentBody(
                "![一](https://example.com/one.jpg) ![危险](javascript:alert(1)) ![二](https://example.com/two.jpg)"
            )

        assertEquals(2, segments.count { it is CommentBodySegment.Image })
        assertTrue(
            segments.any { it == CommentBodySegment.Image("一", "https://example.com/one.jpg") }
        )
        assertTrue(
            segments.any { it == CommentBodySegment.Image("二", "https://example.com/two.jpg") }
        )
        assertTrue(segments.filterIsInstance<CommentBodySegment.Text>().any { "危险" in it.value })
    }

    @Test
    fun plainCommentRemainsOneTextSegment() {
        assertEquals(listOf(CommentBodySegment.Text("普通评论")), splitCommentBody("普通评论"))
    }

    @Test
    fun parsesImageWithTitleAndKeepsUrlClean() {
        val markdown = "前 ![封面](https://example.com/cover.png \"这是标题\") 后"
        val segments = splitCommentBody(markdown)

        assertEquals(
            listOf(
                CommentBodySegment.Text("前 "),
                CommentBodySegment.Image("封面", "https://example.com/cover.png"),
                CommentBodySegment.Text(" 后"),
            ),
            segments,
        )
    }

    @Test
    fun ignoresImagesInsideCodeFenceAndInlineCode() {
        val codeFence = "```\n![code](https://example.com/code.png)\n```"
        val fenceSegments = splitCommentBody(codeFence)
        // 代码块内的内容不应被抽取为 Image
        assertEquals(0, fenceSegments.count { it is CommentBodySegment.Image })
        assertEquals(1, fenceSegments.size)
        assertTrue(fenceSegments.first() is CommentBodySegment.Text)

        val inlineCode = "请运行 `![inline](https://example.com/inline.png)` 命令"
        val inlineSegments = splitCommentBody(inlineCode)
        // 行内代码也不应作为图片提取
        assertEquals(0, inlineSegments.count { it is CommentBodySegment.Image })
        assertEquals(listOf(CommentBodySegment.Text(inlineCode)), inlineSegments)
    }

    @Test
    fun ignoresNormalLinksAndAutolinks() {
        val markdown = "查看 [官方网站](https://example.com) 以及 <https://example.com/doc>"
        val segments = splitCommentBody(markdown)

        // 普通文本超链接和自链接不应被解析为图片
        assertEquals(0, segments.count { it is CommentBodySegment.Image })
        assertEquals(listOf(CommentBodySegment.Text(markdown)), segments)
    }

    @Test
    fun rejectsDangerousSchemesAndPreservesThemAsText() {
        val dangerous =
            "![本地文件](file:///data/data/com.example/databases/db) " +
                "![内容提供者](content://media/external/images/media/1) " +
                "![Base64数据](data:image/png;base64,iVBORw0KGgo) " +
                "![意图注入](intent://example.com/#Intent;scheme=bad;end)"

        val segments = splitCommentBody(dangerous)

        // 所有危险协议均被拒绝为 Image，全部保留在 Text 中
        assertEquals(0, segments.count { it is CommentBodySegment.Image })
        val textContent =
            segments.filterIsInstance<CommentBodySegment.Text>().joinToString { it.value }
        assertTrue("file:" in textContent)
        assertTrue("content:" in textContent)
        assertTrue("data:" in textContent)
        assertTrue("intent:" in textContent)
    }

    @Test
    fun emptyBodyReturnsEmptyList() {
        assertEquals(emptyList<CommentBodySegment>(), splitCommentBody(""))
    }

    @Test
    fun parsesConsecutiveImages() {
        val markdown = "![一](https://example.com/1.png)![二](https://example.com/2.png)"
        val segments = splitCommentBody(markdown)

        assertEquals(
            listOf(
                CommentBodySegment.Image("一", "https://example.com/1.png"),
                CommentBodySegment.Image("二", "https://example.com/2.png"),
            ),
            segments,
        )
    }
}
