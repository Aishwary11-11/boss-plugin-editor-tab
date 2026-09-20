package ai.rever.boss.plugin.dynamic.editortab

import java.io.File
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.file.FileSystemException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.nio.file.attribute.PosixFileAttributeView

/**
 * Writing a document without being able to destroy the one already there (#31).
 *
 * `File.writeText` opens the destination and truncates it before writing a byte. Every
 * failure after that point - a full disk, a network share dropping, the process being
 * killed mid-save - leaves the file empty or half written, and the previous contents are
 * gone. For an editor that is the user's document, and auto save fires on a timer they
 * are not thinking about, so the window is open constantly rather than at moments they
 * choose.
 *
 * So nothing is ever written in place. The content goes to a staging file beside the
 * destination, is forced to disk, and is then moved over the destination in one
 * operation. A failure anywhere before the move leaves the original untouched and the
 * staging file deleted; the move itself is the only step that changes what the path
 * points at.
 *
 * **Why not the host's `EditorContentProvider.writeFileContent`**, which BossConsole#427
 * is making safe and which this plugin's `editor_write_file` tool already uses:
 *
 * - It is not safe yet, and the plugin cannot tell whether a given host carries the fix.
 *   Plugins release independently of the host, so a version that routed saves through an
 *   older host's provider would still truncate the user's file - a regression handed to
 *   exactly the users this change exists to protect. There is no capability query to
 *   gate that on, and the provider returns only `Boolean`.
 * - The editor tab is the editor. Its save has always gone straight to disk, and sending
 *   it through the host's *editor content* provider would make a save a round trip
 *   through the subsystem that owns editor buffers. That is a new path with its own
 *   re-entrancy questions, and this plugin has already shipped one stack overflow on the
 *   provider route (#18, #27).
 *
 * This is the same staged-write contract as the host PR rather than a divergent one, and
 * when the API can say "this host writes safely", the two collapse into one call.
 *
 * **What atomicity does and does not promise.** The rename is atomic on POSIX and on
 * NTFS, so a reader sees either the old file or the new one, never a partial document.
 * It replaces the *directory entry*, so the destination gets a new inode: hard links to
 * the old file keep the old contents, and anything holding the path open by inode is
 * looking at the previous version. A symlink destination is resolved first and written
 * through, so the link survives instead of being replaced by a regular file. Where
 * `ATOMIC_MOVE` is refused - some network filesystems, and Windows when a scanner or
 * sync agent holds the target open - the move is retried non-atomically, which is a
 * narrower window than a truncating write but not a closed one.
 */
internal object AtomicFileWrite {

    /** Marks a staging file as ours, so a leftover is identifiable. */
    private const val STAGING_SUFFIX = ".boss-save"

    /**
     * Write [content] to [file] as UTF-8, or throw leaving the file as it was.
     *
     * [stage] exists so a test can fail the write half way through, which is the
     * failure this whole file is about and the one that cannot be provoked from
     * outside. Production callers use the default.
     *
     * @throws IOException if the content could not be staged or moved into place.
     */
    fun writeText(file: File, content: String, stage: (Path, String) -> Unit = ::stageBytes) {
        // Write through a symlink rather than over it: dotfiles and shared config are
        // routinely symlinked into a project, and replacing the link with a regular file
        // silently detaches it from whatever it pointed at.
        val target = resolveLink(file.toPath())
        val directory = target.parent
            ?: throw IOException("Cannot save ${file.path}: it has no parent directory")

        // A unique staging file per call, never a fixed "<name>.tmp". Auto save and a
        // Cmd+S can overlap, and two writers sharing one staging path would interleave
        // their bytes and then move the result into place - a corrupt document produced
        // by the mechanism meant to prevent one.
        val staging = Files.createTempFile(directory, file.name, STAGING_SUFFIX)
        try {
            stage(staging, content)
            // The destination's mode, not the staging file's: createTempFile is 0600, so
            // without this a save would quietly make a group-readable file private.
            copyPermissions(from = target, to = staging)
            replace(staging, target)
            // The rename is metadata of its own: forcing the file's bytes does not
            // persist the directory entry that points at them.
            runCatching {
                FileChannel.open(directory, StandardOpenOption.READ).use { it.force(true) }
            }
        } finally {
            // A successful move consumed the staging file, so this only fires on failure
            // - which is precisely when a leftover would otherwise sit next to the user's
            // document forever. Guarded so a failing delete cannot mask the real error.
            runCatching { Files.deleteIfExists(staging) }
        }
    }

    /** Put [content] in [staging] and get it onto the platter before it is moved. */
    private fun stageBytes(staging: Path, content: String) {
        FileChannel.open(staging, StandardOpenOption.WRITE).use { channel ->
            // write() may write fewer bytes than the buffer holds. It virtually always
            // completes for a local file, but a short write here would stage a truncated
            // document and then install it atomically - the exact loss this prevents.
            val bytes = ByteBuffer.wrap(content.toByteArray(Charsets.UTF_8))
            while (bytes.hasRemaining()) channel.write(bytes)
            // Before the rename, not after: the rename can persist while the bytes it
            // points at are still only in the page cache, which turns a crash into a
            // present-but-empty file.
            channel.force(true)
        }
    }

    /** The file a symlink points at, or [path] itself. */
    private fun resolveLink(path: Path): Path =
        if (Files.isSymbolicLink(path)) {
            // A dangling link - a stale relative target, a half-synced cloud folder -
            // makes toRealPath throw. Writing to the link path then creates the file the
            // link promises, which is better than refusing to save.
            runCatching { path.toRealPath() }.getOrDefault(path)
        } else {
            path
        }

    /** Mirror [from]'s POSIX mode onto [to], where the platform has one. */
    private fun copyPermissions(from: Path, to: Path) {
        runCatching {
            if (!Files.exists(from)) return
            val view = Files.getFileAttributeView(from, PosixFileAttributeView::class.java) ?: return
            Files.setPosixFilePermissions(to, view.readAttributes().permissions())
        }
    }

    /** Install [staging] at [target], atomically where the filesystem allows it. */
    private fun replace(staging: Path, target: Path) {
        try {
            Files.move(staging, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        } catch (e: FileSystemException) {
            // Not only AtomicMoveNotSupportedException: on Windows a target held open by
            // an indexer, a backup agent or a sync client surfaces as
            // AccessDeniedException. Both are FileSystemException, and both are worth one
            // non-atomic retry rather than failing a save the user asked for.
            System.err.println("[AtomicFileWrite] Atomic move refused for $target (${e.message}); retrying")
            Files.move(staging, target, StandardCopyOption.REPLACE_EXISTING)
        }
    }
}
