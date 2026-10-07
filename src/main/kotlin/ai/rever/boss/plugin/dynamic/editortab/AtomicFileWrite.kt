package ai.rever.boss.plugin.dynamic.editortab

import java.io.File
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.FileSystemLoopException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.nio.file.attribute.PosixFileAttributeView
import java.nio.file.attribute.PosixFilePermissions
import java.nio.file.attribute.PosixFileAttributes
import java.nio.file.attribute.AclFileAttributeView
import java.nio.file.attribute.AclEntry
import java.nio.file.attribute.FileOwnerAttributeView
import java.nio.file.attribute.UserPrincipal
import java.nio.file.attribute.DosFileAttributeView
import java.nio.file.attribute.DosFileAttributes

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
 * The plugin writes locally because published hosts still use a truncating provider
 * implementation. This protection therefore does not depend on a host update.
 *
 * **What atomicity does and does not promise.** The rename is atomic on POSIX and on
 * NTFS, so a reader sees either the old file or the new one, never a partial document.
 * It replaces the *directory entry*, so the destination gets a new inode: hard links to
 * the old file keep the old contents, and anything holding the path open by inode is
 * looking at the previous version. A symlink destination is resolved first and written
 * through, so the link survives instead of being replaced by a regular file. Where
 * the filesystem does not support `ATOMIC_MOVE`, saving fails with the original file
 * intact instead of silently accepting a destructive non-atomic fallback.
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
        val target = resolveLink(file.toPath().toAbsolutePath())
        val directory = target.parent
            ?: throw IOException("Cannot save ${file.path}: it has no parent directory")

        // A unique staging file per call, never a fixed "<name>.tmp". Auto save and a
        // Cmd+S can overlap, and two writers sharing one staging path would interleave
        // their bytes and then move the result into place - a corrupt document produced
        // by the mechanism meant to prevent one.
        if (Files.exists(target) && (!Files.isRegularFile(target) || !Files.isWritable(target))) {
            throw IOException("Cannot save $target: the destination is not a writable regular file")
        }
        val metadata = captureMetadata(target)
        // Match a normal new file's umask-filtered permissions. Existing documents keep
        // private staging until their original mode is copied before commit.
        val attributes = if (!Files.exists(target) &&
            Files.getFileStore(directory).supportsFileAttributeView(PosixFileAttributeView::class.java)) {
            arrayOf(PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-rw-rw-")))
        } else {
            emptyArray()
        }
        val staging = Files.createTempFile(directory, "boss-", STAGING_SUFFIX, *attributes)
        try {
            // Access policy must be present before edited bytes reach staging, especially
            // where a Windows directory grants broader inherited access than the file.
            metadata?.applyAccessTo(staging)
            stage(staging, content)
            metadata?.applyDosTo(staging)
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

    /** Resolve final-component links even when their target does not exist yet. */
    internal fun resolveLink(path: Path): Path {
        var target = path
        val seen = mutableSetOf<Path>()
        while (Files.isSymbolicLink(target)) {
            // Resolve existing parents as well, so aliases cannot hide a link cycle.
            target = target.parent.toRealPath().resolve(target.fileName)
            if (!seen.add(target)) throw FileSystemLoopException(path.toString())
            val link = Files.readSymbolicLink(target)
            target = if (link.isAbsolute) link else target.parent.resolve(link)
        }
        return target
    }

    private data class Metadata(
        val posix: PosixFileAttributes?,
        val owner: UserPrincipal?,
        val acl: List<AclEntry>?,
        val dos: DosFileAttributes?,
    ) {
        fun applyAccessTo(path: Path) {
            if (owner != null) {
                val view = Files.getFileAttributeView(path, FileOwnerAttributeView::class.java)
                    ?: throw IOException("Cannot preserve file ownership")
                if (view.owner != owner) view.owner = owner
            }
            if (posix != null) {
                val view = Files.getFileAttributeView(path, PosixFileAttributeView::class.java)
                    ?: throw IOException("Cannot preserve POSIX permissions")
                if (view.readAttributes().group() != posix.group()) view.setGroup(posix.group())
                view.setPermissions(posix.permissions())
            }
            if (acl != null) {
                val view = Files.getFileAttributeView(path, AclFileAttributeView::class.java)
                    ?: throw IOException("Cannot preserve file ACL")
                view.acl = acl
            }
        }

        fun applyDosTo(path: Path) {
            if (dos != null) {
                val view = Files.getFileAttributeView(path, DosFileAttributeView::class.java)
                    ?: throw IOException("Cannot preserve DOS file attributes")
                view.setHidden(dos.isHidden)
                view.setSystem(dos.isSystem)
                // Edited contents must be included in the next incremental backup.
                // Apply before commit; Windows also sets this bit during rename.
                view.setArchive(true)
                view.setReadOnly(dos.isReadOnly)
            }
        }
    }

    /** Capture supported views before staging; supported read/write failures fail closed. */
    private fun captureMetadata(path: Path): Metadata? {
        if (!Files.exists(path)) return null
        return Metadata(
            posix = Files.getFileAttributeView(path, PosixFileAttributeView::class.java)?.readAttributes(),
            owner = Files.getFileAttributeView(path, FileOwnerAttributeView::class.java)?.owner,
            acl = Files.getFileAttributeView(path, AclFileAttributeView::class.java)?.acl?.toList(),
            dos = Files.getFileAttributeView(path, DosFileAttributeView::class.java)?.readAttributes(),
        )
    }

    /** Install [staging] at [target], atomically where the filesystem allows it. */
    private fun replace(staging: Path, target: Path) {
        try {
            Files.move(staging, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        } catch (e: AtomicMoveNotSupportedException) {
            throw IOException("Cannot safely save $target: this filesystem does not support atomic replacement. " +
                "Save a copy on a local filesystem instead.", e)
        }
    }
}
