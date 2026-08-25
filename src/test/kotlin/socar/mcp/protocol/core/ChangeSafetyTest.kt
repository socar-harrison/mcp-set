package socar.mcp.protocol.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class HmtClassifierTest {
    @Test
    fun `classifies main churn as noise and protocol branch delta as target`() {
        val head = mapOf(
            "generated/OnlyNoise.kt" to "head-noise",
            "generated/OnlyTarget.kt" to "shared-target",
            "generated/Both.kt" to "head-both",
            "generated/Unchanged.kt" to "same",
        )
        val main = mapOf(
            "generated/OnlyNoise.kt" to "main-noise",
            "generated/OnlyTarget.kt" to "shared-target",
            "generated/Both.kt" to "main-both",
            "generated/Unchanged.kt" to "same",
        )
        val target = mapOf(
            "generated/OnlyNoise.kt" to "main-noise",
            "generated/OnlyTarget.kt" to "target-only",
            "generated/Both.kt" to "target-both",
            "generated/Unchanged.kt" to "same",
        )

        val classified = HmtClassifier.classify(head, main, target)

        assertEquals(
            listOf(
                FileChange("generated/Both.kt", FileChangeKind.MODIFIED),
                FileChange("generated/OnlyNoise.kt", FileChangeKind.MODIFIED),
            ),
            classified.noise,
        )
        assertEquals(
            listOf(
                FileChange("generated/Both.kt", FileChangeKind.MODIFIED),
                FileChange("generated/OnlyTarget.kt", FileChangeKind.MODIFIED),
            ),
            classified.target,
        )
    }

    @Test
    fun `classifies additions and deletions relative to each comparison base`() {
        val classified = HmtClassifier.classify(
            head = mapOf(
                "noise-deleted.kt" to "h1",
                "noise-modified.kt" to "h2",
            ),
            main = mapOf(
                "noise-added.kt" to "m1",
                "noise-modified.kt" to "m2",
                "target-deleted.kt" to "m3",
                "target-modified.kt" to "m4",
            ),
            target = mapOf(
                "noise-added.kt" to "m1",
                "noise-modified.kt" to "m2",
                "target-added.kt" to "t1",
                "target-modified.kt" to "t2",
            ),
        )

        assertEquals(
            listOf(
                FileChange("noise-added.kt", FileChangeKind.ADDED),
                FileChange("noise-deleted.kt", FileChangeKind.DELETED),
                FileChange("noise-modified.kt", FileChangeKind.MODIFIED),
                FileChange("target-deleted.kt", FileChangeKind.ADDED),
                FileChange("target-modified.kt", FileChangeKind.ADDED),
            ),
            classified.noise,
        )
        assertEquals(
            listOf(
                FileChange("target-added.kt", FileChangeKind.ADDED),
                FileChange("target-deleted.kt", FileChangeKind.DELETED),
                FileChange("target-modified.kt", FileChangeKind.MODIFIED),
            ),
            classified.target,
        )
    }

    @Test
    fun `a target that restores head content remains a target delta from main`() {
        val classified = HmtClassifier.classify(
            head = mapOf("generated/Restored.kt" to "head"),
            main = mapOf("generated/Restored.kt" to "main"),
            target = mapOf("generated/Restored.kt" to "head"),
        )

        val expected = listOf(FileChange("generated/Restored.kt", FileChangeKind.MODIFIED))
        assertEquals(expected, classified.noise)
        assertEquals(expected, classified.target)
    }

    @Test
    fun `returns stable path ordering independent of map iteration order`() {
        val classified = HmtClassifier.classify(
            head = linkedMapOf("z.kt" to "old", "a.kt" to "old"),
            main = linkedMapOf("z.kt" to "new", "a.kt" to "new"),
            target = linkedMapOf("z.kt" to "target", "a.kt" to "target"),
        )

        assertEquals(listOf("a.kt", "z.kt"), classified.noise.map(FileChange::path))
        assertEquals(listOf("a.kt", "z.kt"), classified.target.map(FileChange::path))
    }
}

class CollisionDetectorTest {
    @Test
    fun `reports candidate paths changed since the baseline including untracked and deleted files`() {
        val baseline = mapOf(
            "generated/modified.kt" to "old",
            "generated/deleted.kt" to "old",
            "generated/unchanged.kt" to "same",
            "outside/user.kt" to "old",
        )
        val current = mapOf(
            "generated/modified.kt" to "new",
            "generated/added.kt" to "new",
            "generated/unchanged.kt" to "same",
            "outside/user.kt" to "new",
        )

        val collisions = CollisionDetector.findChangedPaths(
            baseline = baseline,
            current = current,
            candidatePaths = setOf(
                "generated/unchanged.kt",
                "generated/deleted.kt",
                "generated/modified.kt",
                "generated/added.kt",
            ),
        )

        assertEquals(
            listOf(
                "generated/added.kt",
                "generated/deleted.kt",
                "generated/modified.kt",
            ),
            collisions,
        )
        assertTrue("outside/user.kt" !in collisions)
    }

    @Test
    fun `does not report a path absent from both snapshots`() {
        assertEquals(
            emptyList(),
            CollisionDetector.findChangedPaths(
                baseline = emptyMap(),
                current = emptyMap(),
                candidatePaths = setOf("generated/never-existed.kt"),
            ),
        )
    }

    @Test
    fun `keeps whitespace and newlines in paths as exact snapshot keys`() {
        val unusualPath = "generated/with space and\nnewline.kt"

        assertEquals(
            listOf(unusualPath),
            CollisionDetector.findChangedPaths(
                baseline = mapOf(unusualPath to "old"),
                current = mapOf(unusualPath to "new"),
                candidatePaths = setOf(unusualPath),
            ),
        )
    }
}

class NullDelimitedGitOutputTest {
    @Test
    fun `splits git z output without treating spaces or newlines as separators`() {
        assertEquals(
            listOf(
                "generated/Ordinary.kt",
                "generated/with space.kt",
                "generated/with\nnewline.kt",
            ),
            GitClient.splitNullDelimited(
                "generated/Ordinary.kt\u0000" +
                    "generated/with space.kt\u0000" +
                    "generated/with\nnewline.kt\u0000",
            ),
        )
    }
}
