package ai.rever.boss.plugin.dynamic.editortab

import ai.rever.boss.plugin.ui.BossColors
import ai.rever.boss.plugin.ui.BossThemeColors
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.CircularProgressIndicator
import androidx.compose.material.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.isMetaPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * Cmd/Ctrl+I or Cmd/Ctrl+K: one compact card layered over the top of the editor,
 * carrying the prompt and compact review controls. The red/green review itself is rendered in
 * the editor surface from a shadow document. Not a modal.
 *
 * It replaces two stacked Material dialogs (an `AlertDialog` for the prompt,
 * then the library's `RefactorPreviewDialog` for the result), which took focus
 * away from the editor, hid the code being edited, and read as a settings
 * form rather than an editor affordance.
 *
 * Keys, as in Cursor: Enter generates, ⌘/Ctrl+Enter accepts, Esc backs out one
 * step (review → prompt, prompt → closed), ⌘/Ctrl+R regenerates.
 *
 * Colours come from BOSS's semantic tokens, which resolve through the active
 * host theme, so this re-skins with the rest of the app.
 */
@Composable
fun AiInlineEditBar(
    session: AiInlineEditService.Session,
    onPromptChange: (String) -> Unit,
    onSubmit: () -> Unit,
    onAccept: () -> Unit,
    onCancel: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val reviewing = session.done && session.replacement.isNotEmpty()
    val focus = remember { FocusRequester() }
    var promptReceivedFocus by remember { mutableStateOf(false) }

    LaunchedEffect(reviewing) {
        if (!reviewing) {
            runCatching { focus.requestFocus() }
        }
    }

    Column(
        modifier = modifier
            // A compact anchored card on wide editors; Compose constrains this width to the
            // available pane on narrow windows and split views.
            .width(if (reviewing) 360.dp else 560.dp)
            .padding(horizontal = 8.dp, vertical = 6.dp)
            .clip(RoundedCornerShape(6.dp))
            .background(InlineSurface)
            .border(1.dp, InlineBorder, RoundedCornerShape(6.dp))
            .onPreviewKeyEvent { event ->
                if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                val mod = event.isMetaPressed || event.isCtrlPressed
                when {
                    event.key == Key.Escape -> {
                        onCancel()
                        true
                    }
                    reviewing && mod && event.key == Key.Enter -> {
                        onAccept()
                        true
                    }
                    !reviewing && event.key == Key.Enter && !session.busy -> {
                        onSubmit()
                        true
                    }
                    else -> false
                }
            },
    ) {
        InlineHeader(session = session, reviewing = reviewing)

        if (!reviewing) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 8.dp, end = 6.dp, top = 2.dp, bottom = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(modifier = Modifier.weight(1f)) {
                    BasicTextField(
                        value = session.prompt,
                        onValueChange = onPromptChange,
                        singleLine = true,
                        enabled = !session.busy,
                        textStyle = TextStyle(
                            color = InlineForeground,
                            fontSize = 13.sp,
                            fontFamily = FontFamily.Monospace,
                        ),
                        cursorBrush = SolidColor(InlineForeground),
                        modifier = Modifier
                            .fillMaxWidth()
                            .focusRequester(focus)
                            .onFocusChanged { state ->
                                val hasReceivedFocus = promptReceivedFocus || state.isFocused
                                if (shouldDismissEmptyInlineEdit(
                                        hasReceivedFocus = hasReceivedFocus,
                                        isFocused = state.isFocused,
                                        prompt = session.prompt,
                                        busy = session.busy,
                                        reviewing = reviewing,
                                    )
                                ) {
                                    onCancel()
                                }
                                promptReceivedFocus = hasReceivedFocus
                            },
                    )
                    if (session.prompt.isEmpty()) {
                        Text(
                            text = "Edit selection…",
                            fontSize = 13.sp,
                            fontFamily = FontFamily.Monospace,
                            color = InlineMuted,
                            maxLines = 1,
                        )
                    }
                }
                Spacer(Modifier.width(6.dp))
                if (session.busy) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(12.dp),
                        strokeWidth = 1.5.dp,
                        color = InlineAccent,
                    )
                } else {
                    InlineAction("Generate", enabled = session.prompt.isNotBlank(), onClick = onSubmit)
                }
            }

            if (session.busy && session.streaming.isNotEmpty()) {
                InlineCode(
                    text = session.streaming.takeLast(STREAM_TAIL),
                    modifier = Modifier.heightIn(max = 120.dp),
                )
            }
        } else {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 8.dp, end = 6.dp, top = 2.dp, bottom = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = "Ctrl/⌘+Enter accept · Esc reject",
                    fontSize = 10.sp,
                    fontFamily = FontFamily.Monospace,
                    color = InlineMuted,
                    modifier = Modifier.weight(1f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                InlineAction("Reject", enabled = true, onClick = onCancel, tone = InlineRemoved)
                Spacer(Modifier.width(4.dp))
                InlineAction("Accept", enabled = true, onClick = onAccept, tone = InlineAdded)
            }
        }

        session.error?.let { message ->
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(InlineRemoved.copy(alpha = 0.12f))
                    .padding(horizontal = 8.dp, vertical = 4.dp),
            ) {
                Text(
                    text = message,
                    fontSize = 11.sp,
                    color = InlineRemoved,
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

@Composable
private fun InlineHeader(session: AiInlineEditService.Session, reviewing: Boolean) {
    val lines = session.endLine - session.startLine + 1
    val label =
        when {
            reviewing -> {
                val (added, removed) = AiInlineDiff.stats(
                    AiInlineDiff.of(session.selectionText, session.replacement),
                )
                "AI EDIT  +$added −$removed"
            }
            session.busy -> "AI EDIT  generating…"
            else -> "AI EDIT  $lines ${if (lines == 1) "line" else "lines"}"
        }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 8.dp, end = 8.dp, top = 5.dp, bottom = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = label,
            fontSize = 10.sp,
            fontFamily = FontFamily.Monospace,
            fontWeight = FontWeight.SemiBold,
            letterSpacing = 1.0.sp,
            color = InlineMuted,
            maxLines = 1,
        )
        Spacer(Modifier.weight(1f))
        if (session.language.isNotEmpty()) {
            Text(
                text = session.language,
                fontSize = 10.sp,
                fontFamily = FontFamily.Monospace,
                color = InlineMuted,
                maxLines = 1,
            )
        }
    }
}

@Composable
private fun InlineCode(text: String, modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp)
            .clip(RoundedCornerShape(3.dp))
            .background(InlineInset)
            .verticalScroll(rememberScrollState()),
    ) {
        Text(
            text = text,
            fontSize = 11.sp,
            fontFamily = FontFamily.Monospace,
            color = InlineMuted,
            modifier = Modifier.padding(6.dp),
        )
    }
}

/** A compact text action; the card has no room for a Material Button. */
@Composable
private fun InlineAction(
    label: String,
    enabled: Boolean,
    onClick: () -> Unit,
    tone: Color? = null,
) {
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    val base = tone ?: InlineAccent
    Box(
        modifier = Modifier
            .height(20.dp)
            .clip(RoundedCornerShape(3.dp))
            .hoverable(interaction, enabled = enabled)
            .background(
                when {
                    !enabled -> base.copy(alpha = 0.10f)
                    hovered -> base.copy(alpha = 0.30f)
                    else -> base.copy(alpha = 0.18f)
                },
            )
            .then(if (enabled) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(horizontal = 7.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = label,
            fontSize = 11.sp,
            fontWeight = FontWeight.Medium,
            color = if (enabled) base else base.copy(alpha = 0.45f),
            maxLines = 1,
        )
    }
}

private const val STREAM_TAIL = 600

/** Click-away only dismisses a pristine prompt, after focus was acquired at least once. */
internal fun shouldDismissEmptyInlineEdit(
    hasReceivedFocus: Boolean,
    isFocused: Boolean,
    prompt: String,
    busy: Boolean,
    reviewing: Boolean,
): Boolean = hasReceivedFocus && !isFocused && prompt.isEmpty() && !busy && !reviewing

// BOSS semantic tokens - getters in the host, so the card follows the theme.
private val InlineSurface: Color get() = BossColors.contextMenuBackground
private val InlineInset: Color get() = BossThemeColors.BackgroundColor
private val InlineBorder: Color get() = BossColors.contextMenuBorder
private val InlineForeground: Color get() = BossThemeColors.TextPrimary
private val InlineMuted: Color get() = BossThemeColors.TextMuted
private val InlineAccent: Color get() = BossThemeColors.AccentColor
private val InlineAdded: Color get() = BossThemeColors.SuccessColor
private val InlineRemoved: Color get() = BossThemeColors.ErrorColor
