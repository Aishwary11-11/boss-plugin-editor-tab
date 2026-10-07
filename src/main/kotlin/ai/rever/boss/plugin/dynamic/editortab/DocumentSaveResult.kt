package ai.rever.boss.plugin.dynamic.editortab

internal enum class DocumentSaveResult(
    val message: String?,
) {
    SAVED(null),
    UNCHANGED(null),
    CONFLICT("File changed on disk; resolve the conflict before saving"),
    FAILED("Failed to save file; your changes are still unsaved"),
    UNAVAILABLE("File saving is unavailable; your changes are still unsaved"),
}
