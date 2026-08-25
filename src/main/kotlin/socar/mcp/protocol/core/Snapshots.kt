package socar.mcp.protocol.core

import java.nio.file.FileVisitResult
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.SimpleFileVisitor
import java.nio.file.StandardCopyOption
import java.nio.file.attribute.BasicFileAttributes
import java.nio.file.attribute.PosixFilePermission
import java.security.MessageDigest

object HmtClassifier {
    fun classify(
        head: Map<String, String>,
        main: Map<String, String>,
        target: Map<String, String>,
    ): ChangeClassification = ChangeClassification(
        noise = diff(head, main),
        target = diff(main, target),
    )

    fun diff(before: Map<String, String>, after: Map<String, String>): List<FileChange> =
        (before.keys + after.keys)
            .toSortedSet()
            .mapNotNull { path ->
                when {
                    path !in before -> FileChange(path, FileChangeKind.ADDED)
                    path !in after -> FileChange(path, FileChangeKind.DELETED)
                    before[path] != after[path] -> FileChange(path, FileChangeKind.MODIFIED)
                    else -> null
                }
            }
}

object CollisionDetector {
    fun findChangedPaths(
        baseline: Map<String, String>,
        current: Map<String, String>,
        candidatePaths: Set<String>,
    ): List<String> = candidatePaths
        .asSequence()
        .filter { baseline[it] != current[it] }
        .sorted()
        .toList()
}

internal data class SnapshotEntry(
    val bytes: ByteArray,
    val executable: Boolean,
    val fingerprint: String,
)

internal data class DirectorySnapshot(
    val entries: Map<String, SnapshotEntry>,
) {
    fun fingerprints(): Map<String, String> = entries.mapValues { it.value.fingerprint }

    companion object {
        fun capture(root: Path, requireNonEmpty: Boolean = false): DirectorySnapshot {
            require(Files.isDirectory(root, LinkOption.NOFOLLOW_LINKS)) { "Snapshot root is missing: $root" }
            require(!Files.isSymbolicLink(root)) { "Snapshot root must not be a symbolic link: $root" }

            val entries = linkedMapOf<String, SnapshotEntry>()
            Files.walkFileTree(root, setOf(), Int.MAX_VALUE, object : SimpleFileVisitor<Path>() {
                override fun preVisitDirectory(dir: Path, attrs: BasicFileAttributes): FileVisitResult {
                    require(!attrs.isSymbolicLink) { "Generated directory contains a symbolic link: $dir" }
                    return FileVisitResult.CONTINUE
                }

                override fun visitFile(file: Path, attrs: BasicFileAttributes): FileVisitResult {
                    require(attrs.isRegularFile && !attrs.isSymbolicLink) {
                        "Generated directory contains a non-regular file: $file"
                    }
                    val relative = toPosixPath(root.relativize(file))
                    val bytes = Files.readAllBytes(file)
                    val executable = Files.isExecutable(file)
                    entries[relative] = SnapshotEntry(
                        bytes = bytes,
                        executable = executable,
                        fingerprint = fingerprint(bytes, executable),
                    )
                    return FileVisitResult.CONTINUE
                }
            })
            require(!requireNonEmpty || entries.isNotEmpty()) { "Generation produced no source files" }
            return DirectorySnapshot(entries.toSortedMap())
        }
    }
}

internal object FileTree {
    fun copy(source: Path, destination: Path) {
        require(Files.isDirectory(source, LinkOption.NOFOLLOW_LINKS)) { "Copy source is not a directory: $source" }
        require(!Files.isSymbolicLink(source)) { "Copy source must not be a symbolic link: $source" }
        Files.walkFileTree(source, setOf(), Int.MAX_VALUE, object : SimpleFileVisitor<Path>() {
            override fun preVisitDirectory(dir: Path, attrs: BasicFileAttributes): FileVisitResult {
                require(!attrs.isSymbolicLink) { "Copy source contains a symbolic link: $dir" }
                val relative = source.relativize(dir)
                Files.createDirectories(destination.resolve(relative))
                return FileVisitResult.CONTINUE
            }

            override fun visitFile(file: Path, attrs: BasicFileAttributes): FileVisitResult {
                require(attrs.isRegularFile && !attrs.isSymbolicLink) { "Copy source contains a non-regular file: $file" }
                Files.copy(
                    file,
                    destination.resolve(source.relativize(file)),
                    StandardCopyOption.REPLACE_EXISTING,
                    StandardCopyOption.COPY_ATTRIBUTES,
                )
                return FileVisitResult.CONTINUE
            }
        })
    }

    fun digest(root: Path): String {
        val snapshot = DirectorySnapshot.capture(root, requireNonEmpty = true)
        val digest = MessageDigest.getInstance("SHA-256")
        snapshot.entries.forEach { (path, entry) ->
            digest.update(path.toByteArray(Charsets.UTF_8))
            digest.update(0)
            digest.update(entry.fingerprint.toByteArray(Charsets.UTF_8))
            digest.update(0)
        }
        return digest.digest().toHex()
    }

    fun deleteExact(root: Path) {
        if (!Files.exists(root, LinkOption.NOFOLLOW_LINKS)) return
        require(!Files.isSymbolicLink(root)) { "Refusing to recursively delete a symbolic link: $root" }
        Files.walkFileTree(root, object : SimpleFileVisitor<Path>() {
            override fun visitFile(file: Path, attrs: BasicFileAttributes): FileVisitResult {
                Files.delete(file)
                return FileVisitResult.CONTINUE
            }

            override fun postVisitDirectory(dir: Path, error: java.io.IOException?): FileVisitResult {
                if (error != null) throw error
                Files.delete(dir)
                return FileVisitResult.CONTINUE
            }
        })
    }
}

internal fun applyExecutable(path: Path, executable: Boolean) {
    try {
        val permissions = Files.getPosixFilePermissions(path).toMutableSet()
        val executablePermissions = setOf(
            PosixFilePermission.OWNER_EXECUTE,
            PosixFilePermission.GROUP_EXECUTE,
            PosixFilePermission.OTHERS_EXECUTE,
        )
        if (executable) permissions.add(PosixFilePermission.OWNER_EXECUTE) else permissions.removeAll(executablePermissions)
        Files.setPosixFilePermissions(path, permissions)
    } catch (_: UnsupportedOperationException) {
        path.toFile().setExecutable(executable, true)
    }
}

internal fun fingerprint(bytes: ByteArray, executable: Boolean): String {
    val digest = MessageDigest.getInstance("SHA-256")
    digest.update(if (executable) 1 else 0)
    digest.update(bytes)
    return digest.digest().toHex()
}

internal fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(bytes).toHex()

internal fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }

internal fun toPosixPath(path: Path): String = path.iterator().asSequence().joinToString("/") { it.toString() }
