package ai.rever.bosseditor.settings

import ai.rever.bosseditor.lsp.logging.LogCategory
import ai.rever.bosseditor.lsp.logging.LspLogger
import androidx.compose.ui.text.font.FontFamily
import java.awt.Font
import java.awt.GraphicsEnvironment
import java.awt.font.FontRenderContext
import java.awt.geom.AffineTransform

private val fontLogger = LspLogger.forComponent("FontUtils")

/**
 * Special value indicating the default editor font should be used.
 */
const val DEFAULT_EDITOR_FONT_NAME = "JetBrains Mono (Default)"

/** Section name for recommended fonts */
const val FONT_SECTION_RECOMMENDED = "Recommended"
/** Section name for fixed pitch (monospace) fonts */
const val FONT_SECTION_FIXED_PITCH = "Fixed Pitch"
/** Section name for variable pitch (proportional) fonts */
const val FONT_SECTION_VARIABLE_PITCH = "Variable Pitch"

/**
 * List of recommended programming fonts (JetBrains-style).
 * These fonts are specifically designed for code editing.
 */
private val RECOMMENDED_FONTS = listOf(
    "JetBrains Mono",
    "Fira Code",
    "Source Code Pro",
    "Cascadia Code",
    "Cascadia Mono",
    "SF Mono",
    "Monaco",
    "Menlo",
    "Consolas",
    "Inconsolata",
    "Ubuntu Mono",
    "Roboto Mono",
    "Hack",
    "IBM Plex Mono",
    "Anonymous Pro",
    "Droid Sans Mono",
    "DejaVu Sans Mono",
    "Liberation Mono",
    "Courier New"
)

/**
 * Cache for categorized fonts to avoid repeated system font scanning.
 */
private val cachedFonts: Map<String, List<String>> by lazy { categorizeEditorFonts() }

/**
 * Get fonts organized by category (JetBrains IDE-style).
 * Returns a map with sections: "Recommended", "Fixed Pitch", "Variable Pitch"
 *
 * The Recommended section contains well-known programming fonts that are installed.
 * Fixed Pitch contains all monospace fonts.
 * Variable Pitch contains proportional fonts (for those who prefer them).
 */
fun getEditorCategorizedFonts(): Map<String, List<String>> = cachedFonts

private fun categorizeEditorFonts(): Map<String, List<String>> {

    val allFamilies = systemFontFamilies()
    val fontContext = FontRenderContext(AffineTransform(), true, true)

    val fixedPitch = mutableListOf<String>()
    val variablePitch = mutableListOf<String>()
    val recommended = mutableListOf<String>()

    // First, identify which recommended fonts are available
    for (fontName in RECOMMENDED_FONTS) {
        if (allFamilies.contains(fontName)) {
            recommended.add(fontName)
        }
    }

    // Then categorize all fonts
    for (familyName in allFamilies) {
        // Skip if already in recommended
        if (recommended.contains(familyName)) continue

        try {
            val font = Font(familyName, Font.PLAIN, 12)
            val widthW = font.getStringBounds("W", fontContext).width
            val widthI = font.getStringBounds("i", fontContext).width
            if (kotlin.math.abs(widthW - widthI) < 0.1) {
                fixedPitch.add(familyName)
            } else {
                variablePitch.add(familyName)
            }
        } catch (e: Exception) {
            // Skip fonts that fail to load
        }
    }

    val result = linkedMapOf(
        FONT_SECTION_RECOMMENDED to (listOf(DEFAULT_EDITOR_FONT_NAME) + recommended),
        FONT_SECTION_FIXED_PITCH to fixedPitch.sorted(),
        FONT_SECTION_VARIABLE_PITCH to variablePitch.sorted()
    )

    return result
}

/**
 * Get list of available monospace fonts on the system.
 * Includes recommended fonts first.
 */
fun getAvailableEditorFonts(): List<String> {
    val categorized = getEditorCategorizedFonts()
    return categorized[FONT_SECTION_RECOMMENDED]!! +
            categorized[FONT_SECTION_FIXED_PITCH]!! +
            categorized[FONT_SECTION_VARIABLE_PITCH]!!
}

/**
 * Load editor font by name.
 * @param fontName Font name from system fonts, or null/empty/DEFAULT_EDITOR_FONT_NAME for default.
 * @return FontFamily for editor rendering
 */
fun loadEditorFont(fontName: String? = null): FontFamily {
    // Use default monospace if no name specified or if it's the default marker
    if (fontName.isNullOrEmpty() || fontName == DEFAULT_EDITOR_FONT_NAME) {
        // Try to load JetBrains Mono first, then fall back to system monospace
        return tryLoadSystemFont("JetBrains Mono") ?: FontFamily.Monospace
    }

    return tryLoadSystemFont(fontName) ?: FontFamily.Monospace.also {
        fontLogger.warn(LogCategory.GENERAL, "Configured font unavailable; using monospace", data = mapOf("font" to fontName))
    }
}

/**
 * Try to load a system font by family name.
 * @return FontFamily if successful, null otherwise
 */
@OptIn(androidx.compose.ui.text.ExperimentalTextApi::class)
private fun tryLoadSystemFont(fontName: String): FontFamily? {
    return try {
        val matchedFamily = systemFontFamilies().firstOrNull { it.equals(fontName, ignoreCase = true) }
        if (matchedFamily != null) {
            FontFamily(matchedFamily)
        } else {
            null
        }
    } catch (e: Exception) {
        fontLogger.warn(LogCategory.GENERAL, "Failed to load font", data = mapOf("font" to fontName), error = e)
        null
    }
}

/**
 * Check if a font is installed on the system.
 */
fun isFontInstalled(fontName: String): Boolean {
    if (fontName == DEFAULT_EDITOR_FONT_NAME) return true
    return try {
        systemFontFamilies().any { it.equals(fontName, ignoreCase = true) }
    } catch (e: Exception) {
        false
    }
}

// AWT enumerates system fonts without requiring plugin access to Skia. Actual
// rendering and typeface ownership stay with the host's Compose FontFamily API.
private fun systemFontFamilies(): Set<String> = cachedSystemFamilies

// These AWT aliases are not physical families understood by Compose's Skia
// resolver. In particular "Monospaced" must use FontFamily.Monospace instead.
private val awtLogicalFamilies = setOf("dialog", "dialoginput", "monospaced", "serif", "sansserif")

private val cachedSystemFamilies: Set<String> by lazy {
    GraphicsEnvironment.getLocalGraphicsEnvironment().availableFontFamilyNames
        .filter { it.isNotEmpty() && it.lowercase(java.util.Locale.ROOT) !in awtLogicalFamilies }.toSet()
}
