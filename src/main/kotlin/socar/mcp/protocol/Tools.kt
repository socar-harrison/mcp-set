package socar.mcp.protocol

import io.modelcontextprotocol.kotlin.sdk.server.Server
import io.modelcontextprotocol.kotlin.sdk.types.CallToolResult
import io.modelcontextprotocol.kotlin.sdk.types.TextContent
import io.modelcontextprotocol.kotlin.sdk.types.ToolAnnotations
import io.modelcontextprotocol.kotlin.sdk.types.ToolSchema
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import socar.mcp.protocol.core.GenerationRequest
import socar.mcp.protocol.core.GenerationResult
import socar.mcp.protocol.core.GenerationStatus
import socar.mcp.protocol.core.ProtocolCodegenService
import java.nio.file.Path

private val toolJson = Json {
    encodeDefaults = true
    explicitNulls = false
    prettyPrint = true
}

private val generationInputSchema = ToolSchema(
    properties = buildJsonObject {
        putJsonObject("protocolBranch") {
            put("type", "string")
            put("minLength", 1)
            put(
                "description",
                "Required socar-inc/socar-protocol branch name, for example feature/ABC-123. 브랜치 이름을 그대로 입력합니다.",
            )
        }
        putJsonObject("workspacePath") {
            put("type", "string")
            put(
                "description",
                "Optional absolute socar-android-library path. It must match the server's configured project root. Defaults to SOCAR_ANDROID_WORKSPACE, CLAUDE_PROJECT_DIR, then MCP process cwd.",
            )
        }
        putJsonObject("dryRun") {
            put("type", "boolean")
            put("default", false)
            put("description", "Generate and classify changes without creating a stash or modifying the workspace.")
        }
        putJsonObject("verify") {
            put("type", "boolean")
            put("default", true)
            put("description", "Verify the planned target-only API2 model state in isolation before applying it.")
        }
    },
    required = listOf("protocolBranch"),
)

internal fun registerProtocolTools(server: Server, service: ProtocolCodegenService) {
    server.addTool(
        name = "generate_protocol_data_classes",
        title = "Generate SOCAR protocol data classes",
        description = """
            Use this single atomic tool when the user asks in Korean or English to generate/update API2 Kotlin data classes or run generate.kts from a socar-protocol branch. It pins main and the requested branch to commit SHAs, generates both in isolation, stashes unrelated main baseline churn, and applies only target-branch changes. It never commits, pushes, pops/drops stashes, or overwrites conflicting user work.
        """.trimIndent(),
        inputSchema = generationInputSchema,
        toolAnnotations = ToolAnnotations(
            readOnlyHint = false,
            destructiveHint = true,
            idempotentHint = false,
            openWorldHint = true,
        ),
    ) { request ->
        val arguments = request.arguments
        val protocolBranch = arguments
            ?.get("protocolBranch")
            ?.jsonPrimitive
            ?.contentOrNull
            ?.trim()
            .orEmpty()

        if (protocolBranch.isEmpty()) {
            return@addTool toolError(
                code = "INVALID_ARGUMENT",
                message = "protocolBranch is required. socar-protocol 브랜치 이름을 입력해 주세요.",
            )
        }

        val requestedWorkspace = arguments
            ?.get("workspacePath")
            ?.jsonPrimitive
            ?.contentOrNull
            ?.trim()
            ?.takeIf(String::isNotEmpty)
        val configuredWorkspace = System.getenv("SOCAR_ANDROID_WORKSPACE")?.takeIf(String::isNotBlank)
            ?: System.getenv("CLAUDE_PROJECT_DIR")?.takeIf(String::isNotBlank)
            ?: System.getProperty("user.dir")

        val dryRun = arguments?.get("dryRun")?.jsonPrimitive?.booleanOrNull ?: false
        val verify = arguments?.get("verify")?.jsonPrimitive?.booleanOrNull ?: true

        try {
            val configuredRoot = Path.of(configuredWorkspace).toRealPath()
            val requestedRoot = requestedWorkspace?.let { Path.of(it).toRealPath() }
            require(requestedRoot == null || requestedRoot == configuredRoot) {
                "workspacePath must match the MCP server's configured project root: $configuredRoot"
            }
            val result = service.generate(
                GenerationRequest(
                    workspacePath = configuredRoot.toString(),
                    protocolBranch = protocolBranch,
                    dryRun = dryRun,
                    verify = verify,
                ),
            )
            val structured = toolJson.encodeToJsonElement(GenerationResult.serializer(), result).jsonObject
            CallToolResult(
                content = listOf(TextContent(toolJson.encodeToString(result))),
                isError = result.status == GenerationStatus.BLOCKED || result.status == GenerationStatus.FAILED,
                structuredContent = structured,
            )
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            System.err.println("[$SERVER_NAME] ${failure::class.simpleName}: ${failure.message}")
            toolError(
                code = failure::class.simpleName ?: "GENERATION_FAILED",
                message = failure.message ?: "Protocol data class generation failed.",
            )
        }
    }
}

private fun toolError(code: String, message: String): CallToolResult {
    val structured = buildJsonObject {
        put("status", "error")
        put("code", code)
        put("message", message)
    }
    return CallToolResult(
        content = listOf(TextContent("$code: $message")),
        isError = true,
        structuredContent = structured,
    )
}
