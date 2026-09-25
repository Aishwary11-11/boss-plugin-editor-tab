package ai.rever.boss.plugin.dynamic.editortab

import ai.rever.bosseditor.core.EditorDocument
import ai.rever.bosseditor.features.MinimapConfig
import ai.rever.bosseditor.features.MinimapRenderer
import ai.rever.bosseditor.features.MinimapState
import ai.rever.bosseditor.fold.VisualLineMapper
import ai.rever.bosseditor.highlight.TokenProvider
import ai.rever.bosseditor.theme.EditorColors
import ai.rever.bosseditor.theme.EditorTheme
import androidx.compose.ui.graphics.Canvas
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontListFontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.platform.FontLoader
import java.net.URLClassLoader
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.test.assertFailsWith

class EditorRenderingCompatibilityTest {
    private fun isolatedLoader() = object : URLClassLoader(
        arrayOf(MinimapRenderer::class.java.protectionDomain.codeSource.location),
        javaClass.classLoader
    ) {
        override fun loadClass(name: String, resolve: Boolean): Class<*> {
            if (name.startsWith("org.jetbrains.skia.") || name.startsWith("org.jetbrains.skiko.")) {
                throw ClassNotFoundException("Rendering internals blocked: $name")
            }
            if (name.startsWith("ai.rever.bosseditor.features.MinimapRenderer") ||
                name.startsWith("ai.rever.bosseditor.features.MinimapCanvas") ||
                name.startsWith("ai.rever.bosseditor.settings.FontUtilsKt")) {
                return synchronized(getClassLoadingLock(name)) {
                    (findLoadedClass(name) ?: findClass(name)).also { if (resolve) resolveClass(it) }
                }
            }
            return super.loadClass(name, resolve)
        }
    }

    @Test
    fun `overrides preserve bundled public APIs except paired renderer canvas type`() {
        val names = listOf("features.MinimapRenderer", "features.MinimapCanvasKt",
            "features.MinimapState", "features.MinimapEditorState",
            "features.BasicMinimapEditorState", "settings.FontUtilsKt")
            .map { "ai.rever.bosseditor.$it" }
        val dependency = EditorDocument::class.java.protectionDomain.codeSource.location
        val upstream = object : URLClassLoader(arrayOf(dependency), javaClass.classLoader) {
            override fun loadClass(name: String, resolve: Boolean): Class<*> {
                if (names.any { name == it || name.startsWith(it + "$") }) {
                    return synchronized(getClassLoadingLock(name)) {
                        (findLoadedClass(name) ?: findClass(name)).also { if (resolve) resolveClass(it) }
                    }
                }
                return super.loadClass(name, resolve)
            }
        }
        fun signatures(type: Class<*>): Set<String> {
            fun parameterName(type: Class<*>) = if (type.name == "org.jetbrains.skia.Canvas")
                "androidx.compose.ui.graphics.Canvas" else type.name
            val methods = type.declaredMethods.filter {
                java.lang.reflect.Modifier.isPublic(it.modifiers) && !it.isSynthetic
            }.map { it.name + it.parameterTypes.joinToString(prefix = "(", postfix = ")") { p -> parameterName(p) } + it.returnType.name }
            val constructors = type.constructors.filter { !it.isSynthetic }.map {
                "<init>" + it.parameterTypes.joinToString { p -> p.name }
            }
            val fields = type.fields.map { it.name + ":" + it.type.name }
            return (methods + constructors + fields).toSet()
        }
        upstream.use { loader ->
            for (name in names) {
                val original = signatures(loader.loadClass(name))
                val replacement = signatures(javaClass.classLoader.loadClass(name))
                assertTrue(replacement.containsAll(original), "$name missing APIs: ${original - replacement}")
            }
        }
    }

    @Test
    fun `packaged editor contains no direct Skia references or bundled runtime`() {
        java.util.zip.ZipFile(requireNotNull(System.getProperty("editor.plugin.jar")) { "Run this artifact check using Gradle test so buildPluginJar supplies editor.plugin.jar" }).use { jar ->
            for (entry in jar.entries()) {
                assertFalse(entry.name.startsWith("org/jetbrains/skia/") ||
                    entry.name.startsWith("org/jetbrains/skiko/"), entry.name)
                if (entry.name.startsWith("ai/rever/bosseditor/") && entry.name.endsWith(".class")) {
                    val bytes = jar.getInputStream(entry).use { it.readBytes().toString(Charsets.ISO_8859_1) }
                    assertFalse(bytes.contains("org/jetbrains/skia/") ||
                        bytes.contains("org/jetbrains/skiko/"), entry.name)
                }
            }
        }
    }

    @Test
    fun `minimap renders pixels with direct Skia access blocked`() {
        isolatedLoader().use { loader ->
            assertFailsWith<ClassNotFoundException> { loader.loadClass("org.jetbrains.skia.Canvas") }
            // Resolve composable signatures too: none may expose a native canvas.
            loader.loadClass("ai.rever.bosseditor.features.MinimapCanvasKt").declaredMethods
            val rendererClass = loader.loadClass("ai.rever.bosseditor.features.MinimapRenderer")
            val document = EditorDocument("hello\nworld")
            val colors = EditorTheme.Dark.colors
            val renderer = rendererClass.getConstructor(
                EditorDocument::class.java, TokenProvider::class.java,
                EditorColors::class.java, VisualLineMapper::class.java
            ).newInstance(document, null, colors, VisualLineMapper.noFolds(document.lineCount))
            rendererClass.getMethod("setConfig", MinimapConfig::class.java)
                .invoke(renderer, MinimapConfig(showSlider = false))
            val bitmap = ImageBitmap(80, 80)
            rendererClass.getMethod("render", Canvas::class.java, Float::class.javaPrimitiveType,
                Float::class.javaPrimitiveType, Float::class.javaPrimitiveType,
                Float::class.javaPrimitiveType, MinimapState::class.java
            ).invoke(renderer, Canvas(bitmap), 0f, 0f, 80f, 80f,
                MinimapState(visibleLineCount = 0, currentLine = -1))
            val pixels = bitmap.toPixelMap()
            assertEquals(colors.background, pixels[40, 70])
            assertEquals(colors.text, pixels[3, 0], "Document content must be painted")
        }
    }

    @Suppress("DEPRECATION")
    @Test
    fun `font enumeration selection and missing font fallback work without Skia access`() {
        isolatedLoader().use { loader ->
            val fonts = loader.loadClass("ai.rever.bosseditor.settings.FontUtilsKt")
            val categorized = fonts.getMethod("getEditorCategorizedFonts").invoke(null) as Map<*, *>
            assertEquals(setOf("Recommended", "Fixed Pitch", "Variable Pitch"), categorized.keys)
            val offered = categorized.values.flatMap { (it as List<*>).filterIsInstance<String>() }
            for (logical in listOf("Dialog", "DialogInput", "Monospaced", "Serif", "SansSerif")) {
                assertFalse(logical in offered)
                assertEquals(false, fonts.getMethod("isFontInstalled", String::class.java).invoke(null, logical))
                assertEquals(FontFamily.Monospace, fonts.getMethod("loadEditorFont", String::class.java).invoke(null, logical))
            }
            val installed = offered.first { name ->
                // Pick a real physical family offered by the picker and supported by
                // this test host. Loading it below must not silently resolve a fallback.
                org.jetbrains.skia.FontMgr.default.matchFamilyStyle(name, org.jetbrains.skia.FontStyle.NORMAL)
                    ?.familyName == name
            }
            assertEquals(true, fonts.getMethod("isFontInstalled", String::class.java).invoke(null, installed))
            val selected = assertIs<FontListFontFamily>(fonts.getMethod("loadEditorFont", String::class.java)
                .invoke(null, installed.lowercase(java.util.Locale.ROOT)))
            val regular = selected.fonts.first { it.weight == FontWeight.Normal && it.style == FontStyle.Normal }
            assertEquals(installed, FontLoader().load(regular).familyName)
            assertIs<FontFamily>(fonts.getMethod("loadEditorFont", String::class.java).invoke(null, null))
            val missing = "BOSS definitely missing font 3e3598a6"
            assertFalse(fonts.getMethod("isFontInstalled", String::class.java).invoke(null, missing) as Boolean)
            assertEquals(FontFamily.Monospace, fonts.getMethod("loadEditorFont", String::class.java).invoke(null, missing))
        }
    }
}
