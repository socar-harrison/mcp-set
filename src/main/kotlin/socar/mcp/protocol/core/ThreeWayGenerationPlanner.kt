package socar.mcp.protocol.core

import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration

internal data class PlannedGeneration(
    val snapshot: DirectorySnapshot?,
    val changes: List<FileChange>,
    val conflicts: List<String>,
)

/**
 * Builds P = H + (T - M). Copying a complete T file onto H is unsafe when that file also
 * contains M - H churn, so overlapping regular text files are merged with M as the base.
 * Ambiguous lifecycle changes and non-trivial binary changes fail closed.
 */
internal class ThreeWayGenerationPlanner(
    private val commandRunner: CommandRunner,
) {
    fun plan(
        head: DirectorySnapshot,
        main: DirectorySnapshot,
        target: DirectorySnapshot,
        classification: ChangeClassification,
        temporaryRoot: Path,
    ): PlannedGeneration {
        val plannedEntries = head.entries.toMutableMap()
        val noisePaths = classification.noise.mapTo(hashSetOf()) { it.path }
        val conflicts = mutableListOf<String>()
        val mergeRoot = temporaryRoot.resolve("three-way-merges")
        Files.createDirectories(mergeRoot)

        classification.target.forEach { change ->
            if (change.path !in noisePaths) {
                putOrRemove(plannedEntries, change.path, target.entries[change.path])
                return@forEach
            }
            val merged = mergeOverlap(
                change.path,
                head.entries[change.path],
                main.entries[change.path],
                target.entries[change.path],
                mergeRoot,
            )
            if (merged == null) {
                conflicts += change.path
            } else {
                putOrRemove(plannedEntries, change.path, merged)
            }
        }

        if (conflicts.isNotEmpty()) {
            return PlannedGeneration(null, emptyList(), conflicts.sorted())
        }
        val snapshot = DirectorySnapshot(plannedEntries.toSortedMap())
        val changes = HmtClassifier.diff(head.fingerprints(), snapshot.fingerprints())
        return PlannedGeneration(snapshot, changes, emptyList())
    }

    private fun mergeOverlap(
        relativePath: String,
        head: SnapshotEntry?,
        main: SnapshotEntry?,
        target: SnapshotEntry?,
        mergeRoot: Path,
    ): SnapshotEntry? {
        if (head == null || main == null || target == null) return null
        val executable = mergeExecutable(head.executable, main.executable, target.executable)
            ?: return null
        val bytes = when {
            head.bytes.contentEquals(main.bytes) -> target.bytes
            target.bytes.contentEquals(main.bytes) -> head.bytes
            head.bytes.contentEquals(target.bytes) -> head.bytes
            !isMergeableText(head.bytes) || !isMergeableText(main.bytes) || !isMergeableText(target.bytes) -> return null
            else -> mergeText(relativePath, head.bytes, main.bytes, target.bytes, mergeRoot) ?: return null
        }
        return SnapshotEntry(bytes.copyOf(), executable, fingerprint(bytes, executable))
    }

    private fun mergeText(
        relativePath: String,
        head: ByteArray,
        main: ByteArray,
        target: ByteArray,
        mergeRoot: Path,
    ): ByteArray? {
        val fileRoot = mergeRoot.resolve(sha256(relativePath.toByteArray(Charsets.UTF_8)))
        Files.createDirectories(fileRoot)
        val currentFile = fileRoot.resolve("head")
        val baseFile = fileRoot.resolve("main")
        val otherFile = fileRoot.resolve("target")
        Files.write(currentFile, head)
        Files.write(baseFile, main)
        Files.write(otherFile, target)

        val result = commandRunner.run(
            CommandSpec(
                argv = listOf(
                    "git",
                    "merge-file",
                    currentFile.toString(),
                    baseFile.toString(),
                    otherFile.toString(),
                ),
                directory = fileRoot,
                timeout = MERGE_TIMEOUT,
                // git merge-file returns the conflict count (capped at 127); 255 is an error.
                acceptedExitCodes = (0..127).toSet(),
            ),
        )
        return if (result.exitCode == 0) Files.readAllBytes(currentFile) else null
    }

    private fun mergeExecutable(head: Boolean, main: Boolean, target: Boolean): Boolean? = when {
        head == main -> target
        target == main -> head
        head == target -> head
        else -> null
    }

    private fun isMergeableText(bytes: ByteArray): Boolean {
        if (bytes.any { it == 0.toByte() }) return false
        return try {
            StandardCharsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(bytes))
            true
        } catch (_: java.nio.charset.CharacterCodingException) {
            false
        }
    }

    private fun putOrRemove(
        entries: MutableMap<String, SnapshotEntry>,
        path: String,
        entry: SnapshotEntry?,
    ) {
        if (entry == null) entries.remove(path) else entries[path] = entry
    }

    companion object {
        private val MERGE_TIMEOUT: Duration = Duration.ofSeconds(30)
    }
}
