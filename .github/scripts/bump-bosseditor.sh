#!/usr/bin/env bash
#
# Auto-bump the privately-bundled BossEditor version in build.gradle.kts.
#
# Run from the repo root. Reads the currently bundled `bosseditor-compose-desktop`
# version, resolves the latest release that is actually live on Maven Central, and
# — if a newer one exists — rewrites the dependency line and the plugin version,
# then writes a PR body to pr-body.md. The calling workflow does the git/PR/merge.
#
# The counterpart to terminal-tab's bump-bossterm.sh. BossEditor is bundled into
# this plugin's JAR rather than provided by the host, so picking up a BossEditor
# release means re-releasing this plugin and nothing else.
#
# Intentionally deterministic (no AI). Portable sed/awk so it also runs on macOS
# for local testing.
#
# Inputs (env):
#   TARGET_VERSION  optional — force a specific bosseditor version (manual/test runs)
#   GITHUB_OUTPUT   optional — GitHub Actions step-output file (falls back to stdout)
#
# Outputs (to $GITHUB_OUTPUT):
#   changed=true|false, new_be=<ver>, new_plugin=<ver>
set -euo pipefail

GRADLE_FILE="build.gradle.kts"
ARTIFACT="bosseditor-compose-desktop"
BASE_URL="https://repo1.maven.org/maven2/com/risaboss/${ARTIFACT}"
OUT="${GITHUB_OUTPUT:-/dev/stdout}"

emit() { printf '%s\n' "$1" >> "$OUT"; }
noop() { echo "→ $1"; emit "changed=false"; exit 0; }

# Source overrides are tied to one BossEditor ABI. An automatic update must not
# replace the guarded dependency until those overrides are removed/reviewed.
if [ -f src/main/kotlin/ai/rever/bosseditor/README.md ]; then
  noop "BossEditor compatibility overrides are active; review/remove them before upgrading"
fi

# 1. Current versions from build.gradle.kts
current_be=$(sed -n "s/.*implementation(\"com\.risaboss:${ARTIFACT}:\(.*\)\").*/\1/p" "$GRADLE_FILE" | head -1)
current_plugin=$(sed -n 's/^version = "\(.*\)"/\1/p' "$GRADLE_FILE")
[ -n "$current_be" ]     || { echo "ERROR: could not read the $ARTIFACT version from $GRADLE_FILE" >&2; exit 1; }
[ -n "$current_plugin" ] || { echo "ERROR: could not read plugin version from $GRADLE_FILE" >&2; exit 1; }
echo "Current: bosseditor=$current_be plugin=$current_plugin"

# 2. Latest live release on Maven Central (or forced TARGET_VERSION)
latest="${TARGET_VERSION:-}"
if [ -z "$latest" ]; then
  latest=$(curl -fsSL "${BASE_URL}/maven-metadata.xml" | sed -n 's:.*<release>\(.*\)</release>.*:\1:p' | head -1)
fi
[ -n "$latest" ] || { echo "ERROR: could not resolve latest $ARTIFACT version from Maven Central" >&2; exit 1; }
echo "Maven Central latest release: $latest"

# 3. Newer than what we bundle? (semver-safe; sort -V)
if [ "$latest" = "$current_be" ]; then
  noop "Already bundling the latest bosseditor ($current_be) — nothing to do"
fi
newest=$(printf '%s\n%s\n' "$current_be" "$latest" | sort -V | tail -1)
if [ "$newest" != "$latest" ]; then
  noop "Current $current_be is newer than Maven's $latest (TARGET_VERSION override?) — nothing to do"
fi

# 4. Belt-and-suspenders: the POM must actually be served before we open a PR.
#    maven-metadata.xml can list a version before the CDN serves its files, and a
#    bump opened in that window produces a PR whose build cannot resolve.
pom_url="${BASE_URL}/${latest}/${ARTIFACT}-${latest}.pom"
if ! curl -fsI "$pom_url" >/dev/null 2>&1; then
  echo "::warning::bosseditor $latest is in maven-metadata but its POM is not served yet; will retry next run"
  noop "Artifact $latest not yet resolvable on Maven Central"
fi

# 5. New plugin version: +1 patch from current (matches the repo's release cadence)
IFS='.' read -r MA MI PA <<EOF
$current_plugin
EOF
new_plugin="${MA}.${MI}.$((PA + 1))"
echo "Bumping: bosseditor $current_be → $latest, plugin $current_plugin → $new_plugin"

# 6. Rewrite build.gradle.kts (deterministic, anchored on exact lines)
release_url="https://github.com/risa-labs-inc/BossEditor/releases/tag/v${latest}"
tmp="$(mktemp)"
awk -v artifact="$ARTIFACT" \
    -v old_be="$current_be" -v new_be="$latest" \
    -v old_pv="$current_plugin" -v new_pv="$new_plugin" \
    -v notes="$release_url" '
  $0 == "version = \"" old_pv "\"" {
    print "// " new_pv ": auto-bumped bundled BossEditor to " new_be
    print "// (release: " notes ")."
    print "version = \"" new_pv "\""
    next
  }
  $0 == "    implementation(\"com.risaboss:" artifact ":" old_be "\")" {
    print "    implementation(\"com.risaboss:" artifact ":" new_be "\")"
    next
  }
  { print }
' "$GRADLE_FILE" > "$tmp"

# Sanity: both lines must have actually changed. A silently-unmatched anchor is
# the failure mode this guards — it would otherwise open an empty PR.
grep -q "implementation(\"com\.risaboss:${ARTIFACT}:${latest}\")" "$tmp" \
  || { echo "ERROR: $ARTIFACT dependency rewrite failed" >&2; exit 1; }
grep -q "^version = \"${new_plugin}\"$" "$tmp" \
  || { echo "ERROR: plugin version rewrite failed" >&2; exit 1; }
mv "$tmp" "$GRADLE_FILE"

# 7. PR body
{
  printf 'Bumps the privately-bundled `%s` from **%s → %s** and the plugin version to **%s**.\n\n' \
    "$ARTIFACT" "$current_be" "$latest" "$new_plugin"
  printf 'BossEditor is bundled into this plugin JAR rather than provided by the host, so picking up a BossEditor release means re-releasing this plugin and nothing else.\n\n'
  printf 'Release: %s\n' "$release_url"
  printf 'Changelog: https://github.com/risa-labs-inc/BossEditor/compare/v%s...v%s\n\n' "$current_be" "$latest"
  printf '_Opened automatically by the BossEditor Auto-Bump workflow once `%s` was live on Maven Central._\n' "$latest"
} > pr-body.md

emit "changed=true"
emit "new_be=${latest}"
emit "new_plugin=${new_plugin}"
echo "✅ Prepared bump to bosseditor $latest (plugin $new_plugin)"
