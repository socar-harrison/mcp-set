package socar.mcp.protocol

import io.modelcontextprotocol.kotlin.sdk.server.Server
import io.modelcontextprotocol.kotlin.sdk.server.ServerOptions
import io.modelcontextprotocol.kotlin.sdk.server.StdioServerTransport
import io.modelcontextprotocol.kotlin.sdk.types.Implementation
import io.modelcontextprotocol.kotlin.sdk.types.ServerCapabilities
import kotlinx.coroutines.Job
import kotlinx.coroutines.runBlocking
import kotlinx.io.asSink
import kotlinx.io.asSource
import kotlinx.io.buffered
import socar.mcp.protocol.core.ProtocolCodegenService
import java.io.PrintStream

internal const val SERVER_NAME = "socar-protocol-codegen"
internal const val SERVER_VERSION = "2.0.0"

private val SERVER_INSTRUCTIONS = """
    SOCAR protocol branch / API2 data class generation server. 사용자가 socar-protocol 브랜치로 API2 모델, data class, 데이터 클래스를 생성하거나 generate.kts 실행을 요청하면 generate_protocol_data_classes 도구를 사용한다. 이 서버는 브랜치를 immutable commit SHA로 고정하고 격리된 작업공간에서 main/target을 생성한 뒤 target 전용 변경만 반영한다. unrelated generated churn은 이름 있는 stash로 보관한다. 사용자의 기존 수정, index, untracked 파일, submodule은 변경하지 않으며 충돌 시 적용 전에 실패한다. commit, push, stash pop/drop은 절대 수행하지 않는다. protocolBranch가 없으면 사용자에게 묻는다.
""".trimIndent()

fun main() {
    // MCP JSON-RPC alone may use stdout. Route all process/library logging to stderr.
    val protocolStdout = System.out
    System.setOut(PrintStream(System.err, true))

    val server = Server(
        serverInfo = Implementation(name = SERVER_NAME, version = SERVER_VERSION),
        options = ServerOptions(
            capabilities = ServerCapabilities(
                tools = ServerCapabilities.Tools(listChanged = false),
            ),
        ),
        instructions = SERVER_INSTRUCTIONS,
    )
    registerProtocolTools(server, ProtocolCodegenService())

    System.setOut(protocolStdout)

    runBlocking {
        val transport = StdioServerTransport(
            inputStream = System.`in`.asSource().buffered(),
            outputStream = protocolStdout.asSink().buffered(),
        )
        val session = server.createSession(transport)
        val closeJob = Job()
        session.onClose { closeJob.complete() }
        closeJob.join()
    }
}
