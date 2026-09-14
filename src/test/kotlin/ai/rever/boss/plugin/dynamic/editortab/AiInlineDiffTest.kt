package ai.rever.boss.plugin.dynamic.editortab

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The diff the inline AI review card renders. Pinned because the whole point of
 * showing a diff instead of the raw replacement is that the changed lines are
 * tight - a diff that reports the entire selection as replaced tells the user
 * nothing they can act on.
 */
class AiInlineDiffTest {

    private fun kinds(before: String, after: String) =
        AiInlineDiff.of(before, after).map { it.kind }

    private fun rendered(before: String, after: String) =
        AiInlineDiff.of(before, after).joinToString("\n") { l ->
            val sign = when (l.kind) {
                AiInlineDiff.Kind.ADDED -> "+"
                AiInlineDiff.Kind.REMOVED -> "-"
                AiInlineDiff.Kind.CONTEXT -> " "
            }
            "$sign${l.text}"
        }

    @Test
    fun `an unchanged rewrite is all context`() {
        assertEquals(
            listOf(AiInlineDiff.Kind.CONTEXT, AiInlineDiff.Kind.CONTEXT),
            kinds("a\nb", "a\nb"),
        )
    }

    @Test
    fun `a one-line change keeps the surrounding lines as context`() {
        val out = rendered("fun a() {\n  old()\n}", "fun a() {\n  new()\n}")
        assertEquals(" fun a() {\n-  old()\n+  new()\n }", out)
    }

    @Test
    fun `an insertion in the middle is reported as an addition only`() {
        val out = rendered("a\nc", "a\nb\nc")
        assertEquals(" a\n+b\n c", out)
    }

    @Test
    fun `a deletion in the middle is reported as a removal only`() {
        val out = rendered("a\nb\nc", "a\nc")
        assertEquals(" a\n-b\n c", out)
    }

    @Test
    fun `an empty replacement removes every line`() {
        assertTrue(AiInlineDiff.of("a\nb", "").all { it.kind == AiInlineDiff.Kind.REMOVED })
    }

    @Test
    fun `a trailing newline alone is not a change`() {
        // String.lines() yields a final empty element, which would otherwise
        // render as an added or removed blank line.
        assertTrue(AiInlineDiff.of("a\nb", "a\nb\n").all { it.kind == AiInlineDiff.Kind.CONTEXT })
    }

    @Test
    fun `stats count additions and removals, not context`() {
        val (added, removed) = AiInlineDiff.stats(AiInlineDiff.of("a\nb\nc", "a\nx\ny\nc"))
        assertEquals(2, added)
        assertEquals(1, removed)
    }

    @Test
    fun `a reordered block still pairs the lines it can`() {
        // LCS keeps the shared lines as context instead of calling everything
        // replaced, which is the difference between a readable review and a
        // wall of red and green.
        val out = AiInlineDiff.of("a\nb\nc\nd", "a\nc\nb\nd")
        assertTrue(out.count { it.kind == AiInlineDiff.Kind.CONTEXT } >= 3, rendered("a\nb\nc\nd", "a\nc\nb\nd"))
    }

    @Test
    fun `a large rewrite degrades instead of allocating an enormous table`() {
        // The LCS table is O(n*m); past the budget it must fall back rather
        // than build millions of cells while the user waits.
        val before = (1..900).joinToString("\n") { "old line $it" }
        val after = (1..900).joinToString("\n") { "new line $it" }

        val out = AiInlineDiff.of(before, after)

        assertTrue(out.isNotEmpty())
        assertTrue(out.none { it.kind == AiInlineDiff.Kind.CONTEXT }, "no line is shared, so none is context")
    }

    @Test
    fun `output is capped so one card cannot render unbounded lines`() {
        val before = (1..5000).joinToString("\n") { "a$it" }
        val after = (1..5000).joinToString("\n") { "b$it" }

        assertTrue(AiInlineDiff.of(before, after).size <= AiInlineDiff.MAX_LINES)
    }
}
