package me.rerere.awara.data.cdn

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** 验证共享 [parseContentRange] 的解析、大小写容错及越界校验规则。 */
class CdnHttpTest {
    @Test
    fun parsesStandardAndUnknownTotalRanges() {
        assertEquals(
            ParsedContentRange(start = 0L, end = 1023L, total = 4096L),
            parseContentRange("bytes 0-1023/4096"),
        )
        assertEquals(
            ParsedContentRange(start = 1024L, end = 2047L, total = null),
            parseContentRange("BYTES 1024-2047/*"),
        )
        assertEquals(
            ParsedContentRange(start = 10L, end = 20L, total = 30L),
            parseContentRange("  bytes   10 - 20 / 30  "),
        )
    }

    @Test
    fun rejectsMalformedOrOutOfBoundsRanges() {
        assertNull(parseContentRange(null))
        assertNull(parseContentRange(""))
        assertNull(parseContentRange("bytes */4096"))
        assertNull(parseContentRange("items 0-10/20"))
        assertNull(parseContentRange("bytes 20-10/30"))
        assertNull(parseContentRange("bytes 0-10/10"))
        assertNull(parseContentRange("bytes 0-10/5"))
        assertNull(parseContentRange("bytes -1-10/20"))
    }
}
