package socar.mcp.protocol

import io.modelcontextprotocol.kotlin.sdk.server.Server
import io.modelcontextprotocol.kotlin.sdk.server.ServerOptions
import io.modelcontextprotocol.kotlin.sdk.types.Implementation
import io.modelcontextprotocol.kotlin.sdk.types.ServerCapabilities
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import socar.mcp.protocol.core.ProtocolCodegenService

class ToolsContractTest {
    @Test
    fun `publishes one narrowly scoped mutating tool with an explicit branch schema`() {
        val server = Server(
            serverInfo = Implementation("test", "test"),
            options = ServerOptions(
                ServerCapabilities(tools = ServerCapabilities.Tools(listChanged = false)),
            ),
        )

        registerProtocolTools(server, ProtocolCodegenService())

        val registered = server.tools.values.single()
        val tool = registered.tool
        assertEquals("generate_protocol_data_classes", tool.name)
        assertEquals(listOf("protocolBranch"), tool.inputSchema.required)
        assertNotNull(tool.inputSchema.properties?.get("protocolBranch"))
        assertEquals(false, tool.annotations?.readOnlyHint)
        assertEquals(true, tool.annotations?.destructiveHint)
        assertFalse(tool.description.isNullOrBlank())
        assertTrue(tool.description!!.contains("socar-protocol"))
    }
}
