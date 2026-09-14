package ai.rever.boss.plugin.dynamic.editortab

import kotlin.test.Test
import kotlin.test.assertTrue
import kotlin.test.assertNotNull
import kotlin.test.assertEquals
import kotlin.test.assertNull

class AiTabCompletionSanitizeTest {

    private fun ctx(prefix: String = "", suffix: String = "") =
        CompletionContext(prefix, suffix, "kotlin", "Foo.kt")

    // ---- postProcess ----

    @Test
    fun `plain completion passes through with trailing whitespace stripped`() {
        assertEquals("foo(bar)", AiTabCompletionService.postProcess("foo(bar)\n\n", ctx()))
    }

    @Test
    fun `wrapping markdown fence is stripped despite the prompt forbidding it`() {
        assertEquals("val x = 1", AiTabCompletionService.postProcess("```kotlin\nval x = 1\n```", ctx()))
    }

    @Test
    fun `blank reply means show nothing`() {
        assertNull(AiTabCompletionService.postProcess("   \n  ", ctx()))
        assertNull(AiTabCompletionService.postProcess("```\n\n```", ctx()))
    }

    @Test
    fun `reply echoing what already follows the caret is dropped`() {
        assertNull(AiTabCompletionService.postProcess("return result", ctx(suffix = "\n    return result\n}")))
    }

    @Test
    fun `reply repeating the caret line's tail is trimmed to the new part`() {
        // Caret after "    ret" — model replied with the whole word
        assertEquals("urn x", AiTabCompletionService.postProcess("return x", ctx(prefix = "fun f() {\n    ret")))
    }

    @Test
    fun `runaway completion is capped at eight lines`() {
        val raw = (1..20).joinToString("\n") { "line$it" }
        val expected = (1..8).joinToString("\n") { "line$it" }
        assertEquals(expected, AiTabCompletionService.postProcess(raw, ctx()))
    }

    // ---- buildContext ----

    @Test
    fun `defaults keep inline requests small enough for interactive completion`() {
        val defaults = AiCompletionSettingsData()
        assertEquals(1_200, defaults.debounceMs)
        assertEquals(1_600, defaults.maxPrefixChars)
        assertEquals(400, defaults.maxSuffixChars)
        assertEquals(96, defaults.maxTokens)
    }

    private val settings = AiCompletionSettingsData(maxPrefixChars = 20, maxSuffixChars = 10)

    @Test
    fun `caret mid-file honors both char caps and starts the prefix on a whole line`() {
        val text = "aaaa\nbbbb\ncccc\ndddd\neeee\nffff"
        val caret = text.indexOf("ffff")
        val result = AiTabCompletionService.buildContext(text, caret, "kotlin", "/p/Foo.kt", settings)
        // 20-char window starts mid-"bbbb"; truncation drops the partial first line
        assertEquals("cccc\ndddd\neeee\n", result.prefix)
        assertEquals("ffff", result.suffix)
        assertEquals("Foo.kt", result.fileName)
    }

    @Test
    fun `caret at start and end of file`() {
        val text = "hello"
        assertEquals("", AiTabCompletionService.buildContext(text, 0, "k", "f", settings).prefix)
        assertEquals("hello", AiTabCompletionService.buildContext(text, 0, "k", "f", settings).suffix)
        assertEquals("hello", AiTabCompletionService.buildContext(text, 5, "k", "f", settings).prefix)
        assertEquals("", AiTabCompletionService.buildContext(text, 5, "k", "f", settings).suffix)
    }

    // ---- buildRequest ----

    @Test
    fun `request carries tuning knobs and uses the configured default model`() {
        val tuned = AiCompletionSettingsData(timeoutMs = 1234, maxTokens = 77)
        val request = AiTabCompletionService.buildRequest(ctx(prefix = "a", suffix = "b"), tuned)
        assertEquals(1234, request.timeoutMs)
        assertEquals(77, request.maxTokens)
        assertEquals(0f, request.temperature)
        assertEquals(emptyMap(), request.extras)
        assertNull(request.modelOverride)

    }
}

/**
 * When to stop pretending inline completion is coming.
 *
 * Measured on this machine: a realistic 4KB completion prompt through the CLI
 * route takes 11.7-15.9s. Ghost text that lands twelve seconds after you paused
 * is not ghost text, so repeated timeouts are a configuration fact worth
 * saying out loud rather than a transient failure worth retrying in silence.
 */
class AiTabCompletionSlowProviderTest {

    @Test
    fun `a single timeout is not yet a verdict`() {
        assertNull(AiTabCompletionService.slowProviderNotice(0))
        assertNull(AiTabCompletionService.slowProviderNotice(1))
    }

    @Test
    fun `repeated timeouts are explained rather than retried silently`() {
        val notice = AiTabCompletionService.slowProviderNotice(AiTabCompletionService.SLOW_PROVIDER_STRIKES)
        assertNotNull(notice)
        assertTrue(notice.contains("too slow"), "the message has to say why")
    }

    @Test
    fun `the notice persists while the provider keeps timing out`() {
        assertNotNull(AiTabCompletionService.slowProviderNotice(50))
    }
}
