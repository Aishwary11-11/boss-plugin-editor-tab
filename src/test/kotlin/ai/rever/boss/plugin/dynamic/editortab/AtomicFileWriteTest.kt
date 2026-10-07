package ai.rever.boss.plugin.dynamic.editortab

import java.io.File
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.FileSystemException
import java.nio.file.AccessDeniedException
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.ConcurrentHashMap
import org.junit.Assume
import java.nio.file.StandardOpenOption
import java.nio.file.attribute.PosixFilePermissions
import java.nio.file.attribute.PosixFileAttributeView
import java.nio.file.attribute.AclFileAttributeView
import java.nio.file.attribute.AclEntry
import java.nio.file.attribute.AclEntryType
import java.nio.file.attribute.AclEntryPermission
import java.nio.file.attribute.DosFileAttributeView
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * A save must not be able to destroy the document it is saving (#31).
 *
 * `File.writeText` truncates the destination before writing, so every failure after that
 * point - a full disk, a dropped network share, a kill mid-save - left the user with an
 * empty or half-written file and no previous version. Auto save made that window
 * permanent rather than occasional: it fires on a timer nobody is watching.
 *
 * The cases below are the acceptance checks from the issue. The interesting one is the
 * partial write, which cannot be provoked from outside the process, so [AtomicFileWrite]
 * takes the staging step as a parameter and these tests fail it on purpose.
 */
class AtomicFileWriteTest {

    private lateinit var dir: File

    @BeforeTest
    fun setUp() {
        dir = Files.createTempDirectory("atomic-file-write-test").toFile()
    }

    @AfterTest
    fun tearDown() {
        dir.deleteRecursively()
    }

    private fun file(name: String) = File(dir, name)

    private fun leftovers() = dir.listFiles()?.filter { it.name.contains(".boss-save") }.orEmpty()

    private fun <T : Any> requireCapability(value: T?, description: String): T {
        Assume.assumeTrue(description, value != null)
        return value!!
    }

    private fun createSymlink(link: Path, target: Path) {
        try {
            Files.createSymbolicLink(link, target)
        } catch (failure: UnsupportedOperationException) {
            Assume.assumeNoException("Symlinks are unsupported by this filesystem", failure)
        } catch (failure: FileSystemException) {
            if (System.getProperty("os.name").startsWith("Windows") &&
                failure.reason.orEmpty().contains("privilege", ignoreCase = true)) {
                Assume.assumeNoException("This Windows account lacks symlink privileges", failure)
            } else {
                throw failure
            }
        }
    }

    /** Writes [half] of the content and then fails, as a full disk would. */
    private fun failAfterPartialWrite(half: Int): (Path, String) -> Unit = { staging, content ->
        FileChannel.open(staging, StandardOpenOption.WRITE).use { channel ->
            val bytes = content.toByteArray(Charsets.UTF_8).copyOfRange(0, half)
            channel.write(ByteBuffer.wrap(bytes))
        }
        throw IOException("no space left on device")
    }

    // ------------------------------------------------------- ordinary saves

    @Test
    fun `writes a new file`() {
        val target = file("new.txt")

        AtomicFileWrite.writeText(target, "hello")

        assertEquals("hello", target.readText())
        assertTrue(leftovers().isEmpty(), "left staging files behind: ${leftovers().map { it.name }}")
    }

    @Test
    fun `replaces an existing file`() {
        val target = file("doc.txt")
        target.writeText("first")

        AtomicFileWrite.writeText(target, "second")

        assertEquals("second", target.readText())
        assertTrue(leftovers().isEmpty())
    }

    @Test
    fun `a shorter replacement leaves none of the old content behind`() {
        // The case an in-place write gets wrong without an explicit truncate: the new
        // bytes are written over the start of the file and the tail of the old document
        // survives past them. Staging cannot do that, and this says so.
        val target = file("shrink.txt")
        target.writeText("a very long first version of this document")

        AtomicFileWrite.writeText(target, "short")

        assertEquals("short", target.readText())
    }

    @Test
    fun `multi-byte characters round-trip as UTF-8`() {
        // Character count and byte count differ here, which is where a length-based
        // write goes wrong.
        val target = file("unicode.txt")
        val content = "héllo — 日本語 — 😀"

        AtomicFileWrite.writeText(target, content)

        assertEquals(content, target.readText())
        assertEquals(content.toByteArray(Charsets.UTF_8).size.toLong(), target.length())
    }

    @Test
    fun `an empty document is a legitimate save`() {
        val target = file("empty.txt")
        target.writeText("was not empty")

        AtomicFileWrite.writeText(target, "")

        assertEquals("", target.readText())
    }

    // ------------------------------------------------------------- failures

    @Test
    fun `a partial write leaves the original bytes untouched`() {
        val target = file("doc.txt")
        target.writeText("the original document")

        assertFailsWith<IOException> {
            AtomicFileWrite.writeText(target, "a replacement that never lands", failAfterPartialWrite(half = 5))
        }

        assertEquals("the original document", target.readText())
        assertTrue(leftovers().isEmpty(), "a failed save left staging output: ${leftovers().map { it.name }}")
    }

    @Test
    fun `a failed first save leaves no file at all`() {
        // Nothing to preserve here, so the claim is the other one: a failure must not
        // leave a partial document where the user will later find it and trust it.
        val target = file("never-written.txt")

        assertFailsWith<IOException> {
            AtomicFileWrite.writeText(target, "content that fails", failAfterPartialWrite(half = 3))
        }

        assertTrue(!target.exists(), "a failed first save created ${target.name}")
        assertTrue(leftovers().isEmpty())
    }

    @Test
    fun `a failure before any bytes are written still cleans up`() {
        val target = file("doc.txt")
        target.writeText("intact")

        assertFailsWith<IOException> {
            AtomicFileWrite.writeText(target, "replacement") { _, _ -> throw IOException("disk went away") }
        }

        assertEquals("intact", target.readText())
        assertTrue(leftovers().isEmpty())
    }

    @Test
    fun `an unwritable directory fails the save rather than the file`() {
        val posix = runCatching { Files.getPosixFilePermissions(dir.toPath()) }.isSuccess
        Assume.assumeTrue("POSIX permissions are unsupported", posix)
        Assume.assumeTrue("Root bypasses POSIX write permissions", System.getProperty("user.name") != "root")
        val target = file("doc.txt")
        target.writeText("intact")
        Files.setPosixFilePermissions(dir.toPath(), PosixFilePermissions.fromString("r-xr-xr-x"))

        try {
            assertFailsWith<IOException> { AtomicFileWrite.writeText(target, "replacement") }
            assertEquals("intact", target.readText())
        } finally {
            Files.setPosixFilePermissions(dir.toPath(), PosixFilePermissions.fromString("rwxr-xr-x"))
        }
    }

    // ------------------------------------------------- what the file keeps

    @Test
    fun `the destination keeps its permissions`() {
        // Staging files are created 0600. Without carrying the destination's mode over,
        // saving a group-readable file would quietly make it private - a config file
        // that stops working for everyone else after an edit.
        val target = file("mode.txt")
        target.writeText("first")
        val posix = runCatching {
            Files.setPosixFilePermissions(target.toPath(), PosixFilePermissions.fromString("rw-r--r--"))
        }.isSuccess
        Assume.assumeTrue("POSIX permissions are unsupported", posix)

        AtomicFileWrite.writeText(target, "second")

        assertEquals(
            PosixFilePermissions.fromString("rw-r--r--"),
            Files.getPosixFilePermissions(target.toPath()),
        )
    }

    @Test
    fun `the destination keeps its POSIX owner and group`() {
        val target = file("ownership.txt").also { it.writeText("first") }
        val view = requireCapability(Files.getFileAttributeView(target.toPath(), PosixFileAttributeView::class.java), "POSIX metadata is unsupported")
        val original = view.readAttributes()

        AtomicFileWrite.writeText(target, "second") { staging, content ->
            val staged = Files.readAttributes(staging, java.nio.file.attribute.PosixFileAttributes::class.java)
            assertEquals(original.owner(), staged.owner())
            assertEquals(original.group(), staged.group())
            assertEquals(original.permissions(), staged.permissions(), "access mode must precede content writing")
            Files.writeString(staging, content)
        }

        val replaced = view.readAttributes()
        assertEquals(original.owner(), replaced.owner())
        assertEquals(original.group(), replaced.group())
        assertEquals(original.permissions(), replaced.permissions())
    }

    @Test
    fun `the destination keeps its supported ACL and owner`() {
        val target = file("acl.txt").also { it.writeText("first") }
        val view = requireCapability(Files.getFileAttributeView(target.toPath(), AclFileAttributeView::class.java), "ACL metadata is unsupported")
        val originalOwner = view.owner
        // An explicit non-inherited grant distinguishes this file from a new sibling's
        // inherited ACL, so merely keeping the staging defaults cannot pass the test.
        val explicitGrant = AclEntry.newBuilder()
            .setType(AclEntryType.ALLOW)
            .setPrincipal(originalOwner)
            .setPermissions(AclEntryPermission.READ_DATA, AclEntryPermission.WRITE_DATA,
                AclEntryPermission.APPEND_DATA, AclEntryPermission.READ_ATTRIBUTES,
                AclEntryPermission.WRITE_ATTRIBUTES, AclEntryPermission.READ_ACL,
                AclEntryPermission.WRITE_ACL, AclEntryPermission.SYNCHRONIZE,
                AclEntryPermission.DELETE)
            .build()
        view.acl = listOf(explicitGrant) + view.acl
        val originalAcl = view.acl.toList()

        AtomicFileWrite.writeText(target, "second") { staging, content ->
            val stageView = Files.getFileAttributeView(staging, AclFileAttributeView::class.java)
            assertEquals(originalAcl, stageView.acl, "access policy must precede content writing")
            assertEquals(originalOwner, stageView.owner)
            Files.writeString(staging, content)
        }

        assertEquals(originalAcl, view.acl)
        assertEquals(originalOwner, view.owner)
    }

    @Test
    fun `an edit marks the DOS archive flag and preserves visibility flags`() {
        val target = file("flags.txt").also { it.writeText("first") }
        val view = requireCapability(Files.getFileAttributeView(target.toPath(), DosFileAttributeView::class.java), "DOS metadata is unsupported")
        view.setHidden(true)
        view.setSystem(true)
        view.setArchive(false)
        val original = view.readAttributes()

        AtomicFileWrite.writeText(target, "second")

        val replaced = view.readAttributes()
        assertEquals(original.isHidden, replaced.isHidden)
        assertEquals(original.isSystem, replaced.isSystem)
        assertTrue(replaced.isArchive, "edited files must be included in incremental backups")
        assertEquals(original.isReadOnly, replaced.isReadOnly)
        assertEquals("second", target.readText())
    }

    @Test
    fun `a symlink is written through, not replaced`() {
        // Dotfiles and shared config are routinely symlinked into a project. Moving a
        // staging file over the link would detach it from whatever it pointed at, and
        // the next save would write somewhere the rest of the system is not reading.
        val real = file("real.txt").also { it.writeText("first") }
        val link = File(dir, "link.txt")
        createSymlink(link.toPath(), real.toPath())

        AtomicFileWrite.writeText(link, "second")

        assertTrue(Files.isSymbolicLink(link.toPath()), "the symlink was replaced by a regular file")
        assertEquals("second", real.readText())
        assertEquals("second", link.readText())
    }

    @Test
    fun `a read-only destination is not bypassed by replacing its directory entry`() {
        Assume.assumeTrue("Root bypasses POSIX write permissions", System.getProperty("user.name") != "root")
        val target = file("read-only.txt").also { it.writeText("original") }
        requireCapability(Files.getFileAttributeView(target.toPath(), PosixFileAttributeView::class.java), "POSIX permissions are unsupported")
        Files.setPosixFilePermissions(target.toPath(), PosixFilePermissions.fromString("r--r--r--"))
        try {
            assertFailsWith<IOException> { AtomicFileWrite.writeText(target, "replacement") }
            assertEquals("original", target.readText())
            assertTrue(leftovers().isEmpty())
        } finally {
            Files.setPosixFilePermissions(target.toPath(), PosixFilePermissions.fromString("rw-------"))
        }
    }

    @Test
    fun `a new file follows the same umask as an ordinary created file`() {
        val expected = file("reference.txt")
        Assume.assumeTrue("POSIX permissions are unsupported",
            Files.getFileStore(dir.toPath()).supportsFileAttributeView(PosixFileAttributeView::class.java))
        Files.createFile(expected.toPath(),
            PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-rw-rw-")))
        val target = file("new-mode.txt")

        AtomicFileWrite.writeText(target, "new document")

        assertEquals(Files.getPosixFilePermissions(expected.toPath()), Files.getPosixFilePermissions(target.toPath()))
    }

    @Test
    fun `a directory destination is rejected without staging or removing it`() {
        val target = file("directory").also { it.mkdir() }
        val child = File(target, "keep.txt").also { it.writeText("intact") }

        assertFailsWith<IOException> { AtomicFileWrite.writeText(target, "replacement") }

        assertEquals("intact", child.readText())
        assertTrue(leftovers().isEmpty())
    }

    @Test
    fun `a dangling relative symlink creates its target without replacing the link`() {
        val real = file("not-yet-created.txt")
        val link = file("link.txt")
        createSymlink(link.toPath(), Path.of(real.name))

        AtomicFileWrite.writeText(link, "new document")

        assertTrue(Files.isSymbolicLink(link.toPath()))
        assertEquals("new document", real.readText())
        assertTrue(leftovers().isEmpty())
    }

    @Test
    fun `a symlink cycle fails without destroying either link`() {
        val first = file("first-link")
        val second = file("second-link")
        createSymlink(first.toPath(), Path.of(second.name))
        Files.createSymbolicLink(second.toPath(), Path.of(first.name))

        assertFailsWith<IOException> { AtomicFileWrite.writeText(first, "replacement") }

        assertTrue(Files.isSymbolicLink(first.toPath()))
        assertTrue(Files.isSymbolicLink(second.toPath()))
        assertTrue(leftovers().isEmpty())
    }

    @Test
    fun `a long valid filename does not overflow the staging filename`() {
        val target = file("a".repeat(240) + ".txt")
        target.writeText("original")

        AtomicFileWrite.writeText(target, "replacement")

        assertEquals("replacement", target.readText())
        assertTrue(leftovers().isEmpty())
    }

    @Test
    fun `the destination is replaced, not truncated in place`() {
        // Stated as something exact rather than as a race to lose: an in-place write
        // keeps the inode, so a reader can observe the file mid-write. An atomic move
        // installs a new one, which is why no reader ever sees a partial document.
        val target = file("doc.txt")
        target.writeText("first")
        val before = requireCapability(
            Files.readAttributes(target.toPath(), java.nio.file.attribute.BasicFileAttributes::class.java).fileKey(),
            "Filesystem identity keys are unsupported")

        AtomicFileWrite.writeText(target, "second")

        val after = Files.readAttributes(
            target.toPath(),
            java.nio.file.attribute.BasicFileAttributes::class.java,
        ).fileKey()
        assertTrue(before != after, "the file kept its identity, so it was written in place")
    }

    @Test
    fun `a save in flight cannot have its staging written by another save`() {
        // Auto save and Cmd+S overlap routinely. With a fixed "<name>.boss-save" the two
        // would write into one file and install the mixture - a corrupt document produced
        // by the mechanism that exists to prevent one. Sequenced with latches rather than
        // raced, so it states the property instead of hunting for it.
        val target = file("busy.txt")
        target.writeText("first")
        val staged = java.util.concurrent.CountDownLatch(1)
        val otherDone = java.util.concurrent.CountDownLatch(1)
        val slow = "A".repeat(4_000)
        val quick = "B".repeat(10)

        val failure = AtomicReference<Throwable?>()
        val slowSave = Thread {
            try {
                AtomicFileWrite.writeText(target, slow) { staging, content ->
                    FileChannel.open(staging, StandardOpenOption.WRITE).use { channel ->
                        channel.write(ByteBuffer.wrap(content.toByteArray(Charsets.UTF_8)))
                    }
                    // Staged but not yet installed: the whole window this is about.
                    staged.countDown()
                    assertTrue(otherDone.await(5, TimeUnit.SECONDS), "the other save did not finish")
                }
            } catch (error: Throwable) {
                failure.set(error)
            }
        }
        slowSave.start()
        try {
            assertTrue(staged.await(5, TimeUnit.SECONDS), "the first save never staged")
            AtomicFileWrite.writeText(target, quick)
        } finally {
            otherDone.countDown()
        }
        slowSave.join(5_000)
        assertTrue(!slowSave.isAlive, "the save thread did not terminate")
        failure.get()?.let { throw AssertionError("the overlapping save failed", it) }

        // The slow save moves last, so it wins outright. What must not happen is its
        // staging carrying the other save's bytes, or having been consumed by it.
        assertEquals(slow, target.readText())
        assertTrue(leftovers().isEmpty(), "left staging files behind: ${leftovers().map { it.name }}")
    }

    @Test
    fun `overlapping saves do not share staging`() {
        // Auto save and Cmd+S can overlap. With a fixed "<name>.tmp" the two would
        // interleave their bytes into one staging file and then install the result.
        val target = file("busy.txt")
        target.writeText("first")
        val contents = (1..24).map { "version $it".repeat(200) }

        val failures = ConcurrentLinkedQueue<Throwable>()
        val committed = ConcurrentHashMap.newKeySet<String>()
        val threads = contents.map { content ->
            Thread {
                try {
                    AtomicFileWrite.writeText(target, content)
                    committed.add(content)
                } catch (error: Throwable) {
                    failures.add(error)
                }
            }.also { it.start() }
        }
        threads.forEach {
            it.join(5_000)
            assertTrue(!it.isAlive, "a save thread did not terminate")
        }
        // Windows can deny an atomic rename while another writer briefly has the
        // destination open. A fail-closed refusal is legitimate at this low level;
        // real document saves serialize per buffer and must pass their strict tests.
        for (failure in failures) {
            if (!System.getProperty("os.name").startsWith("Windows") || failure !is AccessDeniedException) {
                throw AssertionError("an overlapping save failed unexpectedly", failure)
            }
        }
        assertTrue(committed.isNotEmpty(), "no concurrent save committed successfully")
        assertTrue(target.readText() in committed, "a save landed with partial or uncommitted content")
        assertTrue(leftovers().isEmpty(), "left staging files behind: ${leftovers().map { it.name }}")
    }
}
