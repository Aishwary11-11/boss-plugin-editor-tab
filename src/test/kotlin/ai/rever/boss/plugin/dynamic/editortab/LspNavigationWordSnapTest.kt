package ai.rever.boss.plugin.dynamic.editortab

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Cmd+Click in whitespace must still resolve the nearest symbol: the position
 * sent to a language server is snapped onto the nearest word character (LSP
 * convention: letters, digits, underscore), preferring the word that ends
 * just left of the pointer, then the word that starts just right.
 *
 * All indices below are 0-based character offsets; spellings in comments show
 * the layout, e.g. "foo  bar" = f0 o1 o2 ' '3 ' '4 b5 a6 r7.
 */
class LspNavigationWordSnapTest {

    private fun snap(content: String, offset: Int) = LspNavigation.snapToNearestWord(content, offset)

    @Test
    fun `an offset on a word char is unchanged`() {
        // "def foo():": d0 e1 f2 ' '3 f4 o5 o6 (7 )8 :9
        assertEquals(4, snap("def foo():", 4))
        assertEquals(5, snap("def foo():", 5))
    }

    @Test
    fun `a trailing colon snaps back onto the word`() {
        // ':' at 9 is not a word char -> the word ending just left, 'o' at 6
        assertEquals(6, snap("def foo():", 9))
    }

    @Test
    fun `whitespace right of a word resolves to the words last char`() {
        // "foo  x": f0 o1 o2 ' '3 ' '4 x5 -> space at 4 snaps left to 'o' at 2
        assertEquals(2, snap("foo  x", 4))
        // "foo bar": f0 o1 o2 ' '3 b4 a5 r6 -> space at 3 snaps left to 'o' at 2
        assertEquals(2, snap("foo bar", 3))
    }

    @Test
    fun `whitespace left of a word resolves to the words first char`() {
        // "  bar": ' '0 ' '1 b2 a3 r4 -> leading space snaps right to 'b' at 2
        assertEquals(2, snap("  bar", 0))
        // on the word itself it stays
        assertEquals(4, snap("foo bar", 4))
    }

    @Test
    fun `punctuation next to a word snaps to the word`() {
        // "foo()bar": f0 o1 o2 (3 )4 b5 a6 r7 -> '(' at 3 snaps left to 'o' at 2
        assertEquals(2, snap("foo()bar", 3))
        // and ')' at 4 snaps left to 'o' at 2 as well (left preference)
        assertEquals(2, snap("foo()bar", 4))
        // 'a' at 6 is a word char -> unchanged
        assertEquals(6, snap("foo()bar", 6))
    }

    @Test
    fun `left preference between two words`() {
        // "foo  bar": the middle spaces (3, 4) resolve to the LEFT word 'foo'
        assertEquals(2, snap("foo  bar", 3))
        assertEquals(2, snap("foo  bar", 4))
        // but the space just after "bar" (7) snaps left to 'r' at 6
        assertEquals(6, snap("foo bar ", 7))
    }

    @Test
    fun `end of line after a word snaps to the last word char`() {
        // "return value\nnext": r0 e1 t2 u3 r4 n5 ' '6 v7 a8 l9 u10 e11 \n12 n13
        val content = "return value\nnext"
        assertEquals(11, snap(content, 12)) // the '\n' itself snaps back onto 'e'
        assertEquals(13, snap(content, 13)) // 'n' of next, unchanged
    }

    @Test
    fun `never crosses a line boundary`() {
        // "aaa\n   \nbbb": a0 a1 a2 \n3 ' '4 ' '5 ' '6 \n7 b8 b9 b10
        val content = "aaa\n   \nbbb"
        // an all-blank middle line has no word at all -> unchanged
        assertEquals(5, snap(content, 5))
        // the '\n' after "aaa" cannot reach into the next line's "bbb"
        assertEquals(2, snap("aaa\nbbb", 3))
    }

    @Test
    fun `eof without trailing newline snaps left`() {
        assertEquals(2, snap("foo", 3))
    }

    @Test
    fun `all whitespace content is unchanged`() {
        assertEquals(3, snap("     ", 3))
    }

    @Test
    fun `underscore and digits count as word chars`() {
        assertEquals(0, snap("_x", 0))
        assertEquals(1, snap("1a", 1))
        assertEquals(0, snap("a ", 1)) // trailing space snaps left to 'a'
    }

    @Test
    fun `offsetToPosition agrees with the snapped offset`() {
        // "def foo():\n    return foo"
        val content = "def foo():\n    return foo"
        // Cmd+Click the space after the colon (offset 9) -> 'o' of foo at 6
        val snapped = snap(content, 9)
        assertEquals(6, snapped)
        val pos = LspNavigation.offsetToPosition(content, snapped)
        assertEquals(0, pos.line)
        assertEquals(6, pos.character)
    }

    // ==================== bounded snap (the hover path) ====================
    //
    // Hover snaps with maxDistance = HOVER_SNAP_MAX_DISTANCE: the pointer merely
    // idled here, so a scan that runs back to lineStart (the click behaviour)
    // would raise the last token's docs for a pointer parked in the blank area
    // right of the line. The bound catches "a character or two off" and nothing
    // more; Cmd+Click stays unbounded (asserted in the last test).

    private fun hoverSnap(content: String, offset: Int) =
        LspNavigation.snapToNearestWord(content, offset, LspNavigation.HOVER_SNAP_MAX_DISTANCE)

    @Test
    fun `hover snap on a word char is unchanged`() {
        // "def foo():": d0 e1 f2 ' '3 f4 o5 o6 (7 )8 :9
        assertEquals(4, hoverSnap("def foo():", 4))
    }

    @Test
    fun `hover snap crosses at most a few non-word chars`() {
        // ':' at 9 is exactly HOVER_SNAP_MAX_DISTANCE left of 'o' at 6 -> snaps
        assertEquals(6, hoverSnap("def foo():", 9))
        // "foo bar": f0 o1 o2 ' '3 b4 a5 r6 -> the space at 3 snaps left to 'o'
        assertEquals(2, hoverSnap("foo bar", 3))
        // "foo()": f0 o1 o2 (3 )4 -> resting on ')' still finds the word
        assertEquals(2, hoverSnap("foo()", 4))
    }

    @Test
    fun `a gap longer than the hover bound snaps nowhere`() {
        // "a        b": a0, b9 - the middle is more than 3 chars from both
        assertEquals(4, hoverSnap("a        b", 4))
    }

    @Test
    fun `the blank area right of a lines text no longer snaps left across the line`() {
        // "return value    ": ...e11 ' '12 ' '13 ' '14 ' '15
        // Parked just after the text (distance 1) still snaps...
        assertEquals(11, hoverSnap("return value    ", 12))
        // ... but the far end of the blank area resolves to nothing, so no
        // tooltip rises from nowhere.
        assertEquals(15, hoverSnap("return value    ", 15))
    }

    @Test
    fun `the hover bound never crosses a line boundary`() {
        // "aaa\n   \nbbb": a0 a1 a2 \n3 ' '4 ' '5 ' '6 \n7 b8 b9 b10
        // the all-blank middle line has no word within reach -> unchanged
        assertEquals(5, hoverSnap("aaa\n   \nbbb", 5))
    }

    @Test
    fun `the click path stays unbounded while hover is bounded`() {
        // Same input, same pointer: the click snaps 4 chars left, the hover
        // refuses to. That asymmetry is deliberate - a click anywhere on the
        // line is an explicit gesture, an idle pointer is not.
        assertEquals(0, snap("a        b", 4))
        assertEquals(4, hoverSnap("a        b", 4))
    }

    @Test
    fun `the hover bound is symmetric - four columns either way gives up, three snaps`() {
        // Pins the off-by-one the right scan used to have: its loop can exit one
        // char PAST the bound, and the post-loop check must still enforce it.
        assertEquals(4, hoverSnap("a    ", 4))  // word 4 left of the pointer: give up
        assertEquals(0, hoverSnap("    a", 0))  // word 4 right of the pointer: give up (used to snap)
        assertEquals(0, hoverSnap("a   ", 3))   // word exactly 3 left: snaps
        assertEquals(3, hoverSnap("   a", 0))   // word exactly 3 right: snaps
    }

    @Test
    fun `an offset past a trailing newline resolves nowhere`() {
        // "foo\n": f0 o1 o2 \n3; offset 4 sits after the final newline. The
        // "line" there is the empty one - no word on either side, so the offset
        // comes back unchanged (and both scans must no-op, since lineStart ends
        // up past lineEnd for this degenerate line).
        assertEquals(4, snap("foo\n", 4))
        assertEquals(4, hoverSnap("foo\n", 4))
    }
}
