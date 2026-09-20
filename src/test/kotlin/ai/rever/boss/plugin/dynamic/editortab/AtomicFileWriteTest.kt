package ai.rever.boss.plugin.dynamic.editortab

import java.io.File
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.nio.file.attribute.PosixFilePermissions
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
        if (!posix) return // Windows: no POSIX mode to take away.
        if (System.getProperty("user.name") == "root") return // root ignores the mode.
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
        if (!posix) return

        AtomicFileWrite.writeText(target, "second")

        assertEquals(
            PosixFilePermissions.fromString("rw-r--r--"),
            Files.getPosixFilePermissions(target.toPath()),
        )
    }

    @Test
    fun `a symlink is written through, not replaced`() {
        // Dotfiles and shared config are routinely symlinked into a project. Moving a
        // staging file over the link would detach it from whatever it pointed at, and
        // the next save would write somewhere the rest of the system is not reading.
        val real = file("real.txt").also { it.writeText("first") }
        val link = File(dir, "link.txt")
        val linked = runCatching { Files.createSymbolicLink(link.toPath(), real.toPath()) }.isSuccess
        if (!linked) return // Windows without developer mode.

        AtomicFileWrite.writeText(link, "second")

        assertTrue(Files.isSymbolicLink(link.toPath()), "the symlink was replaced by a regular file")
        assertEquals("second", real.readText())
        assertEquals("second", link.readText())
    }

    @Test
    fun `the destination is replaced, not truncated in place`() {
        // Stated as something exact rather than as a race to lose: an in-place write
        // keeps the inode, so a reader can observe the file mid-write. An atomic move
        // installs a new one, which is why no reader ever sees a partial document.
        val target = file("doc.txt")
        target.writeText("first")
        val before = runCatching {
            Files.readAttributes(target.toPath(), java.nio.file.attribute.BasicFileAttributes::class.java).fileKey()
        }.getOrNull() ?: return // Windows has no file key.

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

        val slowSave = Thread {
            AtomicFileWrite.writeText(target, slow) { staging, content ->
                FileChannel.open(staging, StandardOpenOption.WRITE).use { channel ->
                    channel.write(ByteBuffer.wrap(content.toByteArray(Charsets.UTF_8)))
                }
                // Staged but not yet installed: the whole window this is about.
                staged.countDown()
                otherDone.await()
            }
        }
        slowSave.start()
        staged.await()

        AtomicFileWrite.writeText(target, quick)
        otherDone.countDown()
        slowSave.join()

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

        val threads = contents.map { content ->
            Thread { AtomicFileWrite.writeText(target, content) }.also { it.start() }
        }
        threads.forEach { it.join() }

        assertTrue(target.readText() in contents, "a save landed with mixed content")
        assertTrue(leftovers().isEmpty(), "left staging files behind: ${leftovers().map { it.name }}")
    }
}
