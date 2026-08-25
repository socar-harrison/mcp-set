package socar.mcp.protocol.core

import kotlinx.serialization.Serializable

@Serializable
data class GenerationRequest(
    val workspacePath: String,
    val protocolBranch: String,
    val dryRun: Boolean = false,
    val verify: Boolean = true,
)

@Serializable
data class GenerationResult(
    val status: GenerationStatus,
    val message: String,
    val manifest: GenerationManifest? = null,
    val changes: ChangeManifest = ChangeManifest(),
    val stash: StashManifest = StashManifest(),
    val verification: VerificationManifest = VerificationManifest(),
    val cleanup: CleanupManifest = CleanupManifest(),
    val error: ErrorManifest? = null,
)

@Serializable
enum class GenerationStatus {
    APPLIED,
    NO_TARGET_CHANGES,
    DRY_RUN,
    BLOCKED,
    FAILED,
}

@Serializable
data class GenerationManifest(
    val workspacePath: String,
    val protocolRemote: String,
    val protocolBranch: String,
    val androidHeadSha: String,
    val protocolMainSha: String,
    val protocolTargetSha: String,
    val pbandkSha: String,
    val generatorSha256: String,
    val pbandkArtifactSha256: String,
    val tools: List<ToolVersion>,
)

@Serializable
data class ToolVersion(
    val name: String,
    val version: String,
)

@Serializable
data class ChangeManifest(
    val noise: List<FileChange> = emptyList(),
    val target: List<FileChange> = emptyList(),
    val planned: List<FileChange> = emptyList(),
    val appliedPaths: List<String> = emptyList(),
    val conflicts: List<String> = emptyList(),
)

@Serializable
data class ChangeClassification(
    val noise: List<FileChange>,
    val target: List<FileChange>,
)

@Serializable
data class FileChange(
    val path: String,
    val kind: FileChangeKind,
)

@Serializable
enum class FileChangeKind {
    ADDED,
    MODIFIED,
    DELETED,
}

@Serializable
data class StashManifest(
    val status: StashStatus = StashStatus.NOT_CREATED,
    val name: String? = null,
    val oid: String? = null,
)

@Serializable
enum class StashStatus {
    CREATED,
    NOT_NEEDED,
    NOT_CREATED,
}

@Serializable
data class VerificationManifest(
    val status: VerificationStatus = VerificationStatus.NOT_RUN,
    val command: List<String> = emptyList(),
    val summary: String? = null,
)

@Serializable
enum class VerificationStatus {
    PASSED,
    FAILED,
    SKIPPED,
    NOT_RUN,
}

@Serializable
data class CleanupManifest(
    val temporaryDirectoryRemoved: Boolean = true,
    val androidWorktreesRemoved: Boolean = true,
    val warnings: List<String> = emptyList(),
)

@Serializable
data class ErrorManifest(
    val code: String,
    val message: String,
)
