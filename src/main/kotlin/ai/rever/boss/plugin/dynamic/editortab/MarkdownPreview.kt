package ai.rever.boss.plugin.dynamic.editortab

import ai.rever.boss.plugin.browser.BrowserConfig
import ai.rever.boss.plugin.browser.BrowserHandle
import ai.rever.boss.plugin.browser.BrowserService
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.CircularProgressIndicator
import androidx.compose.material.Icon
import androidx.compose.material.MaterialTheme
import androidx.compose.material.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import compose.icons.FeatherIcons
import compose.icons.feathericons.EyeOff
import kotlinx.coroutines.delay
import java.io.File
import java.nio.file.FileSystems
import java.nio.file.Files
import java.nio.file.attribute.FileAttribute
import java.nio.file.attribute.PosixFilePermissions
import java.security.SecureRandom
import java.util.Base64

/** View mode for Markdown files: plain editor, side-by-side, or rendered preview only. */
enum class MarkdownViewMode(val displayName: String) {
    EDIT("Edit"),
    SPLIT("Split"),
    PREVIEW("Preview")
}

/**
 * Rendered markdown preview pane backed by the host's BrowserService (JxBrowser),
 * following the same create/dispose pattern as the fluck-chatgpt plugin.
 *
 * Rendering happens inside the browser page with a vendored marked.js
 * (plugin resource `markdown-preview/marked.umd.js`), so the plugin JAR stays thin
 * and no markdown library is needed on the host classloader.
 *
 * Content is handed to the page base64-encoded (initial content embedded in the
 * HTML shell, live updates via [BrowserHandle.executeJavaScript]) which sidesteps
 * all JS/HTML escaping concerns. Updates replace `#content` in place, so the
 * scroll position survives re-renders.
 *
 * The previewed document is not the operator's own writing — it is whatever the
 * repository they opened happens to contain — so the page treats it as data
 * throughout. marked emits HTML verbatim, including any HTML the markdown carried,
 * so its output is passed through DOMPurify (vendored as
 * `markdown-preview/purify.min.js`) and the resulting nodes are attached to the
 * document; nothing in the page assigns a markup string to the DOM. The shell also
 * carries a Content-Security-Policy that admits only what the preview itself needs:
 * the three vendored libraries plus the shell's own inline script, matched by a
 * per-page nonce. See [buildPreviewHtml].
 *
 * @param markdown Current markdown source; changes are debounced then pushed to the page.
 * @param baseDir Directory of the .md file — becomes the page's `<base href>` so
 *   relative image/link paths resolve.
 * @param darkTheme Chooses the dark or light preview stylesheet.
 * @param allowedRoot Directory a clicked local link may open within — the project
 *   root. Null refuses every local link; see [previewLinkRoute].
 * @param onOpenLocalFile Opens a link to a file inside [allowedRoot] in BOSS.
 */
@Composable
fun MarkdownPreviewPane(
    browserService: BrowserService?,
    markdown: String,
    baseDir: String,
    darkTheme: Boolean,
    modifier: Modifier = Modifier,
    allowedRoot: String? = null,
    onOpenLocalFile: ((String) -> Unit)? = null
) {
    if (browserService == null || !browserService.isAvailable()) {
        PreviewUnavailableMessage(modifier)
        return
    }

    var browserHandle by remember { mutableStateOf<BrowserHandle?>(null) }
    var pageReady by remember { mutableStateOf(false) }
    val currentAllowedRoot = rememberUpdatedState(allowedRoot)
    val currentOnOpenLocalFile = rememberUpdatedState(onOpenLocalFile)

    // (Re)create the browser when the file's directory or theme changes.
    LaunchedEffect(baseDir, darkTheme) {
        pageReady = false
        browserHandle?.dispose()

        val htmlFile = writePreviewShell(markdown, baseDir, darkTheme)
        val handle = browserService.createBrowser(
            BrowserConfig(
                url = htmlFile.toURI().toString(),
                enableDevTools = false,
                enableDownloads = false,
                enableFullscreen = false
            )
        )
        if (handle != null) {
            // Links the shell marked _blank land here. http/https/mailto go to the
            // system browser; a link to a neighbouring file opens inside BOSS.
            //
            // Read through the latest-value holders rather than captured directly: this
            // effect only re-runs on baseDir/darkTheme, so a callback capturing the
            // parameters would keep whichever instances existed when the browser was
            // built. Harmless while they are stable, a stale-closure bug the moment one
            // of them closes over recomposing state.
            handle.setOpenInNewTabCallback { url ->
                routePreviewLink(url, currentAllowedRoot.value, currentOnOpenLocalFile.value)
            }
            browserHandle = handle
            pageReady = true
        }
    }

    // Push edits into the live page, debounced so typing doesn't spam the bridge.
    LaunchedEffect(markdown, pageReady) {
        if (!pageReady) return@LaunchedEffect
        delay(200)
        val handle = browserHandle ?: return@LaunchedEffect
        if (handle.isValid) {
            val b64 = Base64.getEncoder().encodeToString(markdown.toByteArray(Charsets.UTF_8))
            handle.executeJavaScript("window.__setMarkdownB64 && window.__setMarkdownB64(\"$b64\")")
        }
    }

    DisposableEffect(Unit) {
        onDispose {
            browserHandle?.dispose()
            browserHandle = null
        }
    }

    Box(modifier = modifier) {
        val handle = browserHandle
        if (handle != null && handle.isValid) {
            handle.Content()
        } else {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(modifier = Modifier.size(24.dp))
            }
        }
    }
}

@Composable
private fun PreviewUnavailableMessage(modifier: Modifier = Modifier) {
    Column(
        modifier = modifier.padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Icon(
            imageVector = FeatherIcons.EyeOff,
            contentDescription = null,
            modifier = Modifier.size(32.dp),
            tint = MaterialTheme.colors.onBackground.copy(alpha = 0.4f)
        )
        Box(modifier = Modifier.height(8.dp))
        Text(
            text = "Markdown preview unavailable",
            fontSize = 13.sp,
            color = MaterialTheme.colors.onBackground.copy(alpha = 0.6f)
        )
        Text(
            text = "Browser service (JxBrowser) is not available",
            fontSize = 11.sp,
            color = MaterialTheme.colors.onBackground.copy(alpha = 0.4f)
        )
    }
}

/**
 * The schemes a previewed document may hand to the desktop.
 *
 * Every link in the preview is the opened repository's writing, and the shell
 * routes all of them here by setting `target = '_blank'`. DOMPurify drops an
 * *absolute* `file:` href, but a relative one is kept by design — and by the time
 * the page reports a click, that relative path has resolved against `<base href>`
 * into an absolute `file:` URL. So without this list `[docs](./setup.exe)` would be
 * a one-click local-file open — on Windows through ShellExecute, which for some
 * target types launches rather than views. The click is the operator's, which caps
 * the severity, but "make the operator click a plausible-looking link" is squarely
 * inside the threat model a hostile README presents.
 *
 * Only the schemes a document has a legitimate reason to point outward with are
 * passed through. Anything else — `file:`, `jar:`, `smb:`, a custom handler
 * registered by some other application — is dropped *here*.
 *
 * A `file:` URL is not simply dropped, though: [previewLinkRoute] gives it a second
 * chance as an in-BOSS open, which is what keeps a link to a neighbouring `.md`
 * working. That path is what stops `[docs](../../../.ssh/id_rsa)` — not this list —
 * by confining the open to the project root. Both halves are needed: this one so
 * nothing local is ever *executed*, that one so nothing outside the project is
 * *read*.
 */
private val BROWSABLE_LINK_SCHEMES = setOf("http", "https", "mailto")

/** Whether [url] is something [openInSystemBrowser] will hand to the desktop. */
internal fun isBrowsableLink(url: String): Boolean {
    val scheme = try {
        java.net.URI(url).scheme
    } catch (_: Exception) {
        null // not a URI at all
    }
    return scheme?.lowercase() in BROWSABLE_LINK_SCHEMES
}

/**
 * Decides what a clicked preview link does.
 *
 * Three outcomes, and the split is the point:
 *
 * - `http`/`https`/`mailto` → the system browser, as before.
 * - a `file:` URL naming an existing regular file **inside the project** → opened
 *   inside BOSS, never handed to the desktop. This is what makes a relative link to
 *   a neighbouring `.md` work again; the sanitisation pass had to refuse those
 *   wholesale because `<base href>` resolves them to `file:` URLs and
 *   `Desktop.browse` would let the OS decide what to *execute* for
 *   `[docs](./setup.exe)`. Handing the path to the editor removes that: BOSS reads
 *   the bytes, the OS is never consulted.
 * - anything else — a `file:` URL pointing at a directory or nothing, one that
 *   escapes the project, `jar:`, `smb:`, a scheme some other application
 *   registered → dropped.
 *
 * Not executing a local file is only half of it. `[Setup instructions](../../../../.ssh/id_rsa)`
 * resolves against `<base href>` to a perfectly real regular file, so an unbounded
 * local-file route would surface the contents of anything readable from one click on
 * a plausibly-labelled link in a repository the operator merely opened — and an open
 * tab is reachable state, not just pixels. Hence [allowedRoot]: the resolved file has
 * to sit inside the project. Every legitimate case (a sibling `README.md`,
 * `../docs/x.md`) still works; a link into another checkout does not, which is what
 * `main` did anyway.
 */
internal sealed interface PreviewLinkRoute {
    /** Hand to the desktop — only ever an allowlisted outward scheme. */
    data class SystemBrowser(val url: String) : PreviewLinkRoute

    /** Open inside BOSS. The OS is never asked what this file means. */
    data class LocalFile(val path: String) : PreviewLinkRoute

    /** Nothing safe to do with it. */
    data object Refuse : PreviewLinkRoute
}

/**
 * Where a clicked preview link should go. Pure — [routePreviewLink] acts on it.
 *
 * @param allowedRoot directory the local-file route is confined to. A null root
 *   refuses every local file rather than allowing all of them: "we don't know where
 *   the project is" must not widen what a document can reach.
 */
internal fun previewLinkRoute(url: String, allowedRoot: String?): PreviewLinkRoute = when {
    isBrowsableLink(url) -> PreviewLinkRoute.SystemBrowser(url)
    else -> localFileFor(url, allowedRoot)?.let { PreviewLinkRoute.LocalFile(it) }
        ?: PreviewLinkRoute.Refuse
}

private fun routePreviewLink(url: String, allowedRoot: String?, onOpenLocalFile: ((String) -> Unit)?) {
    when (val route = previewLinkRoute(url, allowedRoot)) {
        is PreviewLinkRoute.SystemBrowser -> openInSystemBrowser(route.url)
        is PreviewLinkRoute.LocalFile ->
            if (onOpenLocalFile != null) {
                onOpenLocalFile(route.path)
            } else {
                System.err.println("[MarkdownPreview] No host route for a local preview link; ignoring")
            }
        PreviewLinkRoute.Refuse ->
            System.err.println(
                "[MarkdownPreview] Refusing preview link: not http/https/mailto and not a local file"
            )
    }
}

/**
 * The path a `file:` URL names, if it is an existing regular file within [allowedRoot].
 *
 * A directory is rejected (nothing sensible to open) and so is a path that does not
 * exist, which keeps a typo'd link from reaching the host at all.
 *
 * The fragment and query are dropped before the path is taken. `[see](./OTHER.md#setup)`
 * is an ordinary thing to write and `<base href>` resolves it to `file:///…/OTHER.md#setup`;
 * `java.io.File(URI)` rejects any URI carrying either component, so without this a
 * cross-file heading link would be refused rather than opened.
 *
 * Both sides of the containment check use NIO real paths. URL resolution already collapses
 * `..` before the click reaches Kotlin, so that part is insurance — but resolving
 * symlinks is not: an in-repo symlink pointing at `~/.ssh/id_rsa` is inside the project
 * by path and outside it by target. `File.canonicalFile` does not resolve Windows
 * symlinks reliably; `Path.toRealPath` resolves the existing target and root.
 */
private fun localFileFor(url: String, allowedRoot: String?): String? {
    if (allowedRoot.isNullOrBlank()) return null
    val uri = try {
        java.net.URI(url)
    } catch (_: Exception) {
        return null
    }
    if (uri.scheme?.lowercase() != "file") return null
    val bare = if (uri.fragment == null && uri.query == null) {
        uri
    } else {
        try {
            java.net.URI(uri.scheme, uri.authority, uri.path, null, null)
        } catch (_: Exception) {
            return null
        }
    }
    return try {
        val file = java.io.File(bare).toPath().toRealPath()
        val root = java.io.File(allowedRoot).toPath().toRealPath()
        file.takeIf { Files.isRegularFile(it) && Files.isDirectory(root) && it.startsWith(root) }?.toString()
    } catch (_: Exception) {
        null
    }
}


private fun openInSystemBrowser(url: String) {
    if (!isBrowsableLink(url)) {
        System.err.println("[MarkdownPreview] Refusing to open a preview link that is not http, https or mailto")
        return
    }
    try {
        if (java.awt.Desktop.isDesktopSupported()) {
            java.awt.Desktop.getDesktop().browse(java.net.URI(url))
        }
    } catch (e: Exception) {
        System.err.println("[MarkdownPreview] Failed to open link: ${e.message}")
    }
}

// ========== HTML shell generation ==========

/**
 * Creates the directory holding the extracted libraries and the generated shells.
 *
 * Deliberately not a fixed name under the system temp dir. That path is
 * guessable, and `mkdirs()` succeeds silently on a directory a different local
 * account created first — so on a shared host that account could pre-populate it
 * with a `purify.min.js` whose `sanitize` is a passthrough. It would still report
 * `isSupported`, the fail-closed guard would pass, and every property this page
 * establishes would be gone with no visible symptom. That was survivable while
 * the directory only held rendering libraries; it is not now that the sanitizer
 * the preview's guarantees rest on lives there.
 *
 * [Files.createTempDirectory] picks an unguessable name, and where the filesystem
 * carries POSIX permissions the directory is created `rwx------` so nothing but
 * this user can write into it. On Windows the attribute is unsupported and
 * omitted; the per-user temp directory is not a shared location there.
 *
 * The directory is registered for deletion *before* anything is written into it,
 * so the JVM's reverse-order exit hook removes the files first and then the
 * directory itself.
 */
internal fun createPreviewTmpDir(): File {
    val posix = FileSystems.getDefault().supportedFileAttributeViews().contains("posix")
    val attrs: Array<FileAttribute<*>> = if (posix) {
        arrayOf(PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwx------")))
    } else {
        emptyArray()
    }
    return Files.createTempDirectory("boss-md-preview", *attrs).toFile()
        .also { it.deleteOnExit() }
}

private val previewTmpDir: File by lazy { createPreviewTmpDir() }

/**
 * Extracts a vendored JS library from plugin resources to a temp file (one copy
 * for all tabs) and returns its file:// URI. The page references it by absolute
 * URI because `<base href>` points at the markdown file's directory.
 *
 * Always written, never reconciled against what is already on disk: the
 * containing directory is created fresh per JVM, so there is nothing there to
 * reuse, and a "same size means same file" shortcut is exactly the kind of check
 * a substituted sanitizer passes.
 */
private fun extractJsResource(name: String): String {
    val target = File(previewTmpDir, name)
    val resource = object {}.javaClass.classLoader.getResourceAsStream("markdown-preview/$name")
        ?: error("markdown-preview/$name missing from plugin resources")
    target.deleteOnExit()
    resource.use { input -> target.writeBytes(input.readBytes()) }
    return target.toURI().toString()
}

private val markedJsUri: String by lazy { extractJsResource("marked.umd.js") }
private val mermaidJsUri: String by lazy { extractJsResource("mermaid.min.js") }

/**
 * DOMPurify, the sanitizer marked's own documentation points callers at (marked
 * emits the HTML a document carries as-is and has no sanitize option of its own).
 *
 * Vendored verbatim from the published npm artifact — `dompurify@3.4.11`,
 * `dist/purify.min.js`, tarball
 * `https://registry.npmjs.org/dompurify/-/dompurify-3.4.11.tgz`
 * (sha512-zhlUV12GsaRzMsf9q5M254YhA4+VuF0fG+QFqu6aYpoGlKtz+w8//jBcGVYBgQkR5GHjUomejY84AV+/uPbWdw==),
 * so the file still matches `npm pack dompurify@3.4.11` byte for byte and its own
 * banner records the version and upstream, the way `marked.umd.js` does.
 */
private val purifyJsUri: String by lazy { extractJsResource("purify.min.js") }

/** Seeded once; [SecureRandom] is thread-safe and reseeds itself. */
private val nonceRandom = SecureRandom()

/**
 * Fresh nonce per preview page. The shell's Content-Security-Policy names this
 * value as the only way a script may run, and every `<script>` the shell writes
 * carries it — so a script tag that arrives from anywhere else has no way to
 * execute, whatever put it in the document.
 */
internal fun newScriptNonce(): String {
    val bytes = ByteArray(16)
    nonceRandom.nextBytes(bytes)
    // Base64url: the CSP nonce grammar accepts '-' and '_', and dropping the
    // padding keeps the value free of '=' too.
    return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
}

/**
 * Escapes a value for use inside a double-quoted HTML attribute. Every attribute
 * the shell interpolates into is double-quoted, so an apostrophe is left alone —
 * which keeps the policy's `'none'` and `'nonce-…'` readable in the emitted page.
 *
 * The URIs the shell interpolates come from [File.toURI], which already
 * percent-encodes the characters that would end an attribute. Escaping here as
 * well keeps that invariant local to the template instead of resting on the
 * encoding rules of a type two layers away.
 */
private fun htmlAttr(value: String): String = value
    .replace("&", "&amp;")
    .replace("\"", "&quot;")
    .replace("<", "&lt;")
    .replace(">", "&gt;")

/** Writes the per-preview HTML shell to a temp file and returns it. */
private fun writePreviewShell(markdown: String, baseDir: String, dark: Boolean): File {
    val initialB64 = Base64.getEncoder().encodeToString(markdown.toByteArray(Charsets.UTF_8))
    val baseUri = File(baseDir).toURI().toString() // directory URI, ends with '/'
    val html = buildPreviewHtml(
        initialB64 = initialB64,
        baseUri = baseUri,
        markedUri = markedJsUri,
        mermaidUri = mermaidJsUri,
        purifyUri = purifyJsUri,
        nonce = newScriptNonce(),
        dark = dark
    )
    val file = File.createTempFile("preview-", ".html", previewTmpDir)
    file.deleteOnExit()
    file.writeText(html)
    return file
}

/**
 * Builds the preview page.
 *
 * Two properties of the result are load-bearing and covered by
 * `MarkdownPreviewShellTest`:
 *
 * 1. **Nothing in the page assigns a markup string to the DOM.** marked's output
 *    goes through `DOMPurify.sanitize(..., RETURN_DOM_FRAGMENT)` and the returned
 *    nodes are appended; the render-error path writes `textContent`. So the page
 *    contains no `innerHTML` write at all, and if DOMPurify is missing the render
 *    reports an error rather than falling back to the raw HTML.
 * 2. **The Content-Security-Policy admits only what the preview needs.**
 *    `default-src 'none'` denies by default, then:
 *    - `script-src 'nonce-…'` — the three vendored libraries and the shell's own
 *      inline script all carry the nonce. No `'unsafe-inline'`, which is also what
 *      makes an `on…=` attribute inert, and no host source, so nothing can be
 *      pulled in over the network.
 *    - `style-src 'unsafe-inline'` — required: mermaid styles a rendered diagram by
 *      inserting a `<style>` element into the SVG it builds, and the shell's own
 *      stylesheet is inline. Styles are not script, and with the fetch directives
 *      below a stylesheet has nowhere to send anything.
 *
 *      **Accepted residual**: DOMPurify's html profile keeps `style` *attributes*,
 *      and this directive permits them, so a hostile document can style itself —
 *      a `position: fixed` full-viewport overlay could spoof preview content
 *      inside the preview pane. There is no exfiltration channel behind it
 *      (`img-src` and `media-src` are `file: data:`, and `connect-src`/`font-src`
 *      are `'none'`), so the reach ends at the pane. It is left as-is rather than
 *      fixed with `FORBID_ATTR: ['style']`, because marked expresses table column
 *      alignment as `style="text-align:right"` — dropping the attribute would
 *      silently regress every aligned table until the stylesheet grew matching
 *      `[align]` rules.
 *    - `img-src`/`media-src file: data:` — a document's relative image paths resolve
 *      against `<base href>` to `file:` URLs, and marked passes `data:` images
 *      through. Remote schemes are deliberately absent: a preview must not become a
 *      callback for the repository being read, so a remote `<img>` (a README badge,
 *      say) does not load.
 *    - `base-uri file:` — keeps the shell's own `<base href>`, which relative image
 *      and link paths depend on, while refusing to let the document re-point it
 *      somewhere else.
 *    - `object-src`/`frame-src`/`connect-src`/`font-src 'none'` and
 *      `form-action 'none'` — none of them are things this page does.
 *
 *    `require-trusted-types-for` is deliberately not set: mermaid builds its SVG
 *    with plain-string DOM writes, and enforcing Trusted Types would stop diagrams
 *    from rendering at all.
 */
internal fun buildPreviewHtml(
    initialB64: String,
    baseUri: String,
    markedUri: String,
    mermaidUri: String,
    purifyUri: String,
    nonce: String,
    dark: Boolean
): String {
    val colors = if (dark) {
        // GitHub-dark-inspired palette
        PreviewColors(
            bg = "#0d1117", fg = "#e6edf3", border = "#30363d",
            codeBg = "#161b22", link = "#4493f8", muted = "#8d96a0"
        )
    } else {
        PreviewColors(
            bg = "#ffffff", fg = "#1f2328", border = "#d1d9e0",
            codeBg = "#f6f8fa", link = "#0969da", muted = "#59636e"
        )
    }
    val csp = listOf(
        "default-src 'none'",
        "script-src 'nonce-$nonce'",
        "style-src 'unsafe-inline'",
        "img-src file: data:",
        "media-src file: data:",
        "font-src 'none'",
        "connect-src 'none'",
        "frame-src 'none'",
        "object-src 'none'",
        "base-uri file:",
        "form-action 'none'"
    ).joinToString("; ")
    return """
<!DOCTYPE html>
<html>
<head>
<meta charset="utf-8">
<meta http-equiv="Content-Security-Policy" content="${htmlAttr(csp)}">
<base href="${htmlAttr(baseUri)}">
<style>
  * { box-sizing: border-box; }
  html, body { margin: 0; padding: 0; background: ${colors.bg}; }
  .markdown-body {
    font-family: -apple-system, BlinkMacSystemFont, "Segoe UI", Helvetica, Arial, sans-serif;
    font-size: 15px; line-height: 1.6; color: ${colors.fg};
    max-width: 860px; margin: 0 auto; padding: 24px 32px 64px;
    word-wrap: break-word;
  }
  .markdown-body h1, .markdown-body h2 {
    padding-bottom: .3em; border-bottom: 1px solid ${colors.border};
  }
  .markdown-body h1, .markdown-body h2, .markdown-body h3,
  .markdown-body h4, .markdown-body h5, .markdown-body h6 {
    margin-top: 24px; margin-bottom: 16px; font-weight: 600; line-height: 1.25;
  }
  .markdown-body a { color: ${colors.link}; text-decoration: none; }
  .markdown-body a:hover { text-decoration: underline; }
  .markdown-body code, .markdown-body pre {
    font-family: ui-monospace, SFMono-Regular, "SF Mono", Menlo, Consolas, monospace;
    font-size: 85%;
  }
  .markdown-body code { background: ${colors.codeBg}; padding: .2em .4em; border-radius: 6px; }
  .markdown-body pre {
    background: ${colors.codeBg}; padding: 16px; border-radius: 6px;
    overflow-x: auto; line-height: 1.45;
  }
  .markdown-body pre code { background: transparent; padding: 0; }
  .markdown-body blockquote {
    margin: 0 0 16px; padding: 0 1em; color: ${colors.muted};
    border-left: .25em solid ${colors.border};
  }
  .markdown-body table { border-collapse: collapse; margin-bottom: 16px; display: block; overflow-x: auto; }
  .markdown-body th, .markdown-body td { border: 1px solid ${colors.border}; padding: 6px 13px; }
  .markdown-body th { font-weight: 600; background: ${colors.codeBg}; }
  .markdown-body img { max-width: 100%; }
  .markdown-body hr { height: 1px; border: 0; background: ${colors.border}; margin: 24px 0; }
  .markdown-body ul, .markdown-body ol { padding-left: 2em; }
  .markdown-body li + li { margin-top: .25em; }
  .markdown-body input[type="checkbox"] { margin-right: .5em; }
  .markdown-body pre.mermaid {
    background: transparent; padding: 8px 0; text-align: center;
  }
</style>
<script nonce="${htmlAttr(nonce)}" src="${htmlAttr(purifyUri)}"></script>
<script nonce="${htmlAttr(nonce)}" src="${htmlAttr(markedUri)}"></script>
<script nonce="${htmlAttr(nonce)}" src="${htmlAttr(mermaidUri)}"></script>
</head>
<body>
<article id="content" class="markdown-body"></article>
<script nonce="${htmlAttr(nonce)}">
  // The document being previewed is untrusted input: it is whatever the opened
  // repository contains. marked reproduces any HTML the markdown carried, so its
  // output is data until DOMPurify has been over it.
  //
  // The profile is HTML only — the element set GitHub renders markdown into, which
  // also leaves out inline SVG and MathML. Tables, task-list checkboxes, footnote
  // anchors, <details>, alignment and width/height attributes and relative image
  // paths all survive it. FORBID_TAGS then names two the profile would otherwise
  // decide: <style>, so the page's own stylesheet stays the only one, and <form>,
  // because a preview has nothing to submit. RETURN_DOM_FRAGMENT hands back nodes
  // instead of a markup string, so the sanitized result is never re-parsed and no
  // markup string is ever assigned to this document.
  var PURIFY_CONFIG = {
    USE_PROFILES: { html: true },
    FORBID_TAGS: ['style', 'form'],
    RETURN_DOM_FRAGMENT: true
  };

  if (window.mermaid) {
    mermaid.initialize({
      startOnLoad: false,
      // Mermaid renders a diagram by building an SVG string and writing it into
      // the page itself — a path the sanitize call above never sees. Two settings
      // keep what it writes to drawing instructions:
      //
      // securityLevel 'strict' entity-encodes label text, runs it through
      // mermaid's own bundled DOMPurify and ignores click directives. It is also
      // mermaid's default, but it is stated rather than assumed because a diagram
      // may carry an `%%{init: …}%%` directive; mermaid keeps securityLevel in its
      // 'secure' list, so a level set here cannot be lowered from inside a diagram.
      securityLevel: 'strict',
      // htmlLabels false is the one that settles it. With HTML labels on — the
      // flowchart default — a quoted label is placed in a foreignObject as live
      // HTML, and 'strict' does not prevent that: a label of
      // `A["<img src=x onerror=…>"]` really does become an img element in the
      // page. With them off, every label is drawn as SVG <text>, so a label can
      // carry no markup at all. Diagrams render the same either way; labels lose
      // only HTML formatting inside them.
      htmlLabels: false,
      flowchart: { htmlLabels: false },
      theme: ${if (dark) "'dark'" else "'default'"}
    });
  }

  // Second line on mermaid's output path — the policy above is the control that
  // actually stops any of this from running, and it does so whether or not this
  // function is correct. What this adds is that a rendered diagram does not sit in
  // the document carrying a script element, an inline handler or a script-bearing
  // URL in the first place. In normal operation it removes nothing (mermaid emits
  // none of them), and it leaves the drawing alone — geometry, foreignObject
  // content, diagram-internal `#id` references (markers and <use> need them) and
  // data: images.
  //
  // Called with each `pre.mermaid` as its root rather than the whole article: that
  // is the output path this is here for, and it keeps a document with one diagram
  // from re-walking every element and attribute in it on every debounced
  // re-render.
  function scrubRendered(root) {
    var all = root.querySelectorAll('*');
    for (var i = 0; i < all.length; i++) {
      var node = all[i];
      var name = (node.nodeName || '').toLowerCase();
      // The animation elements are here because SMIL can re-point an attribute
      // after this pass has been over it: <animate attributeName="href"
      // to="javascript:…"> sets a scheme no scrub of the static tree can see.
      // Mermaid never emits them, so dropping them costs a diagram nothing.
      if (name === 'script' || name === 'iframe' || name === 'object' || name === 'embed' ||
          name === 'animate' || name === 'animatemotion' || name === 'animatetransform' ||
          name === 'set') {
        node.remove();
        continue;
      }
      var attrs = node.attributes;
      for (var j = attrs.length - 1; j >= 0; j--) {
        var attrName = attrs[j].name;
        var lower = attrName.toLowerCase();
        // Deleting everything below U+0021 is what makes the scheme test mean
        // something: a browser strips TAB, LF and CR out of a URL before it parses
        // the scheme, so `java<TAB>script:` — already entity-decoded by the time it
        // is an attribute value — is a working javascript: URL that a `^javascript:`
        // test does not match.
        var value = (attrs[j].value || '').replace(/[\u0000-\u0020]/g, '');
        if (lower.indexOf('on') === 0) {
          node.removeAttribute(attrName);
        } else if (/^(?:javascript|vbscript):/i.test(value)) {
          node.removeAttribute(attrName);
        } else if ((lower === 'href' || lower === 'xlink:href') && /^data:/i.test(value)) {
          node.removeAttribute(attrName);
        }
      }
    }
  }

  function showRenderError(message) {
    var el = document.getElementById('content');
    if (!el) return;
    el.textContent = '';
    var pre = document.createElement('pre');
    pre.textContent = message;
    el.appendChild(pre);
  }

  window.__setMarkdownB64 = function(b64) {
    try {
      // No sanitizer, no render: an unsanitized document must never be the
      // fallback for a library that failed to load. The isSupported half is the
      // load-bearing one — DOMPurify that reports itself unsupported returns its
      // input untouched rather than throwing, so without this check a render would
      // proceed on unsanitized HTML and look like it had worked.
      if (!window.DOMPurify || !window.DOMPurify.isSupported) {
        throw new Error('HTML sanitizer unavailable');
      }
      var bytes = Uint8Array.from(atob(b64), function(c) { return c.charCodeAt(0); });
      var md = new TextDecoder('utf-8').decode(bytes);
      var el = document.getElementById('content');
      var fragment = DOMPurify.sanitize(marked.parse(md, { gfm: true }), PURIFY_CONFIG);
      el.textContent = '';
      el.appendChild(fragment);
      // Give headings the slug ids a '#heading' link expects.
      //
      // marked used to emit these itself; it dropped heading ids in v8 (they live in
      // a separate extension now), so with marked v18 nothing in the document has an
      // id and every table-of-contents link points at something that isn't there.
      // Assigned after sanitizing, on our own nodes, so it does not depend on the
      // purify allowlist keeping an id attribute.
      var usedIds = Object.create(null);
      el.querySelectorAll('h1,h2,h3,h4,h5,h6').forEach(function(h) {
        if (h.id) { usedIds[h.id] = true; return; }
        // GitHub's slug: lowercase, drop punctuation, spaces to hyphens. Collisions
        // get -1, -2 ... in document order, as GitHub numbers them.
        //
        // \p{L}\p{N} rather than \w: \w is ASCII-only, so a Japanese or Cyrillic
        // heading would slug to the empty string and get no id at all -- leaving a
        // non-English README's table of contents dead -- and an accented heading
        // would lose the accent, so its id wouldn't match the anchor an author
        // copied out of GitHub.
        var base = (h.textContent || '')
          .trim()
          .toLowerCase()
          .replace(/[^\p{L}\p{N}\- _]+/gu, '')
          .replace(/\s+/g, '-');
        if (!base) return;
        // Checked against the document, not just the headings seen so far: the shell
        // renders into <article id="content">, so a '## Content' heading would be the
        // second 'content' in the tree and lose the getElementById lookup to its own
        // ancestor -- '#content' would scroll to the top of the pane instead.
        var id = base, n = 0;
        while (usedIds[id] || document.getElementById(id)) { n++; id = base + '-' + n; }
        usedIds[id] = true;
        h.id = id;
      });
      // Route outbound links through the new-tab path so the host decides where
      // they go, instead of navigating the preview away.
      //
      // Fragment-only links are excluded: '#heading' anchors and footnote
      // back-links are meant to scroll *this* document. Marking them _blank sent
      // them to the host too, so in-page navigation never worked -- a README's
      // table of contents did nothing.
      el.querySelectorAll('a[href]').forEach(function(a) {
        var href = a.getAttribute('href') || '';
        if (href.charAt(0) === '#') return;
        a.target = '_blank';
      });
      // Turn ```mermaid fences into rendered diagrams. textContent un-escapes the
      // HTML entities marked produced, giving mermaid the raw diagram source.
      var fences = el.querySelectorAll('pre > code.language-mermaid');
      if (fences.length && window.mermaid) {
        fences.forEach(function(code) {
          var diagram = document.createElement('pre');
          diagram.className = 'mermaid';
          diagram.textContent = code.textContent;
          code.parentElement.replaceWith(diagram);
        });
        var diagrams = el.querySelectorAll('pre.mermaid');
        var scrub = function() { diagrams.forEach(function(d) { scrubRendered(d); }); };
        // On a bad diagram mermaid shows its error bomb in place; the rejection
        // handler both keeps that from surfacing as an unhandled promise error and
        // scrubs whatever did land.
        mermaid.run({ nodes: diagrams }).then(scrub, scrub);
      }
    } catch (e) {
      showRenderError('Markdown render error: ' + String(e));
    }
  };

  // Scroll fragment links ourselves instead of letting the browser navigate.
  //
  // The page carries a <base href> pointing at the markdown file's directory so
  // relative images resolve, and per spec a bare '#heading' resolves against the
  // BASE rather than the document -- so the default action for '#heading' is a
  // real navigation to file:///thatdir/#heading, which throws away the preview and
  // lands on a directory listing. Not marking these _blank is necessary but not
  // sufficient; the scroll has to be explicit.
  //
  // Delegated and installed once, so it survives every re-render.
  document.addEventListener('click', function(ev) {
    var a = ev.target && ev.target.closest ? ev.target.closest('a[href]') : null;
    if (!a || a.target === '_blank') return;
    var href = a.getAttribute('href') || '';
    if (href.charAt(0) !== '#') return;
    ev.preventDefault();
    var id = decodeURIComponent(href.slice(1));
    if (!id) {
      window.scrollTo(0, 0);
      return;
    }
    // getElementById first: a heading slug is an id. getElementsByName covers the
    // '<a name=...>' anchors older hand-written markdown still uses. Neither is a
    // selector, so a slug containing CSS metacharacters cannot break the lookup.
    var t = document.getElementById(id) || document.getElementsByName(id)[0];
    if (t) t.scrollIntoView({ block: 'start' });
  });

  // Test seam: `src/test/js/preview-dom.test.mjs` drives these directly so the
  // sanitize and scrub paths are asserted against a real DOM.
  window.__previewInternals = { purifyConfig: PURIFY_CONFIG, scrubRendered: scrubRendered };

  window.__setMarkdownB64("$initialB64");
</script>
</body>
</html>
""".trimIndent()
}

private data class PreviewColors(
    val bg: String,
    val fg: String,
    val border: String,
    val codeBg: String,
    val link: String,
    val muted: String
)
