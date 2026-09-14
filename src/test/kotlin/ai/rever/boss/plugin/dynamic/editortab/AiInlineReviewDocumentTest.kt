package ai.rever.boss.plugin.dynamic.editortab

import kotlin.test.Test
import kotlin.test.assertEquals

class AiInlineReviewDocumentTest {
    @Test
    fun `partial line edit shows complete old and proposed lines`() {
        val content = "val answer = oldValue\nprintln(answer)\n"
        val start = content.indexOf("oldValue")
        val review = buildAiInlineReviewDocument(content, start, start + "oldValue".length, "compute()")

        assertEquals(
            "val answer = oldValue\nval answer = compute()\nprintln(answer)\n",
            review.text,
        )
        assertEquals(0, review.removedLineStart)
        assertEquals(1, review.removedLineCount)
        assertEquals(1, review.addedLineStart)
        assertEquals(1, review.addedLineCount)
        assertEquals(1, review.controlAnchorLine)
    }

    @Test
    fun `multiline edit does not duplicate the untouched following line`() {
        val content = "one\ntwo\nthree\nfour\n"
        val start = content.indexOf("two")
        val end = content.indexOf("four")
        val review = buildAiInlineReviewDocument(content, start, end, "second\nthird\n")

        assertEquals("one\ntwo\nthree\nsecond\nthird\nfour\n", review.text)
        assertEquals(1, review.removedLineStart)
        assertEquals(2, review.removedLineCount)
        assertEquals(3, review.addedLineStart)
        assertEquals(2, review.addedLineCount)
    }

    @Test
    fun `last line without newline remains a valid two-sided hunk`() {
        val content = "before\nold"
        val start = content.indexOf("old")
        val review = buildAiInlineReviewDocument(content, start, content.length, "new")

        assertEquals("before\nold\nnew", review.text)
        assertEquals(1, review.removedLineStart)
        assertEquals(1, review.removedLineCount)
        assertEquals(2, review.addedLineStart)
        assertEquals(1, review.addedLineCount)
    }
}
