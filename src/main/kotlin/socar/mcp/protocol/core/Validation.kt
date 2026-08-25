package socar.mcp.protocol.core

import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path

object ProtocolBranch {
    private val allowedCharacters = Regex("[A-Za-z0-9][A-Za-z0-9._/-]{0,254}")

    /**
     * Accepts a Git branch name, not a tag, SHA, refspec, or command fragment.
     * The service later prefixes this value with refs/heads/ and passes it as one argv item.
     */
    fun validate(value: String): String {
        require(value == value.trim() && value.isNotEmpty()) { "protocolBranch must not be blank or padded" }
        require(allowedCharacters.matches(value)) { "protocolBranch contains unsupported characters" }
        require(!value.startsWith("-") && !value.endsWith("/")) { "protocolBranch has an invalid boundary" }
        require(!value.contains("//") && !value.contains("..") && !value.contains("@{")) {
            "protocolBranch is not a valid Git branch"
        }
        require(!value.endsWith(".") && !value.endsWith(".lock", ignoreCase = true)) {
            "protocolBranch is not a valid Git branch"
        }
        require(value.split('/').none { it.isEmpty() || it.startsWith('.') || it.endsWith(".lock", true) }) {
            "protocolBranch contains an invalid path component"
        }
        return value
    }
}

object GenerateScript {
    private val branchDeclaration = Regex(
        "(?m)^[ \\t]*val[ \\t]+branch[ \\t]*=[ \\t]*\\\"([^\\\"\\r\\n]*)\\\"[ \\t]*\\r?$",
    )
    private val remoteDeclaration = Regex(
        "(?m)^[ \\t]*val socarProtocolRepo = \\\"ssh://git@github\\.com/socar-inc/socar-protocol\\.git#subdir=\\${'$'}subDir,branch=\\${'$'}branch\\\"[ \\t]*$",
    )
    private const val WAIT_EXPRESSION = ".waitFor()"

    fun withBranch(source: String, branch: String): String {
        ProtocolBranch.validate(branch)
        val matches = branchDeclaration.findAll(source).toList()
        require(matches.size == 1) { "generate.kts must contain exactly one branch declaration" }
        val valueRange = matches.single().groups[1]?.range
            ?: error("generate.kts branch declaration did not capture its value")
        return source.replaceRange(valueRange, branch)
    }

    /**
     * Rewrites only an isolated copy. Local immutable protocol checkouts avoid a branch moving
     * between resolution and generation. Exit checks compensate for the legacy script ignoring
     * child exit codes; an unexpected script shape fails closed instead of running unchecked.
     */
    fun hardenedForLocalProtocol(source: String, branch: String): String {
        var hardened = withBranch(source, branch)
        val remoteMatches = remoteDeclaration.findAll(hardened).toList()
        require(remoteMatches.size == 1) { "generate.kts remote declaration has an unexpected shape" }
        val localDeclaration = """
            val socarProtocolRepo = File(
                requireNotNull(System.getenv("SOCAR_PROTOCOL_CHECKOUT")) {
                    "SOCAR_PROTOCOL_CHECKOUT is required"
                },
                subDir,
            ).canonicalPath
        """.trimIndent()
        hardened = hardened.replaceRange(remoteMatches.single().range, localDeclaration)

        val waitCount = hardened.windowed(WAIT_EXPRESSION.length).count { it == WAIT_EXPRESSION }
        require(waitCount == 2) { "generate.kts must contain exactly two child process waits" }
        return hardened.replace(
            WAIT_EXPRESSION,
            ".waitFor().also { exitCode -> check(exitCode == 0) { \"Generated command failed with exit code ${'$'}exitCode\" } }",
        )
    }
}

data class WorkspaceLayout(
    val root: Path,
    val gitEntry: Path,
    val generator: Path,
    val generatedRoot: Path,
    val pluginDistribution: Path,
    val gradleWrapper: Path,
)

object WorkspacePaths {
    private const val GENERATOR = "tools/api2-model/generate.kts"
    const val GENERATED_ROOT = "socar-android-api2-model/src/main/java"
    const val PLUGIN_DISTRIBUTION =
        "tools/pbandk/protoc-gen-pbandk/jvm/build/install/protoc-gen-pbandk"

    fun resolve(workspacePath: String): WorkspaceLayout {
        require(workspacePath.isNotBlank()) { "workspacePath must not be blank" }
        val root = Path.of(workspacePath).toRealPath()
        require(Files.isDirectory(root, LinkOption.NOFOLLOW_LINKS)) { "workspacePath is not a directory" }

        val gitEntry = required(root, ".git", allowDirectory = true)
        val generator = required(root, GENERATOR)
        val generatedRoot = required(root, GENERATED_ROOT, allowDirectory = true)
        val pluginDistribution = required(root, PLUGIN_DISTRIBUTION, allowDirectory = true)
        val gradleWrapper = required(root, "gradlew")

        return WorkspaceLayout(
            root = root,
            gitEntry = gitEntry,
            generator = generator,
            generatedRoot = generatedRoot,
            pluginDistribution = pluginDistribution,
            gradleWrapper = gradleWrapper,
        )
    }

    private fun required(root: Path, relative: String, allowDirectory: Boolean = false): Path {
        val unresolved = root.resolve(relative).normalize()
        require(unresolved.startsWith(root)) { "Required path escapes workspace: $relative" }
        require(Files.exists(unresolved, LinkOption.NOFOLLOW_LINKS)) { "Required path is missing: $relative" }
        var cursor = root
        root.relativize(unresolved).forEach { component ->
            cursor = cursor.resolve(component)
            require(!Files.isSymbolicLink(cursor)) { "Required workspace path contains a symbolic link: $relative" }
        }
        val real = unresolved.toRealPath()
        require(real.startsWith(root)) { "Required path escapes workspace: $relative" }
        require(allowDirectory || Files.isRegularFile(real, LinkOption.NOFOLLOW_LINKS)) {
            "Required path is not a regular file: $relative"
        }
        require(!allowDirectory || Files.isDirectory(real, LinkOption.NOFOLLOW_LINKS) || relative == ".git") {
            "Required path is not a directory: $relative"
        }
        return real
    }
}
