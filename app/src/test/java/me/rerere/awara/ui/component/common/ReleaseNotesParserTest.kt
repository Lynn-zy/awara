package me.rerere.awara.ui.component.common

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 更新说明 Markdown 结构化解析与版本跨度格式化单元测试。
 */
class ReleaseNotesParserTest {
    @Test
    fun parsesRealChangelogWithHeadingsListsInlineCodeArrowsAndFullChangelogLink() {
        val markdown =
            """
            ### 新增
            - 全屏播放器新增三档画面缩放模式（`适应`、`填充`、`100%` 真实分辨率）
            - 全屏播放器右上角新增旋转屏幕按钮，并在“设置 -> 播放器设置”中新增开关

            ### 变更
            - 播放器倍速切换按钮移至右下角控制栏

            ### 优化与修复
            - 修复全屏播放时垂直/水平滑动手势偶发失效的问题

            **Full Changelog**: https://github.com/Lynn-zy/awara/compare/v1.3.3...v1.3.4
            """
                .trimIndent()

        val blocks = parseReleaseNoteBlocks(markdown)
        assertEquals(7, blocks.size)

        // 标题去除了 ### 后的前导空格
        assertEquals(
            ReleaseNoteBlock.Heading(level = 3, spans = listOf(ReleaseNoteSpan.Text("新增"))),
            blocks[0],
        )

        val addedList = blocks[1] as ReleaseNoteBlock.BulletList
        assertEquals(2, addedList.items.size)
        assertNull(addedList.items[0].marker)
        // 行内代码按独立样式片断保留
        assertEquals(
            listOf(
                ReleaseNoteSpan.Text("全屏播放器新增三档画面缩放模式（"),
                ReleaseNoteSpan.Code("适应"),
                ReleaseNoteSpan.Text("、"),
                ReleaseNoteSpan.Code("填充"),
                ReleaseNoteSpan.Text("、"),
                ReleaseNoteSpan.Code("100%"),
                ReleaseNoteSpan.Text(" 真实分辨率）"),
            ),
            addedList.items[0].spans,
        )
        // 关键回归校验：箭头符号 `->` 不被吞并
        assertEquals(
            listOf(
                ReleaseNoteSpan.Text("全屏播放器右上角新增旋转屏幕按钮，并在“设置 -> 播放器设置”中新增开关")
            ),
            addedList.items[1].spans,
        )

        assertEquals(
            ReleaseNoteBlock.Heading(level = 3, spans = listOf(ReleaseNoteSpan.Text("变更"))),
            blocks[2],
        )
        assertEquals(
            ReleaseNoteBlock.Heading(level = 3, spans = listOf(ReleaseNoteSpan.Text("优化与修复"))),
            blocks[4],
        )

        // 尾部 Full Changelog 保留加粗标题与可点击安全对比链接
        val footer = blocks[6] as ReleaseNoteBlock.Paragraph
        assertEquals(
            listOf(
                ReleaseNoteSpan.Bold(listOf(ReleaseNoteSpan.Text("Full Changelog"))),
                ReleaseNoteSpan.Text(": "),
                ReleaseNoteSpan.Link(
                    text = "https://github.com/Lynn-zy/awara/compare/v1.3.3...v1.3.4",
                    url = "https://github.com/Lynn-zy/awara/compare/v1.3.3...v1.3.4",
                ),
            ),
            footer.spans,
        )
    }

    @Test
    fun parsesOrderedListsNestedListsQuotesCodeFencesAndDividers() {
        val markdown =
            "1. 第一项 *斜体*\r\n" +
                "   - 嵌套子项\r\n" +
                "2. 第二项\r\n\r\n" +
                "> 引用说明文字\r\n\r\n" +
                "---\r\n\r\n" +
                "```sh\r\n" +
                "./gradlew assembleRelease\r\n" +
                "```"

        val blocks = parseReleaseNoteBlocks(markdown)
        assertEquals(4, blocks.size)

        val orderedList = blocks[0] as ReleaseNoteBlock.BulletList
        assertEquals(2, orderedList.items.size)
        assertEquals("1.", orderedList.items[0].marker)
        assertEquals(
            listOf(
                ReleaseNoteSpan.Text("第一项 "),
                ReleaseNoteSpan.Italic(listOf(ReleaseNoteSpan.Text("斜体"))),
            ),
            orderedList.items[0].spans,
        )
        assertEquals(1, orderedList.items[0].children.size)
        val nestedList = orderedList.items[0].children.single() as ReleaseNoteBlock.BulletList
        assertNull(nestedList.items.single().marker)
        assertEquals(listOf(ReleaseNoteSpan.Text("嵌套子项")), nestedList.items.single().spans)
        assertEquals("2.", orderedList.items[1].marker)

        val quote = blocks[1] as ReleaseNoteBlock.Quote
        assertEquals(
            listOf(ReleaseNoteBlock.Paragraph(listOf(ReleaseNoteSpan.Text("引用说明文字")))),
            quote.blocks,
        )
        assertEquals(ReleaseNoteBlock.Divider, blocks[2])
        assertEquals(ReleaseNoteBlock.CodeBlock("./gradlew assembleRelease"), blocks[3])
    }

    @Test
    fun sanitizesUnsafeLinksAndDoesNotLinkifyInlineCodeUrls() {
        val markdown =
            "查看 [发布页](https://github.com/Lynn-zy/awara/releases)、" +
                "[危险链接](javascript:alert(1))、" +
                "`https://code.example.com/raw` 与 <https://iwara.tv>。"

        val blocks = parseReleaseNoteBlocks(markdown)
        val paragraph = blocks.single() as ReleaseNoteBlock.Paragraph

        assertEquals(
            listOf(
                ReleaseNoteSpan.Text("查看 "),
                ReleaseNoteSpan.Link(
                    text = "发布页",
                    url = "https://github.com/Lynn-zy/awara/releases",
                ),
                ReleaseNoteSpan.Text("、危险链接、"),
                ReleaseNoteSpan.Code("https://code.example.com/raw"),
                ReleaseNoteSpan.Text(" 与 "),
                ReleaseNoteSpan.Link(text = "https://iwara.tv", url = "https://iwara.tv"),
                ReleaseNoteSpan.Text("。"),
            ),
            paragraph.spans,
        )
    }

    @Test
    fun unescapesMarkdownPunctuationAndCleansFormattedLinkLabelsAndMultilineIndent() {
        val markdown =
            """
            - 第一行说明
              续行说明与 \*字面星号\*、\\*双反斜杠* 及 `C:\path\*`
            - 查看 [**Release v1.3.5**](https://github.com/Lynn-zy/awara/releases/tag/v1.3.5)
            """
                .trimIndent()

        val blocks = parseReleaseNoteBlocks(markdown)
        val list = blocks.single() as ReleaseNoteBlock.BulletList
        assertEquals(2, list.items.size)
        assertEquals(
            listOf(
                ReleaseNoteSpan.Text("第一行说明\n续行说明与 *字面星号*、\\"),
                ReleaseNoteSpan.Italic(listOf(ReleaseNoteSpan.Text("双反斜杠"))),
                ReleaseNoteSpan.Text(" 及 "),
                ReleaseNoteSpan.Code("""C:\path\*"""),
            ),
            list.items[0].spans,
        )
        assertEquals(
            listOf(
                ReleaseNoteSpan.Text("查看 "),
                ReleaseNoteSpan.Link(
                    text = "Release v1.3.5",
                    url = "https://github.com/Lynn-zy/awara/releases/tag/v1.3.5",
                ),
            ),
            list.items[1].spans,
        )
    }

    @Test
    fun blankInputProducesEmptyBlockListAndFormatsVersionRangeCleanly() {
        assertTrue(parseReleaseNoteBlocks("   \r\n\t  ").isEmpty())
        assertEquals("v1.3.3 → v1.3.4", formatUpdateVersionRange("1.3.3", "v1.3.4"))
        assertEquals("v1.3.4", formatUpdateVersionRange("1.3.4", "v1.3.4"))
        assertEquals("v1.3.4", formatUpdateVersionRange(null, "1.3.4"))
    }
}
