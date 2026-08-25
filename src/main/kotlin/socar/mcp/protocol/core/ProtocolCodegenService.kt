package socar.mcp.protocol.core

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.time.Duration
import java.util.UUID

class ProtocolCodegenService(
    private val commandRunner: CommandRunner = CommandRunner(),
) {
    private val git = GitClient(commandRunner)

    suspend fun generate(request: GenerationRequest): GenerationResult = withContext(Dispatchers.IO) {
        val branch = try {
            ProtocolBranch.validate(request.protocolBranch)
        } catch (error: IllegalArgumentException) {
            return@withContext invalidRequest(error)
        }
        val workspace = try {
            WorkspacePaths.resolve(request.workspacePath)
        } catch (error: Exception) {
            return@withContext invalidRequest(error)
        }
        val lock = WorkspaceLock.tryAcquire(workspace.root)
            ?: return@withContext GenerationResult(
                status = GenerationStatus.BLOCKED,
                message = "Another protocol generation is already running for this workspace",
                error = ErrorManifest("WORKSPACE_BUSY", "Wait for the existing generation to finish"),
            )
        lock.use {
            executeWithCleanup(request.copy(protocolBranch = branch), workspace)
        }
    }

    private fun executeWithCleanup(
        request: GenerationRequest,
        workspace: WorkspaceLayout,
    ): GenerationResult {
        val state = ExecutionState()
        val temporaryRoot = Files.createTempDirectory(TemporaryResources.TEMP_PREFIX).toRealPath()
        val resources = TemporaryResources(temporaryRoot, workspace.root, git)
        var cancellation: CancellationException? = null
        val preliminary = try {
            execute(request, workspace, temporaryRoot, resources, state)
        } catch (error: CancellationException) {
            cancellation = error
            state.failure("CANCELLED", "Protocol generation was cancelled")
        } catch (error: ProtocolCodegenException) {
            state.failure(error.code, error.message)
        } catch (error: CommandFailure) {
            val code = if (error.timedOut) "COMMAND_TIMEOUT" else "COMMAND_FAILED"
            state.failure(code, commandFailureMessage(error))
        } catch (error: IllegalArgumentException) {
            state.failure("SAFETY_CHECK_FAILED", safeMessage(error))
        } catch (error: Throwable) {
            state.failure("UNEXPECTED_FAILURE", safeMessage(error))
        }
        val cleanup = resources.cleanup()
        cancellation?.let { throw it }
        return preliminary.copy(cleanup = cleanup)
    }

    private fun execute(
        request: GenerationRequest,
        workspace: WorkspaceLayout,
        temporaryRoot: Path,
        resources: TemporaryResources,
        state: ExecutionState,
    ): GenerationResult {
        val repositoryState = inspectRepository(workspace)
        val tools = inspectTools(workspace.root)
        val artifactDigest = FileTree.digest(workspace.pluginDistribution)
        val heads = git.resolveProtocolHeads(workspace.root, request.protocolBranch)
        val paths = createWorktrees(
            workspace,
            repositoryState.androidHead,
            request.protocolBranch,
            heads,
            temporaryRoot,
            resources,
        )

        val headSnapshot = DirectorySnapshot.capture(
            paths.androidMain.resolve(WorkspacePaths.GENERATED_ROOT),
            requireNonEmpty = true,
        )
        val targetHeadSnapshot = DirectorySnapshot.capture(
            paths.androidTarget.resolve(WorkspacePaths.GENERATED_ROOT),
            requireNonEmpty = true,
        )
        require(headSnapshot.fingerprints() == targetHeadSnapshot.fingerprints()) {
            "Isolated Android worktrees do not have the same generated baseline"
        }

        val generatorBytes = Files.readAllBytes(paths.androidMain.resolve(GENERATOR_PATH))
        state.manifest = GenerationManifest(
            workspacePath = workspace.root.toString(),
            protocolRemote = PROTOCOL_REMOTE,
            protocolBranch = request.protocolBranch,
            androidHeadSha = repositoryState.androidHead,
            protocolMainSha = heads.main,
            protocolTargetSha = heads.target,
            pbandkSha = repositoryState.pbandkSha,
            generatorSha256 = sha256(generatorBytes),
            pbandkArtifactSha256 = artifactDigest,
            tools = tools,
        )

        val mainSnapshot = generateSnapshot(
            paths.androidMain,
            paths.protocolMain,
            heads.main,
            workspace.pluginDistribution,
            artifactDigest,
        )
        val targetSnapshot = generateSnapshot(
            paths.androidTarget,
            paths.protocolTarget,
            heads.target,
            workspace.pluginDistribution,
            artifactDigest,
        )
        val classification = HmtClassifier.classify(
            headSnapshot.fingerprints(),
            mainSnapshot.fingerprints(),
            targetSnapshot.fingerprints(),
        )
        val plan = ThreeWayGenerationPlanner(commandRunner).plan(
            headSnapshot,
            mainSnapshot,
            targetSnapshot,
            classification,
            temporaryRoot,
        )
        if (plan.conflicts.isNotEmpty()) {
            state.changes = ChangeManifest(noise = classification.noise, target = classification.target)
            return state.planningBlocked(plan.conflicts)
        }
        val plannedSnapshot = requireNotNull(plan.snapshot) { "Three-way planner returned no snapshot" }
        state.changes = ChangeManifest(
            noise = classification.noise,
            target = classification.target,
            planned = plan.changes,
        )

        val conflicts = findConflicts(workspace, repositoryState.androidHead, headSnapshot, plan.changes)
        if (conflicts.isNotEmpty()) return state.blocked(conflicts)

        state.verification = verifyPlan(
            request.verify,
            paths.androidMain,
            headSnapshot,
            mainSnapshot,
            plannedSnapshot,
            classification,
            plan.changes,
        )
        if (state.verification.status == VerificationStatus.FAILED) {
            return state.failure("VERIFICATION_FAILED", state.verification.summary ?: "Module verification failed")
        }
        if (request.dryRun) {
            return state.result(
                GenerationStatus.DRY_RUN,
                "Dry run completed; no stash was created and the workspace was not modified",
            )
        }

        val refreshedConflicts = findConflicts(
            workspace,
            repositoryState.androidHead,
            headSnapshot,
            plan.changes,
        )
        if (refreshedConflicts.isNotEmpty()) return state.blocked(refreshedConflicts)

        state.stash = createNoiseStash(
            paths.androidMain,
            request.protocolBranch,
            heads.target,
            classification.noise,
            headSnapshot,
        ) { created -> state.stash = created }

        val finalConflicts = findConflicts(
            workspace,
            repositoryState.androidHead,
            headSnapshot,
            plan.changes,
        )
        if (finalConflicts.isNotEmpty()) return state.blocked(finalConflicts)

        applyToWorkspace(workspace, plannedSnapshot, plan.changes)
        state.changes = state.changes.copy(appliedPaths = plan.changes.map { it.path })
        val status = if (plan.changes.isEmpty()) {
            GenerationStatus.NO_TARGET_CHANGES
        } else {
            GenerationStatus.APPLIED
        }
        val message = if (plan.changes.isEmpty()) {
            "Generation completed; the target branch has no net changes after filtering protocol-main churn"
        } else {
            "Target protocol model changes were applied without committing or pushing"
        }
        return state.result(status, message)
    }

    private fun inspectRepository(workspace: WorkspaceLayout): RepositoryState {
        val topLevel = Path.of(git.output(workspace.root, "rev-parse", "--show-toplevel")).toRealPath()
        require(topLevel == workspace.root) { "workspacePath must be the Git repository root" }
        val androidHead = git.output(workspace.root, "rev-parse", "HEAD^{commit}")
        require(GitClient.SHA_PATTERN.matches(androidHead)) { "Android HEAD is not an immutable commit" }

        val treeEntry = git.output(workspace.root, "ls-tree", androidHead, "--", "tools/pbandk")
        val match = PBANDK_TREE_ENTRY.matchEntire(treeEntry)
            ?: throw ProtocolCodegenException("PBANDK_NOT_PINNED", "tools/pbandk is not a pinned Git submodule")
        val pbandkSha = match.groupValues[1]
        val currentPbandkHead = git.output(workspace.root.resolve("tools/pbandk"), "rev-parse", "HEAD^{commit}")
        if (currentPbandkHead != pbandkSha) {
            throw ProtocolCodegenException(
                "PBANDK_HEAD_MISMATCH",
                "tools/pbandk must be checked out at the Android HEAD gitlink; local dirty files are allowed",
            )
        }
        return RepositoryState(androidHead, pbandkSha)
    }

    private fun inspectTools(directory: Path): List<ToolVersion> {
        val commands = listOf(
            "git" to listOf("git", "--version"),
            "kotlin" to listOf("kotlin", "-version"),
            "buf" to listOf("buf", "--version"),
            "protoc" to listOf("protoc", "--version"),
        )
        return commands.map { (name, argv) ->
            val output = commandRunner.run(
                CommandSpec(argv, directory, TOOL_TIMEOUT),
            ).let { (it.stdout + "\n" + it.stderr).trim() }
            ToolVersion(name, output.lineSequence().firstOrNull()?.take(300).orEmpty())
        } + ToolVersion("java", System.getProperty("java.version"))
    }

    private fun createWorktrees(
        workspace: WorkspaceLayout,
        androidHead: String,
        protocolBranch: String,
        heads: ProtocolHeads,
        temporaryRoot: Path,
        resources: TemporaryResources,
    ): IsolatedPaths {
        val androidMain = temporaryRoot.resolve("android-main")
        val androidTarget = temporaryRoot.resolve("android-target")
        addAndroidWorktree(workspace.root, androidMain, androidHead, resources)
        addAndroidWorktree(workspace.root, androidTarget, androidHead, resources)

        val protocolClone = temporaryRoot.resolve("protocol-repository")
        git.cloneProtocol(protocolClone, temporaryRoot)
        git.verifyRemoteHead(protocolClone, MAIN_BRANCH, heads.main)
        git.verifyRemoteHead(protocolClone, protocolBranch, heads.target)

        val protocolMain = temporaryRoot.resolve("protocol-main")
        val protocolTarget = temporaryRoot.resolve("protocol-target")
        git.addWorktree(protocolClone, protocolMain, heads.main)
        git.addWorktree(protocolClone, protocolTarget, heads.target)
        return IsolatedPaths(androidMain, androidTarget, protocolMain, protocolTarget)
    }

    private fun addAndroidWorktree(
        repository: Path,
        destination: Path,
        commit: String,
        resources: TemporaryResources,
    ) {
        try {
            git.addWorktree(repository, destination, commit)
        } finally {
            if (Files.exists(destination.resolve(".git"), LinkOption.NOFOLLOW_LINKS)) {
                resources.registerAndroidWorktree(destination)
            }
        }
    }

    private fun generateSnapshot(
        androidWorktree: Path,
        protocolWorktree: Path,
        protocolSha: String,
        pluginSource: Path,
        expectedPluginDigest: String,
    ): DirectorySnapshot {
        val pluginDestination = androidWorktree.resolve(WorkspacePaths.PLUGIN_DISTRIBUTION)
        FileTree.copy(pluginSource, pluginDestination)
        require(FileTree.digest(pluginDestination) == expectedPluginDigest) {
            "pbandk artifact changed while it was copied"
        }

        val generator = androidWorktree.resolve(GENERATOR_PATH)
        val source = Files.readString(generator)
        Files.writeString(generator, GenerateScript.hardenedForLocalProtocol(source, protocolSha))
        commandRunner.run(
            CommandSpec(
                argv = listOf("kotlin", "generate.kts"),
                directory = generator.parent,
                timeout = GENERATION_TIMEOUT,
                environment = mapOf("SOCAR_PROTOCOL_CHECKOUT" to protocolWorktree.toString()),
            ),
        )
        return DirectorySnapshot.capture(
            androidWorktree.resolve(WorkspacePaths.GENERATED_ROOT),
            requireNonEmpty = true,
        )
    }

    private fun findConflicts(
        workspace: WorkspaceLayout,
        expectedHead: String,
        headSnapshot: DirectorySnapshot,
        targetChanges: List<FileChange>,
    ): List<String> {
        val actualHead = git.output(workspace.root, "rev-parse", "HEAD^{commit}")
        if (actualHead != expectedHead) return listOf("<repository HEAD changed during generation>")
        if (targetChanges.isEmpty()) return emptyList()

        val candidatePaths = targetChanges.mapTo(sortedSetOf()) { it.path }
        val current = DirectorySnapshot.capture(workspace.generatedRoot)
        val contentConflicts = CollisionDetector.findChangedPaths(
            headSnapshot.fingerprints(),
            current.fingerprints(),
            candidatePaths,
        )
        val gitConflicts = git.changedPaths(workspace.root).intersect(candidatePaths)
        return (contentConflicts + gitConflicts).distinct().sorted()
    }

    private fun verifyPlan(
        verify: Boolean,
        androidMain: Path,
        head: DirectorySnapshot,
        main: DirectorySnapshot,
        planned: DirectorySnapshot,
        classification: ChangeClassification,
        plannedChanges: List<FileChange>,
    ): VerificationManifest {
        if (!verify) return VerificationManifest(VerificationStatus.SKIPPED, summary = "Verification disabled by request")
        val generatedRoot = androidMain.resolve(WorkspacePaths.GENERATED_ROOT)
        val restoreHead = GeneratedFileTransaction.apply(generatedRoot, head, classification.noise)
        try {
            val applyTarget = GeneratedFileTransaction.apply(generatedRoot, planned, plannedChanges)
            try {
                require(DirectorySnapshot.capture(generatedRoot).fingerprints() == planned.fingerprints()) {
                    "Verification worktree does not match the planned H + (T - M) state"
                }
                return runModuleVerification(androidMain)
            } finally {
                applyTarget.rollback()
            }
        } finally {
            restoreHead.rollback()
            require(DirectorySnapshot.capture(generatedRoot).fingerprints() == main.fingerprints()) {
                "Verification worktree could not be restored after the build"
            }
        }
    }

    private fun runModuleVerification(androidWorktree: Path): VerificationManifest {
        val displayCommand = listOf("./gradlew", VERIFY_TASK, "--no-daemon", "--console=plain")
        return try {
            commandRunner.run(
                CommandSpec(
                    argv = listOf(androidWorktree.resolve("gradlew").toString()) + displayCommand.drop(1),
                    directory = androidWorktree,
                    timeout = VERIFICATION_TIMEOUT,
                ),
            )
            VerificationManifest(VerificationStatus.PASSED, displayCommand, "API2 model module compilation passed")
        } catch (error: CommandFailure) {
            VerificationManifest(
                VerificationStatus.FAILED,
                displayCommand,
                commandFailureMessage(error),
            )
        }
    }

    private fun createNoiseStash(
        androidMain: Path,
        branch: String,
        targetSha: String,
        noiseChanges: List<FileChange>,
        headSnapshot: DirectorySnapshot,
        onCreated: (StashManifest) -> Unit,
    ): StashManifest {
        if (noiseChanges.isEmpty()) return StashManifest(StashStatus.NOT_NEEDED)
        rejectIgnoredNoise(androidMain, noiseChanges)
        val safeBranch = branch.replace(Regex("[^A-Za-z0-9._-]"), "_").take(80)
        val name = "socar-protocol-codegen/$safeBranch/${targetSha.take(12)}/${UUID.randomUUID()}"
        git.run(
            androidMain,
            "stash",
            "push",
            "--include-untracked",
            "--message",
            name,
            "--",
            WorkspacePaths.GENERATED_ROOT,
        )
        // Search by the UUID-bearing message instead of assuming our stash is still stash@{0};
        // another worktree may update the repository-wide stash stack concurrently.
        val oid = git.findStashOid(androidMain, name)
            ?: throw ProtocolCodegenException("STASH_NOT_CREATED", "Git did not create the required noise stash")
        val created = StashManifest(StashStatus.CREATED, name, oid)
        onCreated(created)
        require(
            DirectorySnapshot.capture(androidMain.resolve(WorkspacePaths.GENERATED_ROOT)).fingerprints() ==
                headSnapshot.fingerprints(),
        ) { "Noise stash did not restore the isolated generated baseline" }
        return created
    }

    private fun rejectIgnoredNoise(androidMain: Path, changes: List<FileChange>) {
        changes.filter { it.kind == FileChangeKind.ADDED }.forEach { change ->
            val repositoryPath = "${WorkspacePaths.GENERATED_ROOT}/${change.path}"
            val result = git.run(
                androidMain,
                "check-ignore",
                "-q",
                "--",
                repositoryPath,
                acceptedExitCodes = setOf(0, 1),
            )
            if (result.exitCode == 0) {
                throw ProtocolCodegenException(
                    "IGNORED_NOISE_CANNOT_BE_STASHED",
                    "Generated noise contains an ignored file and cannot be safely stashed: ${change.path}",
                )
            }
        }
    }

    private fun applyToWorkspace(
        workspace: WorkspaceLayout,
        target: DirectorySnapshot,
        changes: List<FileChange>,
    ) {
        if (changes.isEmpty()) return
        val transaction = GeneratedFileTransaction.apply(workspace.generatedRoot, target, changes)
        try {
            val actual = DirectorySnapshot.capture(workspace.generatedRoot).fingerprints()
            val mismatches = changes.map { it.path }.filter { actual[it] != target.fingerprints()[it] }
            require(mismatches.isEmpty()) { "Applied generated files did not match target output: $mismatches" }
            transaction.commit()
        } catch (error: Throwable) {
            transaction.rollback()
            throw error
        }
    }

    private fun invalidRequest(error: Exception): GenerationResult = GenerationResult(
        status = GenerationStatus.FAILED,
        message = "The protocol generation request is invalid",
        error = ErrorManifest("INVALID_REQUEST", safeMessage(error)),
    )

    private fun commandFailureMessage(error: CommandFailure): String {
        val detail = error.stderr.ifBlank { error.stdout }.takeLast(2_000).trim()
        return buildString {
            append(error.message)
            if (detail.isNotEmpty()) append(": ").append(detail)
        }.take(2_500)
    }

    private data class RepositoryState(val androidHead: String, val pbandkSha: String)

    private data class IsolatedPaths(
        val androidMain: Path,
        val androidTarget: Path,
        val protocolMain: Path,
        val protocolTarget: Path,
    )

    private class ExecutionState {
        var manifest: GenerationManifest? = null
        var changes: ChangeManifest = ChangeManifest()
        var stash: StashManifest = StashManifest()
        var verification: VerificationManifest = VerificationManifest()

        fun blocked(conflicts: List<String>): GenerationResult {
            changes = changes.copy(conflicts = conflicts)
            val message = if (stash.status == StashStatus.CREATED) {
                "Target changes were not applied because caller files changed; the recorded noise stash was retained"
            } else {
                "Target changes overlap caller changes; the workspace was not modified"
            }
            return GenerationResult(
                status = GenerationStatus.BLOCKED,
                message = message,
                manifest = manifest,
                changes = changes,
                stash = stash,
                verification = verification,
                error = ErrorManifest("CALLER_CHANGES_CONFLICT", "Conflicting generated paths: ${conflicts.joinToString()}")
            )
        }

        fun planningBlocked(conflicts: List<String>): GenerationResult {
            changes = changes.copy(conflicts = conflicts)
            return GenerationResult(
                status = GenerationStatus.BLOCKED,
                message = "Protocol-main churn and target changes could not be merged safely; nothing was stashed or applied",
                manifest = manifest,
                changes = changes,
                stash = stash,
                verification = verification,
                error = ErrorManifest(
                    "GENERATION_DELTA_CONFLICT",
                    "Conflicting H/M/T generated paths: ${conflicts.joinToString()}",
                ),
            )
        }

        fun failure(code: String, message: String): GenerationResult = GenerationResult(
            status = GenerationStatus.FAILED,
            message = "Protocol model generation failed safely",
            manifest = manifest,
            changes = changes,
            stash = stash,
            verification = verification,
            error = ErrorManifest(code, message),
        )

        fun result(status: GenerationStatus, message: String): GenerationResult = GenerationResult(
            status = status,
            message = message,
            manifest = manifest,
            changes = changes,
            stash = stash,
            verification = verification,
        )
    }

    companion object {
        private const val GENERATOR_PATH = "tools/api2-model/generate.kts"
        private const val VERIFY_TASK = ":socar-android-api2-model:compileDebugKotlin"
        private val PBANDK_TREE_ENTRY = Regex("160000 commit ([0-9a-f]{40,64})\\ttools/pbandk")
        private val TOOL_TIMEOUT: Duration = Duration.ofSeconds(30)
        private val GENERATION_TIMEOUT: Duration = Duration.ofMinutes(20)
        private val VERIFICATION_TIMEOUT: Duration = Duration.ofMinutes(30)
    }
}
