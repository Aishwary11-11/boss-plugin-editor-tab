# Temporary BossEditor compatibility overrides

These three sources replace their counterparts from BossEditor 1.0.26 (tag
`v1.0.26`). BOSS 9.5.25 shares
Compose but blocks direct Skia/Skiko access from plugins.

- MinimapCanvas and MinimapRenderer use Compose Canvas/Paint/Rect.
- FontUtils enumerates and measures system fonts with AWT, then selects them
  through Compose FontFamily(name). Font availability can differ from Skia's
  enumeration; missing fonts fall back to system monospace.

The packaging task removes the original classes (including generated classes).
A dependency-version guard requires review when upgrading BossEditor. Remove
these overrides and exclusions once the bundled BossEditor release includes the
upstream fix. Do not bundle another Compose, Skia, or Skiko runtime.

Tests exercise font selection and actual minimap pixels through a classloader
that denies direct Skia/Skiko access, and scan the final plugin artifact.

Upstream fix: https://github.com/risa-labs-inc/BossEditor/pull/24

The three source files are identical between v1.0.13 and v1.0.26 before applying
the compatibility changes. ABI tests compare the override public surfaces with
the resolved dependency; only the paired renderer Canvas signature may change.

The automatic BossEditor bump script pauses while this override README exists;
remove that pause along with these overrides when adopting the upstream fix.

When removing overrides, restore the literal BossEditor dependency declaration
(and remove the version guard), or teach bump-bosseditor.sh to update the version
variable before re-enabling automatic bumps.
