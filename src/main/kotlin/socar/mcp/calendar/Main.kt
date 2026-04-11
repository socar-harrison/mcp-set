package socar.mcp.calendar

import io.modelcontextprotocol.kotlin.sdk.Implementation
import io.modelcontextprotocol.kotlin.sdk.ServerCapabilities
import io.modelcontextprotocol.kotlin.sdk.server.Server
import io.modelcontextprotocol.kotlin.sdk.server.ServerOptions
import io.modelcontextprotocol.kotlin.sdk.server.StdioServerTransport
import kotlinx.coroutines.runBlocking
import kotlinx.io.asSink
import kotlinx.io.asSource
import kotlinx.io.buffered

fun main() = runBlocking {
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

    val transport = StdioServerTransport(
        System.`in`.asSource().buffered(),
        System.out.asSink().buffered()
    )
    server.connect(transport)

    // stdin이 닫힐 때까지 서버 유지
    while (System.`in`.available() >= 0) {
        kotlinx.coroutines.delay(100)
    }
}
