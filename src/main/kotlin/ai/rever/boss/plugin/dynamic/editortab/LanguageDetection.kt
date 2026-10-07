package ai.rever.boss.plugin.dynamic.editortab

import ai.rever.bosseditor.highlight.lexers.ActionScriptLexer
import ai.rever.bosseditor.highlight.lexers.BaseLexer
import ai.rever.bosseditor.highlight.lexers.BatchLexer
import ai.rever.bosseditor.highlight.lexers.CLexer
import ai.rever.bosseditor.highlight.lexers.CSharpLexer
import ai.rever.bosseditor.highlight.lexers.ClojureLexer
import ai.rever.bosseditor.highlight.lexers.CssLexer
import ai.rever.bosseditor.highlight.lexers.DLexer
import ai.rever.bosseditor.highlight.lexers.DelphiLexer
import ai.rever.bosseditor.highlight.lexers.DiffLexer
import ai.rever.bosseditor.highlight.lexers.DockerfileLexer
import ai.rever.bosseditor.highlight.lexers.FortranLexer
import ai.rever.bosseditor.highlight.lexers.GoLexer
import ai.rever.bosseditor.highlight.lexers.GroovyLexer
import ai.rever.bosseditor.highlight.lexers.HtmlLexer
import ai.rever.bosseditor.highlight.lexers.JavaLexer
import ai.rever.bosseditor.highlight.lexers.JavaScriptLexer
import ai.rever.bosseditor.highlight.lexers.JsonLexer
import ai.rever.bosseditor.highlight.lexers.JspLexer
import ai.rever.bosseditor.highlight.lexers.KotlinLexer
import ai.rever.bosseditor.highlight.lexers.LaTeXLexer
import ai.rever.bosseditor.highlight.lexers.LispLexer
import ai.rever.bosseditor.highlight.lexers.LuaLexer
import ai.rever.bosseditor.highlight.lexers.MakefileLexer
import ai.rever.bosseditor.highlight.lexers.MarkdownLexer
import ai.rever.bosseditor.highlight.lexers.PHPLexer
import ai.rever.bosseditor.highlight.lexers.PerlLexer
import ai.rever.bosseditor.highlight.lexers.PropertiesLexer
import ai.rever.bosseditor.highlight.lexers.PythonLexer
import ai.rever.bosseditor.highlight.lexers.RubyLexer
import ai.rever.bosseditor.highlight.lexers.RustLexer
import ai.rever.bosseditor.highlight.lexers.ScalaLexer
import ai.rever.bosseditor.highlight.lexers.ShellLexer
import ai.rever.bosseditor.highlight.lexers.SqlLexer
import ai.rever.bosseditor.highlight.lexers.SwiftLexer
import ai.rever.bosseditor.highlight.lexers.TclLexer
import ai.rever.bosseditor.highlight.lexers.TomlLexer
import ai.rever.bosseditor.highlight.lexers.TypeScriptLexer
import ai.rever.bosseditor.highlight.lexers.VisualBasicLexer
import ai.rever.bosseditor.highlight.lexers.XmlLexer
import ai.rever.bosseditor.highlight.lexers.YamlLexer

/**
 * Maps a file path to a language, and a language to a lexer.
 *
 * Pulled out of `EditorTabComponent`'s companion so it can be tested: it is pure
 * lookup with no Compose or Decompose involvement, and reaching it through the
 * component meant loading `ComponentContext`, which is `compileOnly` (the host
 * supplies it) and therefore absent from the test classpath.
 *
 * This chain had a silent hole worth remembering. BossEditor ships working lexers
 * for Dockerfile, Makefile, Properties, Diff and a dozen more, but nothing mapped
 * a file onto them — so those files opened as plain text with no error anywhere.
 * A gap here is invisible, which is why it is now covered by tests.
 */
internal object LanguageDetection {

    /**
     * Language id for [filePath].
     *
     * File *name* patterns are checked before the extension, because the files that
     * need it most have no extension at all: `Dockerfile` and `Makefile` are the
     * canonical spellings and `substringAfterLast('.')` yields `""` for both.
     *
     * The extension is read from the file name rather than the whole path. Reading
     * it from the path let a dot in a parent directory leak into the answer —
     * `/srv/v1.2/Makefile` produced the "extension" `2/Makefile`.
     */
    fun detect(filePath: String): String {
        val fileName = filePath.substringAfterLast('/').substringAfterLast('\\')
        forFileName(fileName)?.let { return it }
        return forExtension(fileName.substringAfterLast('.', "").lowercase())
    }

    /**
     * Languages identified by file name rather than extension.
     *
     * Separate from [forExtension] so the precedence is explicit: `Dockerfile.dev`
     * is a Dockerfile, not whatever `.dev` might otherwise suggest.
     */
    private fun forFileName(fileName: String): String? {
        val lower = fileName.lowercase()
        return when {
            lower == "dockerfile" || lower.startsWith("dockerfile.") -> "dockerfile"
            // Podman/OCI's spelling of the same thing.
            lower == "containerfile" || lower.startsWith("containerfile.") -> "dockerfile"
            lower == "makefile" || lower == "gnumakefile" || lower.startsWith("makefile.") -> "makefile"
            // `CMakeLists.txt` deliberately absent: CMake is not Make. A Make lexer
            // looks for tab-indented recipes that aren't there and misses
            // `if()/endif()` and `${VAR}`, so it highlights confidently wrong. It was
            // also asymmetric — the `.cmake` files beside it were never claimed.
            lower == ".env" || lower.startsWith(".env.") -> "properties"
            lower == "gemfile" || lower == "rakefile" -> "ruby"
            else -> null
        }
    }

    private fun forExtension(extension: String): String = when (extension) {
        "kt", "kts" -> "kotlin"
        "java" -> "java"
        "js", "jsx", "mjs", "cjs" -> "javascript"
        "ts", "tsx" -> "typescript"
        "py", "pyw" -> "python"
        "json" -> "json"
        "xml" -> "xml"
        "html", "htm" -> "html"
        "css", "scss", "sass" -> "css"
        "md", "markdown" -> "markdown"
        "toml" -> "toml"
        "gradle" -> "groovy"
        "swift" -> "swift"
        "c", "h" -> "c"
        "cpp", "cc", "cxx", "hpp" -> "cpp"
        "cs" -> "csharp"
        "rs" -> "rust"
        "go" -> "go"
        "rb" -> "ruby"
        "php" -> "php"
        "pl", "pm" -> "perl"
        "lua" -> "lua"
        // `bash`, not `shell`, to match the host's canonical table
        // (`plugin-platform/plugin-language-types`'s `LanguageIds`). BossConsole#75 tracks this
        // mapping existing in several places that disagreed; this was the last disagreement
        // between that table and this one. Both spellings resolve to `ShellLexer` in [lexerFor]
        // below, so the same `.sh` file now gets the same id whichever route opens it, and the
        // lexer that runs is unchanged.
        "sh", "bash", "zsh" -> "bash"
        "yml", "yaml" -> "yaml"
        "sql" -> "sql"
        "r" -> "r"
        "scala" -> "scala"
        // Extensions for lexers that shipped but had no route to them.
        "dockerfile" -> "dockerfile"
        "mk", "mak" -> "makefile"
        "properties", "ini", "cfg", "env" -> "properties"
        "diff", "patch" -> "diff"
        "bat", "cmd" -> "batch"
        "clj", "cljs", "cljc", "edn" -> "clojure"
        "tex", "sty", "cls", "bib" -> "latex"
        "lisp", "lsp", "el", "scm" -> "lisp"
        "tcl" -> "tcl"
        "f", "f90", "f95", "f03", "for" -> "fortran"
        "d" -> "d"
        "pas", "dpr", "dfm" -> "delphi"
        "vb", "vbs" -> "visualbasic"
        "as" -> "actionscript"
        "jsp", "jspx" -> "jsp"
        else -> "text"
    }

    /**
     * A lexer for [language], or null to fall back to plain text.
     *
     * Every language [detect] can return must appear here — a language with no
     * lexer highlights nothing while looking wired up, which is the exact failure
     * this file was written to end.
     */
    fun lexerFor(language: String): BaseLexer? = when (language) {
        "kotlin", "kt", "kts" -> KotlinLexer()
        "java" -> JavaLexer()
        "javascript", "js", "jsx", "mjs", "cjs" -> JavaScriptLexer()
        "typescript", "ts", "tsx", "mts", "cts" -> TypeScriptLexer()
        "python", "py", "pyw", "pyi", "pyx" -> PythonLexer()
        "json", "jsonc", "json5" -> JsonLexer()
        "xml", "pom", "fxml", "xsd", "xsl", "xslt" -> XmlLexer()
        "html", "htm", "xhtml" -> HtmlLexer()
        "css", "scss", "sass", "less" -> CssLexer()
        "yaml", "yml" -> YamlLexer()
        "markdown", "md", "mdx" -> MarkdownLexer()
        "shell", "sh", "bash", "zsh", "fish" -> ShellLexer()
        "sql", "mysql", "postgresql", "sqlite" -> SqlLexer()
        "go", "golang" -> GoLexer()
        "rust", "rs" -> RustLexer()
        "swift" -> SwiftLexer()
        "c", "h" -> CLexer()
        "cpp", "cc", "cxx", "hpp", "hxx", "c++" -> CLexer()
        "csharp", "cs" -> CSharpLexer()
        "groovy", "gradle", "gvy", "gy", "gsh" -> GroovyLexer()
        "scala", "sc" -> ScalaLexer()
        "ruby", "rb", "erb", "rake" -> RubyLexer()
        "php", "php3", "php4", "php5", "phtml" -> PHPLexer()
        "perl", "pl", "pm", "pod" -> PerlLexer()
        "lua" -> LuaLexer()
        "toml" -> TomlLexer()
        // Newly reachable.
        "dockerfile", "containerfile" -> DockerfileLexer()
        "makefile", "make" -> MakefileLexer()
        "properties", "ini", "env" -> PropertiesLexer()
        "diff", "patch" -> DiffLexer()
        "batch", "bat", "cmd" -> BatchLexer()
        "clojure", "clj" -> ClojureLexer()
        "latex", "tex" -> LaTeXLexer()
        "lisp", "elisp", "scheme" -> LispLexer()
        "tcl" -> TclLexer()
        "fortran" -> FortranLexer()
        "d" -> DLexer()
        "delphi", "pascal" -> DelphiLexer()
        "visualbasic", "vb" -> VisualBasicLexer()
        "actionscript" -> ActionScriptLexer()
        "jsp" -> JspLexer()
        else -> null
    }
}
