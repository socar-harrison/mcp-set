package socar.mcp.protocol.core

import java.nio.channels.FileChannel
import java.nio.channels.FileLock
import java.nio.channels.OverlappingFileLockException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.time.Duration

internal const val PROTOCOL_REMOTE = "ssh://git@github.com/socar-inc/socar-protocol.git"
internal const val MAIN_BRANCH = "main"

internal data class ProtocolHeads(
    val main: String,
    val target: String,
)

internal class GitClient(
    private val runner: CommandRunner,
) {
    fun output(repository: Path, vararg arguments: String): String = run(repository, *arguments).stdout.trim()

    fun run(
        repository: Path,
        vararg arguments: String,
        timeout: Duration = GIT_TIMEOUT,
        acceptedExitCodes: Set<Int> = setOf(0),
    ): CommandResult = runner.run(
        CommandSpec(
            argv = listOf("git", "-C", repository.toString()) + arguments,
            directory = repository,
            timeout = timeout,
            acceptedExitCodes = acceptedExitCodes,
        ),
    )

    fun resolveProtocolHeads(directory: Path, branch: String): ProtocolHeads {
        val requestedRefs = linkedSetOf("refs/heads/$MAIN_BRANCH", "refs/heads/$branch")
        val result = runner.run(
            CommandSpec(
                argv = listOf("git", "ls-remote", "--heads", PROTOCOL_REMOTE) + requestedRefs,
                directory = directory,
                timeout = NETWORK_TIMEOUT,
            ),
        )
        val refs = result.stdout
            .lineSequence()
            .filter { it.isNotBlank() }
            .associate { line ->
                val parts = line.split('\t', limit = 2)
                require(parts.size == 2 && SHA_PATTERN.matches(parts[0])) { "Unexpected git ls-remote output" }
                parts[1] to parts[0]
            }
        val main = refs["refs/heads/$MAIN_BRANCH"]
            ?: throw ProtocolCodegenException("MAIN_BRANCH_NOT_FOUND", "Protocol main branch was not found")
        val target = refs["refs/heads/$branch"]
            ?: throw ProtocolCodegenException(
                "PROTOCOL_BRANCH_NOT_FOUND",
                "Protocol branch '$branch' was not found on the fixed remote",
            )
        return ProtocolHeads(main, target)
    }

    fun cloneProtocol(destination: Path, directory: Path) {
        runner.run(
            CommandSpec(
                argv = listOf(
                    "git",
                    "clone",
                    "--filter=blob:none",
                    "--no-checkout",
                    "--no-tags",
                    "--origin",
                    "origin",
                    PROTOCOL_REMOTE,
                    destination.toString(),
                ),
                directory = directory,
                timeout = NETWORK_TIMEOUT,
            ),
        )
    }

    fun addWorktree(repository: Path, destination: Path, commit: String) {
        require(SHA_PATTERN.matches(commit)) { "Worktree commit is not an immutable SHA" }
        run(repository, "worktree", "add", "--detach", destination.toString(), commit, timeout = WORKTREE_TIMEOUT)
    }

    fun verifyRemoteHead(protocolClone: Path, branch: String, expected: String) {
        val actual = output(protocolClone, "rev-parse", "refs/remotes/origin/$branch^{commit}")
        if (actual != expected) {
            throw ProtocolCodegenException(
                "PROTOCOL_BRANCH_MOVED",
                "Protocol branch '$branch' moved while the operation was starting; retry safely",
            )
        }
    }

    fun changedPaths(repository: Path): Set<String> {
        val root = WorkspacePaths.GENERATED_ROOT
        val commands = listOf(
            arrayOf("diff", "--name-only", "-z", "--cached", "HEAD", "--", root),
            arrayOf("diff", "--name-only", "-z", "--", root),
            arrayOf("ls-files", "--others", "--exclude-standard", "-z", "--", root),
        )
        return commands
            .flatMap { arguments -> splitNullDelimited(run(repository, *arguments).stdout) }
            .mapNotNull(::generatedRelativePath)
            .toSet()
    }

    fun findStashOid(repository: Path, message: String): String? {
        require(message.isNotBlank() && '\u0000' !in message && '\n' !in message && '\r' !in message) {
            "Stash message is invalid"
        }
        val result = run(
            repository,
            "log",
            "-g",
            "--fixed-strings",
            "--grep=$message",
            "-1",
            "--format=%H",
            "refs/stash",
            acceptedExitCodes = setOf(0, 128),
        )
        val oid = result.stdout.trim().takeIf(SHA_PATTERN::matches) ?: return null
        val subject = output(repository, "show", "-s", "--format=%s", oid)
        return oid.takeIf { subject.contains(message) }
    }

    private fun generatedRelativePath(repositoryRelative: String): String? {
        val prefix = "${WorkspacePaths.GENERATED_ROOT}/"
        return when {
            repositoryRelative == WorkspacePaths.GENERATED_ROOT -> ""
            repositoryRelative.startsWith(prefix) -> repositoryRelative.removePrefix(prefix)
            else -> null
        }
    }

    companion object {
        val SHA_PATTERN = Regex("[0-9a-f]{40,64}")
        val GIT_TIMEOUT: Duration = Duration.ofMinutes(2)
        val NETWORK_TIMEOUT: Duration = Duration.ofMinutes(5)
        val WORKTREE_TIMEOUT: Duration = Duration.ofMinutes(5)

        fun splitNullDelimited(value: String): List<String> = value
            .split('\u0000')
            .filter { it.isNotEmpty() }
    }
}

internal class WorkspaceLock private constructor(
    private val channel: FileChannel,
    private val lock: FileLock,
) : AutoCloseable {
    override fun close() {
        lock.release()
        channel.close()
    }

    companion object {
        fun tryAcquire(workspace: Path): WorkspaceLock? {
            val lockDirectory = Path.of(System.getProperty("java.io.tmpdir"), "socar-protocol-codegen-locks")
            Files.createDirectories(lockDirectory)
            val lockName = sha256(workspace.toString().toByteArray(Charsets.UTF_8)) + ".lock"
            val channel = FileChannel.open(
                lockDirectory.resolve(lockName),
                StandardOpenOption.CREATE,
                StandardOpenOption.WRITE,
            )
            val lock = try {
                channel.tryLock()
            } catch (_: OverlappingFileLockException) {
                null
            }
            if (lock == null) {
                channel.close()
                return null
            }
            return WorkspaceLock(channel, lock)
        }
    }
}

internal class TemporaryResources(
    val root: Path,
    private val androidRepository: Path,
    private val git: GitClient,
) {
    private val androidWorktrees = mutableListOf<Path>()

    fun registerAndroidWorktree(path: Path) {
        require(path.normalize().startsWith(root.normalize())) { "Temporary worktree escaped its root" }
        androidWorktrees.add(path)
    }

    fun cleanup(): CleanupManifest {
        val warnings = mutableListOf<String>()
        var worktreesRemoved = true
        androidWorktrees.asReversed().forEach { worktree ->
            try {
                git.run(
                    androidRepository,
                    "worktree",
                    "remove",
                    "--force",
                    worktree.toString(),
                    timeout = GitClient.WORKTREE_TIMEOUT,
                )
            } catch (error: Throwable) {
                worktreesRemoved = false
                warnings += "Could not remove temporary Android worktree ${worktree.fileName}: ${safeMessage(error)}"
            }
        }

        var directoryRemoved = false
        if (worktreesRemoved) {
            try {
                require(root.fileName.toString().startsWith(TEMP_PREFIX)) { "Unexpected temporary directory name" }
                FileTree.deleteExact(root)
                directoryRemoved = true
            } catch (error: Throwable) {
                warnings += "Could not remove temporary directory: ${safeMessage(error)}"
            }
        } else {
            warnings += "Temporary directory was retained to avoid orphaning linked worktree metadata: $root"
        }
        return CleanupManifest(directoryRemoved, worktreesRemoved, warnings)
    }

    companion object {
        const val TEMP_PREFIX = "socar-protocol-codegen-"
    }
}

internal class ProtocolCodegenException(
    val code: String,
    override val message: String,
) : RuntimeException(message)

internal fun safeMessage(error: Throwable): String =
    error.message?.lineSequence()?.firstOrNull()?.take(500) ?: error.javaClass.simpleName
