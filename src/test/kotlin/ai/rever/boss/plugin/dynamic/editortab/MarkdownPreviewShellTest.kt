package ai.rever.boss.plugin.dynamic.editortab

import java.io.File
import java.nio.file.FileSystems
import java.nio.file.Files
import java.nio.file.attribute.PosixFilePermissions
import java.util.Base64
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Covers the preview page the plugin hands to the browser: what the page is
 * allowed to do, and how markdown-derived HTML reaches its DOM.
 *
 * The page's behaviour is asserted separately, against a real DOM, by
 * `src/test/js/preview-dom.test.mjs`, which loads the page in a headless browser
 * — see that file's header for how to run it.
 */
class MarkdownPreviewShellTest {

    private val nonce = "TESTNONCEvalue123"

    private fun shell(
        markdown: String = "# hello",
        baseUri: String = "file:///docs/",
        dark: Boolean = false
    ): String = buildPreviewHtml(
        initialB64 = Base64.getEncoder().encodeToString(markdown.toByteArray()),
        baseUri = baseUri,
        markedUri = "file:///lib/marked.umd.js",
        mermaidUri = "file:///lib/mermaid.min.js",
        purifyUri = "file:///lib/purify.min.js",
        nonce = nonce,
        dark = dark
    )

    /** `content` of the page's Content-Security-Policy meta tag, split by directive. */
    private fun cspDirectives(html: String): Map<String, String> {
        val meta = Regex("""<meta http-equiv="Content-Security-Policy" content="([^"]*)">""")
            .find(html)
        assertNotNull(meta, "page carries no Content-Security-Policy")
        return meta.groupValues[1]
            .split(';')
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .associate { directive ->
                val name = directive.substringBefore(' ')
                name to directive.removePrefix(name).trim()
            }
    }

    // ---------- how markdown-derived HTML reaches the DOM ----------

    /**
     * Scope note: this greps **the shell**, which is the code that decides how
     * markdown-derived HTML reaches the document. It says nothing about the
     * vendored libraries — `mermaid.min.js` writes markup strings internally, as
     * its own business. What keeps *mermaid's* output from carrying markup a
     * diagram supplied is `htmlLabels: false` plus the policy plus `scrubRendered`,
     * asserted separately below and in `preview-dom.test.mjs`.
     */
    @Test
    fun `the shell never assigns a markup string to the DOM`() {
        val html = shell()
        listOf("innerHTML", "outerHTML", "insertAdjacentHTML", "document.write", "createContextualFragment")
            .forEach { sink ->
                assertFalse(
                    html.contains(sink),
                    "preview page writes markup through $sink; markdown-derived HTML must be " +
                        "attached as sanitized nodes instead"
                )
            }
    }

    @Test
    fun `marked output is sanitized before it is attached`() {
        val html = shell()
        val parses = Regex("""marked\.parse\(""").findAll(html).count()
        assertEquals(1, parses, "expected exactly one marked.parse call to audit")
        // Whitespace-tolerant: the guarantee is that marked's output is the
        // sanitizer's argument, not the exact formatting of the call.
        assertTrue(
            Regex("""DOMPurify\.sanitize\(\s*marked\.parse\(""").containsMatchIn(html),
            "marked's output must be handed straight to DOMPurify"
        )
        assertTrue(html.contains("RETURN_DOM_FRAGMENT: true"), "sanitizer must return nodes, not markup")
        assertTrue(html.contains("USE_PROFILES: { html: true }"), "sanitizer must use the HTML-only profile")
        assertTrue(html.contains("FORBID_TAGS: ['style', 'form']"))
    }

    @Test
    fun `render fails closed when the sanitizer is unavailable`() {
        val html = shell()
        assertTrue(
            html.contains("if (!window.DOMPurify || !window.DOMPurify.isSupported)"),
            "the render path must refuse to run without a working sanitizer"
        )
        assertTrue(html.contains("throw new Error('HTML sanitizer unavailable')"))
    }

    @Test
    fun `the render error path writes text, not markup`() {
        val html = shell()
        assertTrue(html.contains("pre.textContent = message"))
        assertTrue(html.contains("showRenderError('Markdown render error: ' + String(e))"))
    }

    @Test
    fun `the sanitizer ships as a plugin resource at a pinned version`() {
        val stream = javaClass.classLoader.getResourceAsStream("markdown-preview/purify.min.js")
        assertNotNull(stream, "markdown-preview/purify.min.js is missing from plugin resources")
        val banner = stream.use { it.readBytes() }.decodeToString().lineSequence().first()
        assertTrue(
            banner.contains("DOMPurify 3.4.11"),
            "vendored sanitizer banner should pin the version; was: $banner"
        )
    }

    // ---------- Content-Security-Policy ----------

    @Test
    fun `policy denies by default and allows only the preview's own machinery`() {
        val directives = cspDirectives(shell())
        assertEquals("'none'", directives["default-src"])
        assertEquals("'nonce-$nonce'", directives["script-src"])
        // mermaid styles a diagram by inserting a <style> element into its SVG, and
        // the shell's own stylesheet is inline.
        assertEquals("'unsafe-inline'", directives["style-src"])
        // Relative image paths resolve against <base href> to file: URLs.
        assertEquals("file: data:", directives["img-src"])
        assertEquals("file: data:", directives["media-src"])
        assertEquals("file:", directives["base-uri"])
        listOf("font-src", "connect-src", "frame-src", "object-src", "form-action").forEach {
            assertEquals("'none'", directives[it], "$it should be denied")
        }
    }

    @Test
    fun `policy permits no remote load and no inline script`() {
        val directives = cspDirectives(shell())
        val scriptSrc = directives.getValue("script-src")
        assertFalse(scriptSrc.contains("unsafe-inline"), "inline scripts and on… handlers must stay inert")
        assertFalse(scriptSrc.contains("unsafe-eval"))
        directives.forEach { (name, value) ->
            listOf("http:", "https:", "ws:", "wss:", "//", "*").forEach { remote ->
                assertFalse(
                    value.contains(remote),
                    "$name allows a remote source ($remote); the preview loads nothing over the network"
                )
            }
        }
    }

    /**
     * No count assertion here on purpose: the guarantee is that *every* script the
     * page carries is nonced, which holds for any number of them. The count is
     * load-bearing only in the interpolation test below, where it is what shows an
     * injected script tag did not appear.
     */
    @Test
    fun `every script the page carries is nonced`() {
        val html = shell()
        val scriptTags = Regex("""<script(\s[^>]*)?>""").findAll(html).toList()
        assertTrue(scriptTags.isNotEmpty(), "page carries no script tags at all")
        scriptTags.forEach { tag ->
            assertTrue(
                tag.value.contains("""nonce="$nonce""""),
                "script tag without the page nonce cannot run: ${tag.value}"
            )
        }
    }

    @Test
    fun `each page gets a fresh nonce`() {
        val nonces = (1..200).map { newScriptNonce() }
        assertEquals(nonces.size, nonces.toSet().size, "nonces must not repeat across pages")
        nonces.forEach {
            assertTrue(it.length >= 16, "nonce too short to be unguessable: $it")
            assertTrue(Regex("""^[A-Za-z0-9_-]+$""").matches(it), "nonce is not a CSP base64 value: $it")
        }
    }

    // ---------- template interpolation ----------

    @Test
    fun `interpolated attribute values cannot end their attribute`() {
        val html = shell(baseUri = """file:///docs/a"><script>x</script><b foo='y'/""")
        assertFalse(html.contains("""<base href="file:///docs/a">"""), "base attribute was cut short")
        assertTrue(html.contains("&quot;"), "quote in an interpolated URI should be escaped")
        assertTrue(html.contains("&lt;script&gt;"), "angle brackets in an interpolated URI should be escaped")
        // Still exactly the four script tags the page is supposed to have.
        assertEquals(4, Regex("""<script(\s[^>]*)?>""").findAll(html).count())
    }

    // ---------- mermaid ----------

    @Test
    fun `mermaid is initialized at its strict security level`() {
        val html = shell()
        assertTrue(
            html.contains("securityLevel: 'strict'"),
            "mermaid writes its SVG into the page itself, so its own escaping must be on"
        )
    }

    @Test
    fun `mermaid draws labels as svg text rather than html`() {
        val html = shell()
        // Verified in preview-dom.test.mjs: with HTML labels on, 'strict' still lets
        // a quoted label become live HTML in a foreignObject.
        assertTrue(html.contains("htmlLabels: false"), "label HTML must be off globally")
        assertTrue(
            html.contains("flowchart: { htmlLabels: false }"),
            "the flowchart default re-enables label HTML, so it must be set too"
        )
    }

    @Test
    fun `rendered diagrams are scrubbed on both mermaid outcomes`() {
        val html = shell()
        assertTrue(
            Regex("""mermaid\.run\(\{\s*nodes:\s*diagrams\s*\}\)\.then\(scrub,\s*scrub\)""")
                .containsMatchIn(html),
            "mermaid's output must be scrubbed whether the run resolves or rejects"
        )
        assertTrue(html.contains("function scrubRendered(root)"))
        // Scoped to the diagram roots, not the whole article: that is the output
        // path the scrub exists for, and it keeps a document with one mermaid fence
        // from re-walking every element in it on every debounced re-render.
        assertTrue(
            html.contains("var diagrams = el.querySelectorAll('pre.mermaid')") &&
                html.contains("diagrams.forEach(function(d) { scrubRendered(d); })"),
            "the scrub must run over the diagram roots rather than all of #content"
        )
    }

    @Test
    fun `the scrub drops the elements that can re-point an attribute later`() {
        val html = shell()
        // SMIL can set an attribute after a one-pass scrub has been over the tree,
        // so <animate>/<set> and friends are removed outright. Verified against a
        // real DOM in preview-dom.test.mjs.
        listOf("'animate'", "'animatemotion'", "'animatetransform'", "'set'").forEach { name ->
            assertTrue(html.contains("name === $name"), "scrub does not remove $name elements")
        }
    }

    @Test
    fun `the scrub tests the scheme with url whitespace removed`() {
        val html = shell()
        // A browser strips TAB/LF/CR out of a URL before parsing its scheme, so
        // `java<TAB>script:` runs while `/^javascript:/` does not match it.
        assertTrue(
            html.contains("""replace(/[\u0000-\u0020]/g, '')"""),
            "the scheme test must run against a value with control characters removed"
        )
        assertTrue(html.contains("""/^(?:javascript|vbscript):/i"""))
    }

    @Test
    fun `theme choice does not change the policy`() {
        assertEquals(cspDirectives(shell(dark = false)), cspDirectives(shell(dark = true)))
    }

    // ---------- what a clicked link may reach ----------

    @Test
    fun `only outward-pointing schemes are handed to the desktop`() {
        listOf(
            "https://example.invalid/x",
            "http://example.invalid/x",
            "HTTPS://example.invalid/x",
            "mailto:someone@example.invalid"
        ).forEach { assertTrue(isBrowsableLink(it), "$it should open in the system browser") }
    }

    @Test
    fun `a resolved local path is not handed to the desktop`() {
        // Every link in the preview is routed through the new-tab callback, and a
        // relative href — which DOMPurify keeps by design — has already resolved
        // against <base href> into an absolute file: URL by the time it arrives.
        // Desktop.browse on one of these is a local-file open, and on Windows goes
        // through ShellExecute, which for some target types launches rather than
        // views.
        listOf(
            "file:///Users/someone/.ssh/id_rsa",
            "file:///Users/someone/docs/setup.exe",
            "file:///etc/passwd",
            "jar:file:///tmp/x.jar!/y",
            "smb://host/share/x",
            "javascript:alert(1)",
            "data:text/html,<script>alert(1)</script>",
            "vbscript:msgbox(1)",
            "custom-handler:payload",
            "",
            "not a uri at all",
            "./OTHER.md"
        ).forEach { assertFalse(isBrowsableLink(it), "$it must not be handed to the desktop") }
    }

    // ---------- where a clicked link goes ----------

    @Test
    fun `outward schemes route to the system browser`() {
        listOf("https://example.invalid/x", "http://example.invalid/x", "mailto:a@example.invalid")
            .forEach {
                assertEquals(
                    PreviewLinkRoute.SystemBrowser(it), previewLinkRoute(it, null),
                    "$it should go to the system browser"
                )
            }
    }

    @Test
    fun `a link to a neighbouring file opens inside BOSS`() {
        // The whole point of the local-file route: a relative link, which <base href>
        // has already turned into a file: URL, reaches the editor as a path. The OS is
        // never asked what the file means, so `[docs](./setup.exe)` cannot launch.
        val root = Files.createTempDirectory("boss-preview-link-test").toFile().canonicalFile
        val neighbour = File(root, "OTHER.md").apply { writeText("# other\n") }
        val exe = File(root, "setup.exe").apply { writeText("MZ") }
        try {
            assertEquals(
                PreviewLinkRoute.LocalFile(neighbour.path),
                previewLinkRoute(neighbour.toURI().toString(), root.path)
            )
            assertEquals(
                PreviewLinkRoute.LocalFile(exe.path),
                previewLinkRoute(exe.toURI().toString(), root.path),
                "an executable must route into BOSS as a file, never to the desktop"
            )
        } finally {
            neighbour.delete()
            exe.delete()
            root.delete()
        }
    }

    @Test
    fun `a heading link into another file keeps working`() {
        // `[see](./OTHER.md#setup)` is ordinary markdown, and File(URI) rejects any URI
        // carrying a fragment — so the fragment has to be stripped or the link is refused.
        val root = Files.createTempDirectory("boss-preview-frag-test").toFile().canonicalFile
        val neighbour = File(root, "OTHER.md").apply { writeText("# setup\n") }
        try {
            assertEquals(
                PreviewLinkRoute.LocalFile(neighbour.path),
                previewLinkRoute("${neighbour.toURI()}#setup", root.path)
            )
            assertEquals(
                PreviewLinkRoute.LocalFile(neighbour.path),
                previewLinkRoute("${neighbour.toURI()}?v=1", root.path)
            )
        } finally {
            neighbour.delete()
            root.delete()
        }
    }

    @Test
    fun `a path with a space still resolves`() {
        val root = Files.createTempDirectory("boss preview space test").toFile().canonicalFile
        val neighbour = File(root, "my notes.md").apply { writeText("# notes\n") }
        try {
            assertEquals(
                PreviewLinkRoute.LocalFile(neighbour.path),
                previewLinkRoute(neighbour.toURI().toString(), root.path),
                "a percent-encoded path must be decoded back to the real file"
            )
        } finally {
            neighbour.delete()
            root.delete()
        }
    }

    @Test
    fun `a link may not escape the project`() {
        // `[Setup instructions](../../../../home/you/.ssh/id_rsa)` resolves against
        // <base href> into a perfectly real regular file. Not executing it is not
        // enough — opening it in a panel still surfaces its contents from one click on
        // a plausibly-labelled link in a repo the operator merely opened.
        val parent = Files.createTempDirectory("boss-preview-escape").toFile().canonicalFile
        val root = File(parent, "project").apply { mkdirs() }
        val outside = File(parent, "id_rsa").apply { writeText("PRIVATE KEY") }
        val inside = File(root, "README.md").apply { writeText("# hi\n") }
        try {
            assertEquals(
                PreviewLinkRoute.Refuse,
                previewLinkRoute(outside.toURI().toString(), root.path),
                "a file outside the project must not open"
            )
            assertEquals(
                PreviewLinkRoute.LocalFile(inside.path),
                previewLinkRoute(inside.toURI().toString(), root.path),
                "a file inside the project must still open"
            )
        } finally {
            listOf(inside, outside, root, parent).forEach { it.delete() }
        }
    }

    @Test
    fun `a sibling directory sharing the root's name prefix is not inside it`() {
        // A path-prefix check would accept `/tmp/x/project-secrets/k` for a root of
        // `/tmp/x/project`, because the text matches before the separator does.
        val parent = Files.createTempDirectory("boss-preview-prefix").toFile().canonicalFile
        val root = File(parent, "project").apply { mkdirs() }
        val sibling = File(parent, "project-secrets").apply { mkdirs() }
        val secret = File(sibling, "key.txt").apply { writeText("k") }
        try {
            assertEquals(
                PreviewLinkRoute.Refuse,
                previewLinkRoute(secret.toURI().toString(), root.path)
            )
        } finally {
            listOf(secret, sibling, root, parent).forEach { it.delete() }
        }
    }

    @Test
    fun `a symlink pointing out of the project is refused`() {
        // NIO real paths follow symlinks on Windows too: a link is inside the
        // project by path and outside it by target.
        val parent = Files.createTempDirectory("boss-preview-symlink").toFile().canonicalFile
        val root = File(parent, "project").apply { mkdirs() }
        val outside = File(parent, "id_rsa").apply { writeText("PRIVATE KEY") }
        val link = File(root, "innocent.md")
        try {
            createPreviewSymlink(link, outside)
            assertEquals(
                PreviewLinkRoute.Refuse,
                previewLinkRoute(link.toURI().toString(), root.path)
            )
        } finally {
            listOf(link, outside, root, parent).forEach { it.delete() }
        }
    }

    @Test
    fun `a symlink to an existing in project file still opens its real target`() {
        val root = Files.createTempDirectory("boss-preview-inside-link").toFile()
        val target = File(root, "README.md").apply { writeText("# project notes") }
        val link = File(root, "notes.md")
        try {
            createPreviewSymlink(link, target)
            assertEquals(PreviewLinkRoute.LocalFile(target.toPath().toRealPath().toString()),
                previewLinkRoute(link.toURI().toString(), root.path))
        } finally {
            listOf(link, target, root).forEach { it.delete() }
        }
    }

    @Test
    fun `a symlinked project root allows only files in its real directory`() {
        val parent = Files.createTempDirectory("boss-preview-root-link").toFile()
        val root = File(parent, "project").apply { mkdirs() }
        val rootLink = File(parent, "checkout")
        val target = File(root, "README.md").apply { writeText("# project notes") }
        val outside = File(parent, "id_rsa").apply { writeText("PRIVATE KEY") }
        try {
            createPreviewSymlink(rootLink, root)
            assertEquals(PreviewLinkRoute.LocalFile(target.toPath().toRealPath().toString()),
                previewLinkRoute(File(rootLink, "README.md").toURI().toString(), rootLink.path))
            assertEquals(PreviewLinkRoute.Refuse, previewLinkRoute(outside.toURI().toString(), rootLink.path))
        } finally {
            listOf(rootLink, target, outside, root, parent).forEach { it.delete() }
        }
    }

    @Test
    fun `a symlinked parent directory cannot expose an outside file`() {
        val parent = Files.createTempDirectory("boss-preview-parent-link").toFile()
        val root = File(parent, "project").apply { mkdirs() }
        val outside = File(parent, "private").apply { mkdirs() }
        val secret = File(outside, "id_rsa").apply { writeText("PRIVATE KEY") }
        val link = File(root, "docs")
        try {
            createPreviewSymlink(link, outside)
            assertEquals(PreviewLinkRoute.Refuse,
                previewLinkRoute(File(link, "id_rsa").toURI().toString(), root.path))
        } finally {
            listOf(link, secret, outside, root, parent).forEach { it.delete() }
        }
    }

    /** Skip only a demonstrated inability to create the link, never a routing failure. */
    private fun createPreviewSymlink(link: File, target: File) {
        try {
            Files.createSymbolicLink(link.toPath(), target.toPath())
        } catch (failure: UnsupportedOperationException) {
            org.junit.Assume.assumeNoException("Symlinks are unsupported by this filesystem", failure)
        } catch (failure: java.nio.file.FileSystemException) {
            if (System.getProperty("os.name").startsWith("Windows") &&
                failure.reason.orEmpty().contains("privilege", ignoreCase = true)) {
                org.junit.Assume.assumeNoException("This Windows account lacks symlink privileges", failure)
            } else {
                throw failure
            }
        }
    }

    @Test
    fun `without a known project root no local link opens`() {
        // Failing closed: "we don't know where the project is" must not widen what a
        // document can reach.
        val root = Files.createTempDirectory("boss-preview-noroot").toFile().canonicalFile
        val file = File(root, "README.md").apply { writeText("# hi\n") }
        try {
            assertEquals(PreviewLinkRoute.Refuse, previewLinkRoute(file.toURI().toString(), null))
            assertEquals(PreviewLinkRoute.Refuse, previewLinkRoute(file.toURI().toString(), ""))
        } finally {
            file.delete()
            root.delete()
        }
    }

    @Test
    fun `everything else is refused`() {
        val root = Files.createTempDirectory("boss-preview-refuse-test").toFile().canonicalFile
        try {
            listOf(
                "file://${root.path}", // a directory: nothing to open
                "file://${root.path}/nope.md", // a typo must not reach the host
                "jar:file:///tmp/x.jar!/y",
                "smb://host/share/x",
                "javascript:alert(1)",
                "data:text/html,<script>alert(1)</script>",
                "vbscript:msgbox(1)",
                "custom-handler:payload",
                "",
                "not a uri at all"
            ).forEach {
                assertEquals(
                    PreviewLinkRoute.Refuse, previewLinkRoute(it, root.path),
                    "$it must be refused"
                )
            }
        } finally {
            root.delete()
        }
    }

    // ---------- where the sanitizer is extracted to ----------

    @Test
    fun `the extraction directory is unguessable and owner-only`() {
        // The sanitizer every guarantee above rests on is extracted to this
        // directory. A fixed name under the system temp dir is one another local
        // account can create first — mkdirs() succeeds silently on a directory it
        // does not own — and populate with a passthrough purify.min.js that still
        // reports isSupported.
        val first = createPreviewTmpDir()
        val second = createPreviewTmpDir()
        try {
            assertTrue(first.isDirectory, "temp dir was not created")
            assertNotEquals(
                first.name, second.name,
                "the extraction directory name must not be predictable"
            )
            val posix = FileSystems.getDefault().supportedFileAttributeViews().contains("posix")
            if (posix) {
                assertEquals(
                    "rwx------",
                    PosixFilePermissions.toString(Files.getPosixFilePermissions(first.toPath())),
                    "nothing but this user may write into the directory holding the sanitizer"
                )
            }
        } finally {
            first.delete()
            second.delete()
        }
    }
}
