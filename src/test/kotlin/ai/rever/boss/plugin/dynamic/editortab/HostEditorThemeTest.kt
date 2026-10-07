package ai.rever.boss.plugin.dynamic.editortab

import ai.rever.bosseditor.settings.EditorSettings
import ai.rever.bosseditor.theme.ChromeColors
import ai.rever.bosseditor.theme.EditorChrome
import ai.rever.bosseditor.theme.EditorTheme
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * An editor tab used to render one of six hand-authored themes regardless of the
 * host theme, so a light window contained a dark editor. These pin the derivation
 * that fixes it: which colors come from the host, which stay curated, and which
 * `themeName` values follow the host.
 */
class HostEditorThemeTest {

    /** Blueprint, the default host theme: electric blue on near-black ink. */
    private val blueprint = HostChromeTokens(
        ink = Color(0xFF05070B),
        panel = Color(0xFF0B0F16),
        line = Color(0xFF1B2430),
        textPrimary = Color(0xFFE6EDF6),
        textSecondary = Color(0xFF9FB0C4),
        textMuted = Color(0xFF6B7C90),
        signal = Color(0xFF0F5BFF),
        data = Color(0xFF56C7E0),
        alert = Color(0xFFFF5C5C),
        warn = Color(0xFFFFB84D),
    )

    /** Blueprint Light, the light half: paper floor, dark text. */
    private val blueprintLight = HostChromeTokens(
        ink = Color(0xFFFFFFFF),
        panel = Color(0xFFF3F5F9),
        line = Color(0xFFD6DDE7),
        textPrimary = Color(0xFF10151C),
        textSecondary = Color(0xFF41505F),
        textMuted = Color(0xFF6B7C90),
        signal = Color(0xFF0F5BFF),
        data = Color(0xFF1B7C83),
        alert = Color(0xFFCF222E),
        warn = Color(0xFF95580A),
    )

    @Test
    fun `the floor and the caret come from the host`() {
        val colors = buildHostEditorTheme(blueprint).colors

        assertEquals(blueprint.ink, colors.background)
        assertEquals(blueprint.ink, colors.gutterBackground)
        assertEquals(blueprint.ink, colors.minimapBackground)
        assertEquals(blueprint.textPrimary, colors.text)
        assertEquals(blueprint.signal, colors.caret)
        assertEquals(blueprint.line, colors.gutterBorder)
        assertEquals(blueprint.textMuted, colors.lineNumber)
    }

    @Test
    fun `status colors come from the host, syntax colors stay curated`() {
        val colors = buildHostEditorTheme(blueprint).colors

        assertEquals(blueprint.alert, colors.error)
        assertEquals(blueprint.alert, colors.errorSquiggle)
        assertEquals(blueprint.warn, colors.warningSquiggle)
        assertEquals(blueprint.data, colors.infoSquiggle)
        assertEquals(blueprint.data, colors.hyperlink)

        // The host has no opinion about keywords or strings, and 11 chrome tokens
        // cannot produce a balanced syntax palette - those stay with the base.
        val base = EditorTheme.Dark.colors
        assertEquals(base.keyword, colors.keyword)
        assertEquals(base.string, colors.string)
        assertEquals(base.comment, colors.comment)
        assertEquals(base.function, colors.function)
        assertEquals(base.rainbowBracket1, colors.rainbowBracket1)
    }

    @Test
    fun `a light host yields a light editor`() {
        val theme = buildHostEditorTheme(blueprintLight)

        assertEquals(false, theme.isDark)
        assertEquals(blueprintLight.ink, theme.colors.background)
        // Light syntax colors, or code would be dark-theme pastels on paper.
        assertEquals(EditorTheme.Light.colors.keyword, theme.colors.keyword)
        assertTrue(theme.colors.text.luminance() < theme.colors.background.luminance())
    }

    @Test
    fun `a dark host yields a dark editor`() {
        val theme = buildHostEditorTheme(blueprint)

        assertEquals(true, theme.isDark)
        assertEquals(EditorTheme.Dark.colors.keyword, theme.colors.keyword)
        assertTrue(theme.colors.text.luminance() > theme.colors.background.luminance())
    }

    @Test
    fun `every fill stays distinct from the floor and from every other fill`() {
        // The whole matrix, not a hand-picked subset, over floors and token shapes the
        // derivation can collapse: pure black (worst case for a blend), a monochrome
        // host where signal and warn are one color (all five fills land on one axis),
        // and a signal that clears the surface gate but is far too close to the floor
        // to survive being diluted to 12%.
        val pureBlack = blueprint.copy(ink = Color(0xFF000000), panel = Color(0xFF000000))
        val monochrome = blueprint.copy(warn = blueprint.signal, data = blueprint.signal)
        val weakSignal = blueprint.copy(signal = Color(0xFF0A0F18))
        // The fixture that pins the blend ladder: a signal that only just clears the
        // wash gate (about 2.1:1 on ink) has a small channel range, so fills spaced
        // 0.04 apart in blend factor land within 1/100 of each other. Every fixture
        // above has a high-contrast signal, which hides that.
        val dimSignal = blueprint.copy(signal = Color(0xFF454545), warn = Color(0xFF454545))
        // Both halves of the hardest case at once: a signal that has to fall back to
        // the text color, and a panel sitting almost exactly where a 12% tint of that
        // text color lands (`mix(#05070B, #E6EDF6, 0.12) == #202326`). The fallback
        // alone cannot separate those - the surface has to move.
        val dimSignalOrdinaryPanel = blueprint.copy(
            signal = Color(0xFF060810),
            warn = Color(0xFF060810),
            panel = Color(0xFF202326),
        )
        val fixtures = listOf(
            blueprint, blueprintLight, pureBlack, monochrome, weakSignal, dimSignal,
            dimSignalOrdinaryPanel,
        )

        for (tokens in fixtures) {
            val c = buildHostEditorTheme(tokens).colors
            val what = "floor ${tokens.ink} signal ${tokens.signal} warn ${tokens.warn}"
            val fills = mapOf(
                "current line" to c.currentLineHighlight,
                "occurrences" to c.markOccurrences,
                "bracket" to c.matchedBracketBackground,
                "selection" to c.selectionBackground,
                "search match" to c.searchMatchBackground,
                "current search match" to c.currentSearchMatchBackground,
            )

            for ((name, fill) in fills) {
                assertVisiblyDiffers(c.background, fill, "$name vs the floor: $what")
            }
            // The two inlay chips sit side by side in a line of code, and the parameter
            // one is deliberately the current-line surface, so they are asserted here
            // rather than in the matrix (where "same as the current line" is correct).
            assertVisiblyDiffers(
                c.inlayHintTypeBackground,
                c.inlayHintParameterBackground,
                "inlay chips: $what",
            )
            // Pairwise: a fill the user cannot tell from another fill is as useless as
            // one they cannot tell from the floor.
            val entries = fills.entries.toList()
            for (i in entries.indices) {
                for (j in i + 1 until entries.size) {
                    assertVisiblyDiffers(
                        entries[i].value,
                        entries[j].value,
                        "${entries[i].key} vs ${entries[j].key}: $what",
                    )
                }
            }
        }
    }

    @Test
    fun `code stays readable when the host's text color sits on its own floor`() {
        // The highest-consequence degenerate case, and the pair polarity is taken from:
        // near-equal ink and text means a coin-flip base and code the color of the floor.
        for (floor in listOf(blueprint.ink, blueprintLight.ink)) {
            val tokens = blueprint.copy(ink = floor, textPrimary = floor)
            val c = buildHostEditorTheme(tokens).colors

            assertVisiblyDiffers(c.background, c.text, "text vs floor $floor")
            assertTrue(
                contrastRatio(c.text, c.background) > 4.5f,
                "text on floor $floor was ${contrastRatio(c.text, c.background)}:1",
            )
        }
    }

    @Test
    fun `a floor with no chrome step above it still gets a visible highlight`() {
        // A host theme is free to make panel equal ink, or one step off it, which is
        // worse: it passes an equality guard and paints an invisible highlight.
        val flat = blueprint.copy(panel = blueprint.ink)
        val almostFlat = blueprint.copy(panel = Color(0xFF05070C)) // ink + 1/255 on blue

        for (tokens in listOf(flat, almostFlat)) {
            val colors = buildHostEditorTheme(tokens).colors
            assertVisiblyDiffers(colors.background, colors.currentLineHighlight, "current line")
            assertVisiblyDiffers(colors.background, colors.foldPlaceholderBackground, "fold placeholder")
        }
    }

    @Test
    fun `a brand token that collapses into the floor falls back to the text color`() {
        // Nothing stops a host theme from having a signal or a warn the same color as
        // its floor; a caret or a selection derived from it would be invisible.
        val collapsed = blueprint.copy(
            signal = blueprint.ink,
            warn = blueprint.ink,
            data = blueprint.ink,
            alert = blueprint.ink,
        )
        val c = buildHostEditorTheme(collapsed).colors

        assertVisiblyDiffers(c.background, c.caret, "caret")
        assertVisiblyDiffers(c.background, c.selectionBackground, "selection")
        assertVisiblyDiffers(c.background, c.searchMatchBackground, "search match")
        assertVisiblyDiffers(c.background, c.hyperlink, "hyperlink")
        assertVisiblyDiffers(c.background, c.errorSquiggle, "error squiggle")
    }

    @Test
    fun `a translucent token is composited onto the floor, never mixed raw`() {
        // "Surface = white at 8% over the background" is a normal way to define a
        // chrome step. Read as a non-premultiplied triple it is near-white, so the
        // fold-placeholder hover would come out an opaque near-white slab on ink.
        val translucent = blueprint.copy(panel = Color.White.copy(alpha = 0.08f))
        val c = buildHostEditorTheme(translucent).colors

        assertEquals(1f, c.currentLineHighlight.alpha, "opaque after compositing")
        assertTrue(
            c.currentLineHighlight.luminance() < 0.2f,
            "8% white over ink should stay dark, was ${c.currentLineHighlight}",
        )
        assertVisiblyDiffers(c.background, c.currentLineHighlight, "current line")
    }

    @Test
    fun `polarity comes from the floor against the host's own text color`() {
        // A mid grey sits at ~0.216 relative luminance, so an absolute `> 0.5f` test
        // calls it dark and puts dark-theme pastels on it. The ink/text pair the host
        // already balanced answers correctly at any tone.
        val midToneDarkText = blueprint.copy(
            ink = Color(0xFF808080),
            panel = Color(0xFF8C8C8C),
            textPrimary = Color(0xFF10151C),
            textSecondary = Color(0xFF2A3038),
            textMuted = Color(0xFF454C55),
        )
        val midToneLightText = blueprint.copy(ink = Color(0xFF808080), panel = Color(0xFF6E6E6E))

        assertEquals(false, buildHostEditorTheme(midToneDarkText).isDark, "dark text on mid grey")
        assertEquals(
            EditorTheme.Light.colors.keyword,
            buildHostEditorTheme(midToneDarkText).colors.keyword,
        )
        assertEquals(true, buildHostEditorTheme(midToneLightText).isDark, "light text on mid grey")
    }

    private fun assertVisiblyDiffers(a: Color, b: Color, what: String) {
        assertTrue(visiblyDiffers(a, b), "$what: $a and $b are indistinguishable")
    }

    @Test
    fun `a flat panel and a collapsed text color together still leave a visible line`() {
        // Each degeneracy has its own test above; this is both at once, which is what
        // the fallback ordering has to survive - a current-line tint derived from a
        // text color that was itself rejected is just the floor again.
        for (floor in listOf(blueprint.ink, blueprintLight.ink)) {
            val tokens = blueprint.copy(ink = floor, panel = floor, textPrimary = floor)
            val c = buildHostEditorTheme(tokens).colors

            assertVisiblyDiffers(c.background, c.currentLineHighlight, "current line on $floor")
            assertVisiblyDiffers(c.background, c.foldPlaceholderBackground, "fold placeholder on $floor")
            assertVisiblyDiffers(c.background, c.minimapCurrentLine, "minimap current line on $floor")
            assertVisiblyDiffers(c.background, c.text, "text on $floor")
        }
    }

    @Test
    fun `a muted token that vanishes into the floor still numbers the lines`() {
        // "Muted" is a design choice; muted into its own floor is unreadable. Line
        // numbers, fold arrows and hints all come from these two tokens.
        val collapsed = blueprint.copy(textSecondary = blueprint.ink, textMuted = blueprint.ink)
        val c = buildHostEditorTheme(collapsed).colors

        for ((name, color) in mapOf(
            "line number" to c.lineNumber,
            "fold indicator" to c.foldIndicator,
            "hint squiggle" to c.hintSquiggle,
            "fold placeholder text" to c.foldPlaceholderText,
        )) {
            assertTrue(
                contrastRatio(color, c.background) >= 2f,
                "$name was ${contrastRatio(color, c.background)}:1 on the floor",
            )
        }
    }

    @Test
    fun `seeding at plugin load fills the registry and the chrome`() {
        // What register() does, and what the settings panel depends on: SideEffect
        // cannot cover a first composition that reads a plain, non-snapshot map.
        EditorTheme.unregisterTheme(FOLLOW_HOST_THEME_NAME)
        EditorChrome.reset()
        try {
            assertEquals(EditorTheme.Dark, EditorTheme.forName(FOLLOW_HOST_THEME_NAME))

            publishHostThemeToEditor()

            assertEquals(FOLLOW_HOST_THEME_NAME, EditorTheme.forName(FOLLOW_HOST_THEME_NAME).name)
            assertNotEquals(ChromeColors.Default, EditorChrome.colors)
        } finally {
            EditorTheme.unregisterTheme(FOLLOW_HOST_THEME_NAME)
            EditorChrome.reset()
        }
    }

    @Test
    fun `chrome content on the accent survives a translucent accent`() {
        // contentFor picks light or dark content by luminance, so a translucent accent
        // read as a raw triple flips the choice: 10% white over a dark panel is dark
        // and needs white content, but reads as near-white if the alpha is ignored.
        val translucent = blueprint.copy(signal = Color.White.copy(alpha = 0.10f))
        val chrome = hostChromeColors(translucent)

        assertEquals(Color.White, chrome.onAccent, "content on a nearly-transparent accent")
        assertEquals(blueprint.panel, chrome.surface)
        assertEquals(blueprint.ink, chrome.background)
    }

    @Test
    fun `unpopulated host tokens are not published`() {
        // What register() sees if it ever runs before the host applies its theme:
        // every token is Unspecified, which is alpha 0, and compositing ten of those
        // would seed a pure black theme.
        val unset = HostChromeTokens(
            ink = Color.Unspecified, panel = Color.Unspecified, line = Color.Unspecified,
            textPrimary = Color.Unspecified, textSecondary = Color.Unspecified,
            textMuted = Color.Unspecified, signal = Color.Unspecified,
            data = Color.Unspecified, alert = Color.Unspecified, warn = Color.Unspecified,
        )

        assertEquals(false, unset.allSpecified())
        assertTrue(blueprint.allSpecified())
        assertEquals(false, blueprint.copy(warn = Color.Unspecified).allSpecified())
    }

    @Test
    fun `the run icon tint falls back when it collapses into the gutter`() {
        val gutter = Color(0xFF282A36) // Dracula's gutter
        val text = Color(0xFFF8F8F2)

        // A usable green survives.
        assertEquals(Color(0xFF59A869), runIconTint(Color(0xFF59A869), gutter, text))
        // A green the same color as the gutter would be an invisible click target.
        assertEquals(text, runIconTint(gutter, gutter, text))
        // Translucency is composited, not read as a raw triple: 8% white over the
        // gutter is still dark, so it falls back rather than painting near-white.
        assertEquals(text, runIconTint(Color.White.copy(alpha = 0.08f), gutter, text))
    }

    @Test
    fun `the status bar stays legible on a degenerate host`() {
        val hostTheme = buildHostEditorTheme(blueprint)
        // A translucent accent used to paint the active markdown mode at 10% alpha,
        // making the selected one the least visible of the three; a muted token equal
        // to the bar's own fill made the caret position unreadable.
        val degenerate = blueprint.copy(
            signal = Color.White.copy(alpha = 0.10f),
            textSecondary = blueprint.panel,
            textMuted = blueprint.panel,
            textPrimary = blueprint.panel,
        )

        for ((label, tokens) in mapOf("blueprint" to blueprint, "degenerate" to degenerate)) {
            val bar = statusBarColors(tokens, hostTheme)
            assertEquals(1f, bar.accent.alpha, "$label: accent composited")
            for ((name, color) in mapOf(
                "primary" to bar.primary,
                "secondary" to bar.secondary,
                "muted" to bar.muted,
                "accent" to bar.accent,
                "error" to bar.error,
            )) {
                assertTrue(
                    contrastRatio(color, bar.fill) >= 2f,
                    "$label $name was ${contrastRatio(color, bar.fill)}:1 on the bar",
                )
            }
        }
    }

    @Test
    fun `the status bar follows the editor theme once a fixed one is chosen`() {
        val bar = statusBarColors(blueprint, EditorTheme.Dracula)

        // Welded to the canvas above it, not to the host chrome.
        assertEquals(EditorTheme.Dracula.colors.gutterBackground, bar.fill)
        assertEquals(EditorTheme.Dracula.colors.text, bar.primary)
        assertEquals(EditorTheme.Dracula.colors.caret, bar.accent)
    }

    @Test
    fun `only the derived theme counts as following the host`() {
        assertTrue(followsHostTheme(buildHostEditorTheme(blueprint)))
        assertEquals(false, followsHostTheme(EditorTheme.Dracula))
        assertEquals(false, followsHostTheme(EditorTheme.Dark))
    }

    @Test
    fun `the derived theme carries the follow-host name`() {
        assertEquals(FOLLOW_HOST_THEME_NAME, buildHostEditorTheme(blueprint).name)
    }

    @Test
    fun `mixing does not collapse near the extremes`() {
        // Compose's own lerp interpolates in Oklab, where a 5% step from pure black
        // rounds back to pure black. This is the assertion that fails if the
        // derivation ever swaps the gamma-space mix for it.
        val black = Color(0xFF000000)
        val white = Color(0xFFFFFFFF)

        assertNotEquals(black, mix(black, white, 0.05f))
        assertNotEquals(white, mix(white, black, 0.05f))
        assertEquals(1f, mix(black, white.copy(alpha = 0.2f), 0.5f).alpha)
    }

    @Test
    fun `following the host ignores whatever theme name is on file`() {
        val host = buildHostEditorTheme(blueprint)

        // The switch is on by default and old settings files all carry "Dark", so
        // this is the path an existing install takes.
        assertSame(host, resolveEditorTheme(followHost = true, themeName = "Dark", hostTheme = host))
        assertSame(host, resolveEditorTheme(followHost = true, themeName = "Dracula", hostTheme = host))
        assertSame(host, resolveEditorTheme(followHost = true, themeName = null, hostTheme = host))
    }

    @Test
    fun `the editor default follows the host`() {
        assertEquals(true, EditorSettings().followHostTheme)
    }

    @Test
    fun `an unset theme name follows the host`() {
        val host = buildHostEditorTheme(blueprint)

        assertSame(host, resolveEditorTheme(false, null, host))
        assertSame(host, resolveEditorTheme(false, "", host))
        assertSame(host, resolveEditorTheme(false, "   ", host))
        assertSame(host, resolveEditorTheme(false, FOLLOW_HOST_THEME_NAME, host))
    }

    @Test
    fun `an explicit theme name wins once following is off`() {
        val host = buildHostEditorTheme(blueprint)

        assertEquals(EditorTheme.Dracula, resolveEditorTheme(false, "Dracula", host))
        assertEquals(EditorTheme.Light, resolveEditorTheme(false, "Light", host))
        assertEquals(EditorTheme.Dark, resolveEditorTheme(false, "Dark", host))
        assertEquals(EditorTheme.SolarizedLight, resolveEditorTheme(false, "Solarized Light", host))
    }

    @Test
    fun `a name no theme answers to follows the host rather than falling back to Dark`() {
        val host = buildHostEditorTheme(blueprint)

        // EditorTheme.forName resolves anything unknown to Dark, which is how a
        // typo or a retired theme name would put a dark editor in a light window.
        assertEquals(EditorTheme.Dark, EditorTheme.forName("Gruvbox"))
        assertSame(host, resolveEditorTheme(false, "Gruvbox", host))
    }

    @Test
    fun `every builtin name resolves to itself`() {
        val host = buildHostEditorTheme(blueprint)

        for (name in EditorTheme.availableThemes) {
            assertEquals(name, resolveEditorTheme(false, name, host).name, "builtin $name")
        }
    }
}
