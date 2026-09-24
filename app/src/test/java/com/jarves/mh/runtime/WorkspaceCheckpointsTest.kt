package com.jarves.mh.runtime

import com.jarves.mh.model.DiffLineType
import java.io.File
import kotlin.random.Random
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * ISSUE-034: diffing must never load whole large/binary files into the heap.
 * A 50 MB binary produced by the agent itself has to yield an info card, not
 * an OutOfMemoryError, and small text diffs must keep working.
 */
class WorkspaceCheckpointsTest {

    @get:Rule
    val temp = TemporaryFolder()

    private fun newStore(): WorkspaceCheckpoints = WorkspaceCheckpoints(temp.root)

    @Test
    fun `a huge binary file becomes an info card instead of a heap blowup`() {
        val store = newStore()
        val workspace = store.ensureWorkspace("p1")
        store.createCheckpoint("p1", workspace)

        // 50 MB of pseudo-random binary content (guaranteed zero bytes).
        val big = File(workspace, "assets/blob.bin")
        Random(42).let { random ->
            big.outputStream().use { output ->
                val chunk = ByteArray(1024 * 1024)
                repeat(50) {
                    random.nextBytes(chunk)
                    chunk[0] = 0 // force binary detection in the first sample
                    output.write(chunk)
                }
            }
        }

        val details = store.buildChangeDetails("p1", workspace, listOf("assets/blob.bin"))
        assertEquals(1, details.size)
        val change = details.first()
        assertTrue("the change must be flagged binary", change.binary)
        assertEquals(0, change.additions)
        assertEquals(0, change.deletions)
        assertEquals(DiffLineType.INFO, change.diffLines.single().type)
    }

    @Test
    fun `an oversized text file is summarized without a full diff`() {
        val store = newStore()
        val workspace = store.ensureWorkspace("p2")
        store.createCheckpoint("p2", workspace)

        val large = File(workspace, "logs/generated.log")
        large.printWriter().use { writer ->
            repeat(400_000) { writer.println("line $it with some text to cross the size cap") }
        }
        assertTrue(large.length() > 5L * 1024 * 1024)

        val details = store.buildChangeDetails("p2", workspace, listOf("logs/generated.log"))
        val change = details.single()
        assertFalse(change.binary)
        assertEquals(DiffLineType.INFO, change.diffLines.single().type)
        assertTrue(change.diffLines.single().text.contains("too large"))
    }

    @Test
    fun `small text files keep their line diff`() {
        val store = newStore()
        val workspace = store.ensureWorkspace("p3")
        File(workspace, "notes.md").writeText("first\n")
        store.createCheckpoint("p3", workspace)
        File(workspace, "notes.md").writeText("first\nsecond\n")

        val details = store.buildChangeDetails("p3", workspace, listOf("notes.md"))
        val change = details.single()
        assertFalse(change.binary)
        assertEquals(1, change.additions)
        assertEquals(0, change.deletions)
        assertTrue(change.diffLines.any { it.type == DiffLineType.ADDITION && it.text == "second" })
    }
}
