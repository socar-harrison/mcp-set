package socar.mcp.calendar

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
import java.io.PrintStream

fun main() {
    // Save real stdout before any library initialization pollutes it
    val realStdout = System.out
    // Redirect stdout to stderr during initialization to prevent
    // kotlin-logging init message from corrupting MCP JSON-RPC stream
    System.setOut(PrintStream(System.err, true))

    val auth = GoogleAuth()

    // MCP transport 연결 전에 인증을 먼저 완료
    System.err.println("[google-calendar-mcp] 인증 상태 확인 중...")
    auth.ensureAuthenticated()
    System.err.println("[google-calendar-mcp] 인증 완료! MCP 서버를 시작합니다.")

    val server = Server(
        serverInfo = Implementation(name = "google-calendar-mcp", version = "1.0.0"),
        options = ServerOptions(
            capabilities = ServerCapabilities(
                tools = ServerCapabilities.Tools(listChanged = false)
            )
        )
    )

    val client = CalendarClient(auth)
    registerTools(server, client)

    // Restore real stdout for MCP transport
    System.setOut(realStdout)

    runBlocking {
        val transport = StdioServerTransport(
            inputStream = System.`in`.asSource().buffered(),
            outputStream = realStdout.asSink().buffered()
        )

        val session = server.createSession(transport)

        val closeJob = Job()
        session.onClose { closeJob.complete() }
        closeJob.join()
    }
}
