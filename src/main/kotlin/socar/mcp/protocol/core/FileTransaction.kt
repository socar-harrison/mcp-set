package socar.mcp.protocol.core

import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.StandardCopyOption

internal class GeneratedFileTransaction private constructor(
    private val root: Path,
    private val originals: Map<String, SnapshotEntry?>,
    private val appliedPaths: List<String>,
) {
    private var active = true

    fun commit() {
        active = false
    }

    fun rollback() {
        if (!active) return
        var failure: Throwable? = null
        appliedPaths.asReversed().forEach { relative ->
            try {
                writeEntry(resolveSafe(root, relative), originals.getValue(relative))
            } catch (error: Throwable) {
                val previousFailure = failure
                if (previousFailure == null) failure = error else previousFailure.addSuppressed(error)
            }
        }
        active = false
        if (failure != null) throw IllegalStateException("Could not roll back generated files", failure)
    }

    companion object {
        fun apply(
            root: Path,
            target: DirectorySnapshot,
            changes: List<FileChange>,
        ): GeneratedFileTransaction {
            require(Files.isDirectory(root, LinkOption.NOFOLLOW_LINKS)) { "Generated root is missing: $root" }
            require(!Files.isSymbolicLink(root)) { "Generated root must not be a symbolic link" }
            val uniqueChanges = changes.associateBy { it.path }.values.sortedBy { it.path }
            val originals = uniqueChanges.associate { change ->
                change.path to readEntryIfPresent(resolveSafe(root, change.path))
            }
            val applied = mutableListOf<String>()
            try {
                uniqueChanges.forEach { change ->
                    val desired = target.entries[change.path]
                    writeEntry(resolveSafe(root, change.path), desired)
                    applied += change.path
                }
            } catch (error: Throwable) {
                val transaction = GeneratedFileTransaction(root, originals, applied)
                try {
                    transaction.rollback()
                } catch (rollbackError: Throwable) {
                    error.addSuppressed(rollbackError)
                }
                throw error
            }
            return GeneratedFileTransaction(root, originals, applied)
        }

        private fun readEntryIfPresent(path: Path): SnapshotEntry? {
            if (!Files.exists(path, LinkOption.NOFOLLOW_LINKS)) return null
            require(Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS) && !Files.isSymbolicLink(path)) {
                "Generated target is not a regular file: $path"
            }
            val bytes = Files.readAllBytes(path)
            val executable = Files.isExecutable(path)
            return SnapshotEntry(bytes, executable, fingerprint(bytes, executable))
        }

        private fun writeEntry(path: Path, entry: SnapshotEntry?) {
            requireNoSymlinkParents(path.parent)
            if (entry == null) {
                Files.deleteIfExists(path)
                return
            }
            Files.createDirectories(path.parent)
            val temporary = Files.createTempFile(path.parent, ".protocol-codegen-", ".tmp")
            try {
                Files.write(temporary, entry.bytes)
                applyExecutable(temporary, entry.executable)
                try {
                    Files.move(
                        temporary,
                        path,
                        StandardCopyOption.ATOMIC_MOVE,
                        StandardCopyOption.REPLACE_EXISTING,
                    )
                } catch (_: AtomicMoveNotSupportedException) {
                    Files.move(temporary, path, StandardCopyOption.REPLACE_EXISTING)
                }
            } finally {
                Files.deleteIfExists(temporary)
            }
        }

        private fun requireNoSymlinkParents(parent: Path?) {
            var cursor = parent
            while (cursor != null && Files.exists(cursor, LinkOption.NOFOLLOW_LINKS)) {
                require(!Files.isSymbolicLink(cursor)) { "Generated target has a symbolic-link parent: $cursor" }
                cursor = cursor.parent
            }
        }

        private fun resolveSafe(root: Path, relative: String): Path {
            require(relative.isNotBlank() && '\u0000' !in relative) { "Generated path is invalid" }
            require('\\' !in relative) { "Generated paths must use POSIX separators" }
            val relativePath = Path.of(relative)
            require(!relativePath.isAbsolute && relativePath.none { it.toString() == ".." }) {
                "Generated path escapes its root: $relative"
            }
            val resolved = root.resolve(relativePath).normalize()
            require(resolved.startsWith(root)) { "Generated path escapes its root: $relative" }
            return resolved
        }
    }
}
