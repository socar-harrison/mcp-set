package socar.mcp.protocol.core

import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration
import kotlin.io.path.createDirectories
import kotlin.io.path.createSymbolicLinkPointingTo
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFails
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class WorkspacePathsTest {
    @Test
    fun `resolves the fixed generator layout under the workspace`() = withTemporaryDirectory { root ->
        createWorkspaceFixture(root)

        val layout = WorkspacePaths.resolve(root.toString())

        assertEquals(root.toRealPath(), layout.root)
        assertEquals(root.resolve("tools/api2-model/generate.kts").toRealPath(), layout.generator)
        assertEquals(
            root.resolve("socar-android-api2-model/src/main/java").toRealPath(),
            layout.generatedRoot,
        )
        assertTrue(layout.generator.startsWith(layout.root))
        assertTrue(layout.generatedRoot.startsWith(layout.root))
    }

    @Test
    fun `rejects a required file that is a symbolic link`() = withTemporaryDirectory { root ->
        createWorkspaceFixture(root)
        val externalGenerator = Files.createTempFile("external-generate", ".kts")
        try {
            val generator = root.resolve("tools/api2-model/generate.kts")
            Files.delete(generator)
            generator.createSymbolicLinkPointingTo(externalGenerator)

            val failure = assertFails { WorkspacePaths.resolve(root.toString()) }

            assertTrue(failure.message.orEmpty().contains("symbolic link", ignoreCase = true))
        } finally {
            Files.deleteIfExists(externalGenerator)
        }
    }

    @Test
    fun `rejects an intermediate symbolic link that escapes the workspace`() = withTemporaryDirectory { root ->
        createWorkspaceFixture(root)
        val external = Files.createTempDirectory("external-tools")
        try {
            val api2Model = external.resolve("api2-model").createDirectories()
            api2Model.resolve("generate.kts").writeText("val branch = \"main\"\n")
            val plugin = external.resolve("pbandk/protoc-gen-pbandk/jvm/build/install/protoc-gen-pbandk")
                .createDirectories()
            assertTrue(Files.isDirectory(plugin))

            root.resolve("tools").toFile().deleteRecursively()
            root.resolve("tools").createSymbolicLinkPointingTo(external)

            val failure = assertFails { WorkspacePaths.resolve(root.toString()) }

            assertTrue(failure.message.orEmpty().contains("workspace", ignoreCase = true))
        } finally {
            external.toFile().deleteRecursively()
        }
    }

    @Test
    fun `snapshot rejects a symbolic link nested in generated sources`() = withTemporaryDirectory { root ->
        val generated = root.resolve("generated").createDirectories()
        val external = Files.createTempFile("external-generated", ".kt")
        try {
            generated.resolve("Safe.kt").writeText("data class Safe(val id: Long)\n")
            generated.resolve("Escaped.kt").createSymbolicLinkPointingTo(external)

            val failure = assertFails { DirectorySnapshot.capture(generated) }

            assertTrue(
                failure.message.orEmpty().contains("symbolic link", ignoreCase = true) ||
                    failure.message.orEmpty().contains("non-regular", ignoreCase = true),
            )
        } finally {
            Files.deleteIfExists(external)
        }
    }

    private fun createWorkspaceFixture(root: Path) {
        root.resolve(".git").createDirectories()
        root.resolve("tools/api2-model").createDirectories()
        root.resolve("tools/api2-model/generate.kts").writeText("val branch = \"main\"\n")
        root.resolve("tools/pbandk/protoc-gen-pbandk/jvm/build/install/protoc-gen-pbandk").createDirectories()
        root.resolve("socar-android-api2-model/src/main/java").createDirectories()
        root.resolve("gradlew").writeText("#!/bin/sh\n")
    }
}

class GeneratedFileTransactionTest {
    @Test
    fun `rejects a generated relative path that escapes its root`() = withTemporaryDirectory { directory ->
        val generated = directory.resolve("generated").createDirectories()
        val escaped = directory.resolve("escaped.kt")
        val entry = snapshotEntry("data class Escaped(val value: String)\n")

        assertFails {
            GeneratedFileTransaction.apply(
                root = generated,
                target = DirectorySnapshot(mapOf("../escaped.kt" to entry)),
                changes = listOf(FileChange("../escaped.kt", FileChangeKind.ADDED)),
            )
        }

        assertFalse(Files.exists(escaped))
    }

    @Test
    fun `rejects a symbolic link in a generated file parent path`() = withTemporaryDirectory { directory ->
        val generated = directory.resolve("generated").createDirectories()
        val external = directory.resolve("external").createDirectories()
        generated.resolve("package").createSymbolicLinkPointingTo(external)
        val externalTarget = external.resolve("Injected.kt")
        val entry = snapshotEntry("data class Injected(val value: String)\n")

        val failure = assertFails {
            GeneratedFileTransaction.apply(
                root = generated,
                target = DirectorySnapshot(mapOf("package/Injected.kt" to entry)),
                changes = listOf(FileChange("package/Injected.kt", FileChangeKind.ADDED)),
            )
        }

        assertTrue(failure.message.orEmpty().contains("symbolic", ignoreCase = true))
        assertFalse(Files.exists(externalTarget))
    }

    private fun snapshotEntry(content: String): SnapshotEntry {
        val bytes = content.toByteArray()
        return SnapshotEntry(
            bytes = bytes,
            executable = false,
            fingerprint = fingerprint(bytes, executable = false),
        )
    }
}

class ThreeWayGenerationPlannerTest {
    @Test
    fun `applies target delta onto head while excluding disjoint main-only churn`() =
        withTemporaryDirectory { temporaryRoot ->
            val path = "socar/generated/Model.kt"
            val head = directorySnapshot(
                path to """
                    package socar.generated

                    val mainNoise = "head"
                    val filler1 = 1
                    val filler2 = 2
                    val filler3 = 3
                    val filler4 = 4
                    val filler5 = 5
                    val filler6 = 6
                    val filler7 = 7
                    val targetValue = "head"
                """.trimIndent(),
            )
            val main = directorySnapshot(
                path to """
                    package socar.generated

                    val mainNoise = "main-churn"
                    val filler1 = 1
                    val filler2 = 2
                    val filler3 = 3
                    val filler4 = 4
                    val filler5 = 5
                    val filler6 = 6
                    val filler7 = 7
                    val targetValue = "head"
                """.trimIndent(),
            )
            val target = directorySnapshot(
                path to """
                    package socar.generated

                    val mainNoise = "main-churn"
                    val filler1 = 1
                    val filler2 = 2
                    val filler3 = 3
                    val filler4 = 4
                    val filler5 = 5
                    val filler6 = 6
                    val filler7 = 7
                    val targetValue = "target-branch"
                """.trimIndent(),
            )
            val classification = HmtClassifier.classify(
                head.fingerprints(),
                main.fingerprints(),
                target.fingerprints(),
            )

            val planned = ThreeWayGenerationPlanner(CommandRunner()).plan(
                head = head,
                main = main,
                target = target,
                classification = classification,
                temporaryRoot = temporaryRoot,
            )

            assertEquals(emptyList(), planned.conflicts)
            assertEquals(listOf(FileChange(path, FileChangeKind.MODIFIED)), planned.changes)
            val merged = requireNotNull(planned.snapshot).entries.getValue(path).bytes.toString(Charsets.UTF_8)
            assertTrue(merged.contains("val mainNoise = \"head\""))
            assertFalse(merged.contains("main-churn"))
            assertTrue(merged.contains("val targetValue = \"target-branch\""))
        }

    @Test
    fun `blocks target and main changes that overlap in the same text hunk`() =
        withTemporaryDirectory { temporaryRoot ->
            val path = "socar/generated/Conflicted.kt"
            val head = directorySnapshot(path to "val value = \"head\"\n")
            val main = directorySnapshot(path to "val value = \"main-churn\"\n")
            val target = directorySnapshot(path to "val value = \"target-branch\"\n")
            val classification = HmtClassifier.classify(
                head.fingerprints(),
                main.fingerprints(),
                target.fingerprints(),
            )

            val planned = ThreeWayGenerationPlanner(CommandRunner()).plan(
                head = head,
                main = main,
                target = target,
                classification = classification,
                temporaryRoot = temporaryRoot,
            )

            assertEquals(listOf(path), planned.conflicts)
            assertEquals(null, planned.snapshot)
            assertEquals(emptyList(), planned.changes)
        }

    @Test
    fun `blocks ambiguous add then modify lifecycle overlap`() =
        withTemporaryDirectory { temporaryRoot ->
            val path = "socar/generated/NewModel.kt"
            val head = directorySnapshot()
            val main = directorySnapshot(path to "data class NewModel(val from: String = \"main\")\n")
            val target = directorySnapshot(path to "data class NewModel(val from: String = \"target\")\n")
            val classification = HmtClassifier.classify(
                head.fingerprints(),
                main.fingerprints(),
                target.fingerprints(),
            )

            val planned = ThreeWayGenerationPlanner(CommandRunner()).plan(
                head = head,
                main = main,
                target = target,
                classification = classification,
                temporaryRoot = temporaryRoot,
            )

            assertEquals(listOf(path), planned.conflicts)
            assertEquals(null, planned.snapshot)
            assertEquals(emptyList(), planned.changes)
        }

    private fun directorySnapshot(vararg files: Pair<String, String>): DirectorySnapshot =
        DirectorySnapshot(
            files.associate { (path, content) ->
                val bytes = content.toByteArray()
                path to SnapshotEntry(
                    bytes = bytes,
                    executable = false,
                    fingerprint = fingerprint(bytes, executable = false),
                )
            },
        )
}

class CommandRunnerTest {
    @Test
    fun `bounds stdout and stderr retained on a failed command`() = withTemporaryDirectory { directory ->
        val runner = CommandRunner(maxOutputBytes = 8)

        val failure = assertFailsWith<CommandFailure> {
            runner.run(
                CommandSpec(
                    argv = listOf(
                        "/bin/sh",
                        "-c",
                        "printf 12345678901234567890; printf abcdefghijklmnopqrst >&2; exit 7",
                    ),
                    directory = directory,
                    timeout = Duration.ofSeconds(5),
                ),
            )
        }

        assertEquals(7, failure.exitCode)
        assertFalse(failure.timedOut)
        assertTrue(failure.stdout.startsWith("12345678"))
        assertTrue(failure.stderr.startsWith("abcdefgh"))
        assertTrue(failure.stdout.contains("output truncated"))
        assertTrue(failure.stderr.contains("output truncated"))
        assertTrue(failure.stdout.length < 128)
        assertTrue(failure.stderr.length < 128)
        assertFalse(failure.message.orEmpty().contains("12345678"))
        assertFalse(failure.message.orEmpty().contains("abcdefgh"))
    }

    @Test
    fun `reports timeout separately from a nonzero exit`() = withTemporaryDirectory { directory ->
        val failure = assertFailsWith<CommandFailure> {
            CommandRunner(maxOutputBytes = 128).run(
                CommandSpec(
                    argv = listOf("/bin/sh", "-c", "sleep 5"),
                    directory = directory,
                    timeout = Duration.ofMillis(50),
                ),
            )
        }

        assertTrue(failure.timedOut)
        assertTrue(failure.message.orEmpty().contains("timed out", ignoreCase = true))
    }

    @Test
    fun `rejects unsafe output limits before launching a process`() {
        assertFails { CommandRunner(maxOutputBytes = 0) }
        assertFails { CommandRunner(maxOutputBytes = CommandRunner.ABSOLUTE_MAX_OUTPUT_BYTES + 1) }
    }
}

class NamedStashTest {
    @Test
    fun `records the named generated stash OID and leaves outside changes untouched`() =
        withTemporaryDirectory { repository ->
            val git = GitClient(CommandRunner())
            git.run(repository, "init")
            git.run(repository, "config", "user.name", "Protocol Codegen Test")
            git.run(repository, "config", "user.email", "protocol-codegen@example.invalid")

            val generated = repository.resolve(WorkspacePaths.GENERATED_ROOT).createDirectories()
            val existing = generated.resolve("Existing.kt")
            existing.writeText("val value = \"head\"\n")
            val outside = repository.resolve("outside.txt")
            outside.writeText("head\n")
            git.run(repository, "add", ".")
            git.run(repository, "commit", "-m", "fixture")

            existing.writeText("val value = \"main-noise\"\n")
            generated.resolve("Added.kt").writeText("val added = true\n")
            outside.writeText("caller change\n")
            val name = "socar-protocol-codegen/test/0123456789ab/00000000-0000-0000-0000-000000000000"

            git.run(
                repository,
                "stash",
                "push",
                "--include-untracked",
                "--message",
                name,
                "--",
                WorkspacePaths.GENERATED_ROOT,
            )

            val oid = git.findStashOid(repository, name)
            assertTrue(oid != null && GitClient.SHA_PATTERN.matches(oid))
            assertEquals("val value = \"head\"\n", existing.toFile().readText())
            assertFalse(Files.exists(generated.resolve("Added.kt")))
            assertEquals("caller change\n", outside.toFile().readText())
            assertTrue(git.run(repository, "status", "--short").stdout.contains("outside.txt"))
        }
}

private inline fun withTemporaryDirectory(block: (Path) -> Unit) {
    val directory = Files.createTempDirectory("protocol-codegen-test")
    try {
        block(directory)
    } finally {
        directory.toFile().deleteRecursively()
    }
}
