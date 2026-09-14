package ai.rever.boss.plugin.dynamic.editortab

import androidx.compose.ui.input.key.Key
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * The editor's key dispatch, pinned as a table.
 *
 * The dispatch used to live as branch conditions inside the composable's
 * `when`, where a condition that matches too broadly (isLargeFile/hasSelection
 * alone) silently makes every later branch dead code - that bug once made
 * Cmd+S, Cmd+F, Cmd/Ctrl+I/K, F3 and Escape unreachable on every normal file, and
 * nothing tested the ordering. [editorShortcutFor] is pure, so the table
 * below pins it.
 */
class EditorShortcutDispatchTest {

    /** The defaults of a bare key press on a normal file with no selection. */
    private fun dispatch(
        key: Key,
        isMeta: Boolean = false,
        isInlineAiModifier: Boolean = isMeta,
        isShift: Boolean = false,
        isLargeFile: Boolean = false,
        showSearchBar: Boolean = false,
        editRange: IntRange? = null,
        caretTarget: Int? = null,
    ) = editorShortcutFor(
        key = key,
        isMeta = isMeta,
        isInlineAiModifier = isInlineAiModifier,
        isShift = isShift,
        isLargeFile = isLargeFile,
        showSearchBar = showSearchBar,
        editRange = editRange,
        caretTarget = caretTarget,
    )

    @Test
    fun `Cmd+S saves on a normal file with no selection`() {
        // The regression this file exists for: with the old guard-style
        // branches this key never reached the save branch.
        assertEquals(EditorKeyAction.Save, dispatch(Key.S, isMeta = true))
        // Ctrl+S follows the host convention (isMeta is meta-OR-ctrl at the
        // call site), so the same action.
        assertEquals(EditorKeyAction.Save, dispatch(Key.S, isMeta = true, isLargeFile = false))
    }

    @Test
    fun `Cmd+S is not consumed on a large file`() {
        assertNull(dispatch(Key.S, isMeta = true, isLargeFile = true))
    }

    @Test
    fun `find shortcuts open the search bar in their modes`() {
        assertEquals(EditorKeyAction.ShowFind, dispatch(Key.F, isMeta = true))
        assertEquals(EditorKeyAction.ShowFindReplace, dispatch(Key.H, isMeta = true))
    }

    @Test
    fun `go to line is Cmd+G or Cmd+L`() {
        assertEquals(EditorKeyAction.GoToLine, dispatch(Key.G, isMeta = true))
        assertEquals(EditorKeyAction.GoToLine, dispatch(Key.L, isMeta = true))
    }

    @Test
    fun `Cmd+Y is redo`() {
        assertEquals(EditorKeyAction.Redo, dispatch(Key.Y, isMeta = true))
    }

    @Test
    fun `the platform inline modifier plus I or K starts AI edit on normal files only`() {
        assertEquals(EditorKeyAction.InlineAiEdit, dispatch(Key.I, isMeta = true))
        assertEquals(EditorKeyAction.InlineAiEdit, dispatch(Key.K, isMeta = true))
        assertNull(dispatch(Key.I, isMeta = true, isLargeFile = true))
        assertNull(dispatch(Key.K, isMeta = true, isLargeFile = true))
    }

    @Test
    fun `macOS Ctrl I and K remain available to editor bindings`() {
        assertNull(dispatch(Key.I, isMeta = true, isInlineAiModifier = false))
        assertNull(dispatch(Key.K, isMeta = true, isInlineAiModifier = false))
    }

    @Test
    fun `F3 navigates matches, shift reverses`() {
        assertEquals(EditorKeyAction.FindNext, dispatch(Key.F3))
        assertEquals(EditorKeyAction.FindPrevious, dispatch(Key.F3, isShift = true))
    }

    @Test
    fun `escape closes the search bar only while it is open`() {
        assertEquals(EditorKeyAction.CloseSearch, dispatch(Key.Escape, showSearchBar = true))
        assertNull(dispatch(Key.Escape, showSearchBar = false))
    }

    @Test
    fun `editing shortcuts win over the platform shortcuts`() {
        // The same key the platform uses (e.g. a caret movement that is also a
        // Mac editing shortcut) resolves through the pre-computed results,
        // which sit first in the dispatch.
        assertEquals(EditorKeyAction.MoveCaret(12), dispatch(Key.DirectionLeft, isMeta = true, caretTarget = 12))
        assertEquals(
            EditorKeyAction.EditRange(0..5),
            dispatch(Key.Backspace, isMeta = true, editRange = 0..5),
        )
    }

    @Test
    fun `keys nobody claims are not consumed`() {
        assertNull(dispatch(Key.A))
        assertNull(dispatch(Key.Backspace)) // plain Backspace: the editor's own
        assertNull(dispatch(Key.S)) // S without the meta modifier
    }

    @Test
    fun `with a selection, deletion yields to the editor but caret movement still works`() {
        // The composable pre-computes editRange = null when a selection is
        // present (the editor's delete-selection is right), while
        // caretShortcutTarget still answers its keys:
        assertEquals(
            EditorKeyAction.MoveCaret(0),
            dispatch(Key.DirectionLeft, isMeta = true, caretTarget = 0),
        )
        // ...and nothing else claims Backspace, so the editor's own
        // selection delete runs (the handler returns false).
        assertNull(dispatch(Key.Backspace, isMeta = true))
    }
}
