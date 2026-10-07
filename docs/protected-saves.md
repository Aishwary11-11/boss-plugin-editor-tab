# Protected document saves

Cmd/Ctrl+S, autosave, and saves in the editable diff pane use the same
`saveEditorDocument` transaction. It stages UTF-8 content beside the destination,
syncs the completed staging file, and atomically replaces the destination through
the plugin's local `AtomicFileWrite`. It does not depend on a protected host
writer or raise the plugin's minimum host version.

A failed save leaves the document modified and reports an error. Successful-write
signatures, external state, and git-refresh bookkeeping advance only after the
write commits. Saves serialize per shared buffer, including separate editor and
diff viewports. A save marks only its captured document version saved, so typing
during a write remains dirty. Canceling an autosave timer after writing starts
does not skip commit bookkeeping. The watcher's final state update and explicit
Reload/Keep mine choices serialize with the same transaction, so a reload cannot
mark a different buffer version clean while an earlier save is still committing.
Watcher disk reads and dispatcher hops happen before it acquires the save lock;
the short locked update rechecks the captured baseline and document version.

External edits and unresolved watcher conflicts are checked before saving. The
editable diff pane offers the same Reload and Keep mine choices as the editor.
This is a pre-save check rather than a filesystem compare-and-swap: another
program can still modify the file between the check and commit.

## Filesystem limits

Saving requires enough temporary disk space and write access to the containing
directory. Each save uses a unique sibling staging file; ordinary failures clean
it up. A failed first save leaves no partial destination. Existing symlinks are
written through rather than replaced, and cycles fail safely.

If the filesystem does not support atomic replacement, saving fails with the
original intact. Copy the unsaved text to a file on a local filesystem that
supports atomic replacement instead of retrying a destructive in-place write.
Read-only destinations and locks can also prevent replacement.

The local writer preserves supported ownership, POSIX group and rwx permissions,
Windows ACLs, and DOS hidden/system/read-only flags. Edited DOS files are marked
for incremental backup with the archive flag set. A failure reading or reapplying
supported metadata fails the save; shared-file ownership may require additional permission.
New POSIX files respect the process umask. Special mode bits and arbitrary
extended attributes are not preserved. Atomic replacement changes file identity:
hard-linked aliases retain the old contents.
Process termination can leave staging output, and power-loss durability is not
promised.

## Host compatibility and provenance

[Editor Tab #40](https://github.com/risa-labs-inc/boss-plugin-editor-tab/pull/40)
introduced the local staged writer. This transaction incorporates the shared
save pipeline, cancellation/version tracking, editable diff conflict handling,
and regression coverage contributed by Aishwary Anand in
[Editor Tab #32](https://github.com/risa-labs-inc/boss-plugin-editor-tab/pull/32)
(original commit `6496b559dd7314b702c0c17569dde220bf0fea10`, with diff-conflict
follow-up `2e077c5762e9ec75108d238a94b609e751c99e01`). The integration uses the
local writer instead of #32's host-provider callback.

[BossConsole #427](https://github.com/risa-labs-inc/BossConsole/pull/427) remains
independently useful for other callers of the host's
`EditorContentProvider`. Published BOSS 9.5.41 still delegates that provider to
a direct `File.writeText`, so normal document saves use the protected local
writer. A future provider migration must verify the first host release containing
the protected writer and enforce its actual version floor; #32's reserved 9.5.12
floor alone is insufficient.

Merge [Editor Tab #38](https://github.com/risa-labs-inc/boss-plugin-editor-tab/pull/38)
before #40 when possible. Its queued-observation baseline/version guard is also
necessary for the save integration here: a save or conflict choice can supersede
a watcher read before it reaches the UI thread. Keep its race tests when merging
the save lock, and never hold that lock while waiting to dispatch the UI update.

## MCP disk writes

`editor_write_file` uses the same local protected writer on a background I/O
dispatcher, including when the host editor provider is unavailable. It retains
the `editor.write` permission requirement and validates both path and content
before writing. Missing or null content fails rather than erasing a document;
explicit empty content still means an intentional empty file.

The tool remains a disk-only create/overwrite operation without a document-version
guard. Its write waits for save transactions on all open buffers matching the
physical target, including dot-path and symlink aliases. It acquires their locks
in a stable order without changing registry keys or the disk argument. It does
not mark buffers saved or update their disk baselines: the
watcher still surfaces a conflict if the tool changes disk beneath unsaved edits.
Use `editor_apply_edit` for version-checked changes to live editor buffers.

On failure the tool returns an error without the edited text or raw exception.
Partial staging failures preserve an existing file, leave a failed first-save
destination absent, and clean up staging output. The same filesystem and metadata
limits described above apply.

This is the remaining protection added by #32 after #40 incorporated its original
document-save pipeline. It has no new host-version dependency; the manifest keeps
the current host floor instead of guessing a future release number. Host #427
remains independently useful for other plugins calling `EditorContentProvider`.

## Regression coverage

`EditorDocumentSaveTest` covers failed/unavailable writers, partial staging
failures, shorter UTF-8 replacement, conflict resolution, shared-viewport save
serialization, cancellation after writing starts, and newer typing staying
dirty. It also exercises the default local writer without a host provider.
`AtomicFileWriteTest` checks the production staging, replacement, symlink,
permission, concurrency, and cleanup behavior separately.
`EditorMcpAtomicWriteTest` calls the actual MCP handler and checks old/missing
providers, partial production staging failures, UTF-8/empty writes, argument and
permission contracts, I/O dispatch, live-buffer transactions, and existing or
absent targets reached through dot paths, symlinks, and symlink/.. traversal.
