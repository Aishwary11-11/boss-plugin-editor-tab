package ai.rever.boss.plugin.dynamic.editortab

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/**
 * Guards the path→language→lexer chain.
 *
 * This chain had a silent hole: BossEditor shipped working lexers for Dockerfile,
 * Makefile and others, but nothing mapped a file onto them, so those files opened
 * as plain text. A mapping gap produces no error — just quietly unhighlighted
 * files — so it needs a test rather than a glance.
 */
class LanguageDetectionTest {

    // ------------------------------------------------------ extension-less files

    @Test
    fun `bare Dockerfile is detected`() {
        assertEquals("dockerfile", LanguageDetection.detect("/srv/app/Dockerfile"))
    }

    @Test
    fun `Dockerfile with a stage suffix is detected`() {
        // `Dockerfile.dev` must win on the filename, not lose to the `.dev` extension.
        assertEquals("dockerfile", LanguageDetection.detect("/srv/app/Dockerfile.dev"))
        assertEquals("dockerfile", LanguageDetection.detect("/srv/app/Dockerfile.prod"))
    }

    @Test
    fun `dockerfile extension form is detected`() {
        assertEquals("dockerfile", LanguageDetection.detect("/srv/app/build.dockerfile"))
    }

    @Test
    fun `Containerfile is treated as a Dockerfile`() {
        assertEquals("dockerfile", LanguageDetection.detect("/srv/app/Containerfile"))
    }

    @Test
    fun `Makefile spellings are detected`() {
        assertEquals("makefile", LanguageDetection.detect("/srv/app/Makefile"))
        assertEquals("makefile", LanguageDetection.detect("/srv/app/GNUmakefile"))
        assertEquals("makefile", LanguageDetection.detect("/srv/app/build.mk"))
    }

    @Test
    fun `dotenv files map to properties`() {
        assertEquals("properties", LanguageDetection.detect("/srv/app/.env"))
        assertEquals("properties", LanguageDetection.detect("/srv/app/.env.local"))
    }

    // ------------------------------------------------------------ path handling

    @Test
    fun `a dot in a parent directory does not leak into the extension`() {
        // Regression: the extension used to be read from the whole path, so
        // `/srv/v1.2/Makefile` yielded the "extension" `2/Makefile`.
        assertEquals("makefile", LanguageDetection.detect("/srv/v1.2/Makefile"))
        assertEquals("yaml", LanguageDetection.detect("/srv/v1.2/values.yaml"))
        assertEquals("text", LanguageDetection.detect("/srv/v1.2/README"))
    }

    @Test
    fun `windows separators are handled`() {
        assertEquals("dockerfile", LanguageDetection.detect("C:\\src\\app\\Dockerfile"))
    }

    // -------------------------------------------------------------- still-correct

    @Test
    fun `existing mappings are unchanged`() {
        assertEquals("kotlin", LanguageDetection.detect("/a/B.kt"))
        assertEquals("yaml", LanguageDetection.detect("/a/Chart.yaml"))
        assertEquals("yaml", LanguageDetection.detect("/a/values-prod.yml"))
        assertEquals("json", LanguageDetection.detect("/a/package.json"))
        assertEquals("markdown", LanguageDetection.detect("/a/README.md"))
        assertEquals("text", LanguageDetection.detect("/a/notes.unknownext"))
    }

    // ------------------------------------------------- every language has a lexer

    @Test
    fun `newly wired languages resolve to a lexer`() {
        val wired = listOf(
            "dockerfile", "makefile", "properties", "diff", "batch", "clojure",
            "latex", "lisp", "tcl", "fortran", "d", "delphi", "visualbasic",
            "actionscript", "jsp",
        )
        wired.forEach { language ->
            assertNotNull(
                LanguageDetection.lexerFor(language),
                "no lexer wired for '$language' — it would fall back to plain text",
            )
        }
    }

    /**
     * The point of the fix: a detected language must actually reach a lexer.
     * Detection returning "dockerfile" while the factory returns null would look
     * fixed and highlight nothing.
     */
    @Test
    fun `detected language for these files resolves to a lexer`() {
        val paths = listOf(
            "/srv/app/Dockerfile",
            "/srv/app/Dockerfile.dev",
            "/srv/app/Containerfile",
            "/srv/app/Makefile",
            "/srv/app/GNUmakefile",
            "/srv/app/app.properties",
            "/srv/app/change.patch",
            "/srv/app/Chart.yaml",
        )
        paths.forEach { path ->
            val language = LanguageDetection.detect(path)
            assertNotNull(
                LanguageDetection.lexerFor(language),
                "$path detected as '$language' but no lexer handles it",
            )
        }
    }

    // --------------------------------------------- the invariant lexerFor states

    /**
     * Every language [LanguageDetection.detect] can return resolves to a lexer.
     *
     * `lexerFor`'s own KDoc states this: "Every language `detect` can return must appear here - a
     * language with no lexer highlights nothing while looking wired up." Nothing enforced it, and
     * it is false today for one id, so the claim and the code have drifted apart in exactly the
     * way this file was written to prevent.
     *
     * The list is spelled out rather than derived, because `when` branches cannot be reflected
     * over. Adding a language to `detect` means adding it here, and this test is what makes
     * forgetting the lexer a failure rather than a file that silently opens as plain text.
     *
     * Two ids `detect` returns are deliberately absent from the list. `text` is the no-match
     * fallback, so a null lexer is the right answer and `unknown languages still return no lexer`
     * below already pins it. `r` is a real gap and has its own test.
     */
    @Test
    fun `every detectable language resolves to a lexer`() {
        val detectable = listOf(
            "actionscript", "bash", "batch", "c", "clojure", "cpp",
            "csharp", "css", "d", "delphi", "diff", "dockerfile",
            "fortran", "go", "groovy", "html", "java", "javascript",
            "json", "jsp", "kotlin", "latex", "lisp", "lua",
            "makefile", "markdown", "perl", "php", "properties", "python",
            "ruby", "rust", "scala", "sql", "swift", "tcl",
            "toml", "typescript", "visualbasic", "xml", "yaml",
        )

        detectable.forEach { language ->
            assertNotNull(
                LanguageDetection.lexerFor(language),
                "detect can return '$language' but no lexer handles it, so those files open plain",
            )
        }
    }

    /**
     * `r` is the exception that is a real gap, pinned here so it is visible rather than silent.
     *
     * `detect` maps `.r` to `r` and `lexerFor` has no branch for it, so an R file is named as R
     * and then highlights as nothing. It cannot be fixed by adding a branch: the bundled
     * `bosseditor-compose-desktop:1.0.26` ships 41 lexers and none of them is an R lexer.
     *
     * This asserts the current behaviour, not the desired one. When an R lexer ships, this test
     * fails, which is the point: it is the reminder to wire it up and move `r` into the list above.
     * Reported on BossConsole#75; the host's table maps `.r` the same way, so the two copies agree
     * and are both wrong, and changing only this one would create a fresh disagreement.
     */
    @Test
    fun `R is detected but has no lexer, and that is a known gap`() {
        assertEquals("r", LanguageDetection.detect("/srv/analysis/model.r"))
        assertNull(
            LanguageDetection.lexerFor("r"),
            "an R lexer now exists: add it to lexerFor and move 'r' into the detectable list",
        )
    }

    // ------------------------------------------------------- host table agreement

    /**
     * Shell scripts are `bash`, which is the id the host's canonical table uses.
     *
     * This copy said `shell` and the host said `bash` for the same file, which is the
     * disagreement BossConsole#75 was filed about: the id a `.sh` file gets depended on which
     * route opened it. Pinned here because nothing at build time compares the two tables, and
     * this repository cannot import the host's.
     */
    @Test
    fun `shell scripts use the host's id`() {
        assertEquals("bash", LanguageDetection.detect("/srv/app/deploy.sh"))
        assertEquals("bash", LanguageDetection.detect("/srv/app/deploy.bash"))
        assertEquals("bash", LanguageDetection.detect("/srv/app/deploy.zsh"))
    }

    /**
     * And the id change does not change which lexer runs.
     *
     * [LanguageDetection.lexerFor] accepts both spellings, so this is the assertion that makes
     * the rename safe rather than merely consistent: a file that highlighted before still does.
     */
    @Test
    fun `both shell spellings still resolve to the same lexer`() {
        val viaDetect = LanguageDetection.lexerFor(LanguageDetection.detect("/srv/app/deploy.sh"))
        assertNotNull(viaDetect, "a detected shell script must reach a lexer")
        assertEquals(
            LanguageDetection.lexerFor("shell")!!::class,
            viaDetect::class,
            "the old id and the new one must select the same lexer",
        )
    }

    @Test
    fun `unknown languages still return no lexer`() {
        assertNull(LanguageDetection.lexerFor("text"))
        assertNull(LanguageDetection.lexerFor("brainfuck"))
    }

    /**
     * CMake is not Make, so it stays unclaimed rather than being highlighted by a
     * lexer that expects tab-indented recipes. Kept identical to the host's
     * EditorLanguages, which made the same call.
     */
    @Test
    fun `CMakeLists is not claimed as makefile`() {
        assertEquals("text", LanguageDetection.detect("/a/CMakeLists.txt"))
    }
}
