package socar.mcp.calendar

import io.modelcontextprotocol.kotlin.sdk.server.Server
import io.modelcontextprotocol.kotlin.sdk.types.CallToolResult
import io.modelcontextprotocol.kotlin.sdk.types.TextContent
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive

fun registerTools(server: Server, client: CalendarClient) {

    server.addTool(
        name = "list_calendars",
        description = "접근 가능한 Google 캘린더 목록을 조회합니다."
    ) {
        val result = client.listCalendars()
        CallToolResult(content = listOf(TextContent(result)))
    }

    server.addTool(
        name = "get_deploy_schedule",
        description = "SOCAR 앱 배포 캘린더에서 다가오는 일정을 조회합니다. daysAhead 파라미터로 조회 기간(일)을 설정할 수 있습니다. (기본: 30일)"
    ) { request ->
        val daysAhead = request.arguments?.get("daysAhead")?.jsonPrimitive?.intOrNull ?: 30
        val result = client.getEvents(DEPLOY_CALENDAR_ID, daysAhead)
        CallToolResult(content = listOf(TextContent(result)))
    }

    server.addTool(
        name = "search_events",
        description = "Google 캘린더에서 키워드로 이벤트를 검색합니다. query(검색어), calendarId(캘린더 ID, 기본: 배포 캘린더), daysAhead(검색 기간, 기본: 30일) 파라미터를 사용할 수 있습니다."
    ) { request ->
        val query = request.arguments?.get("query")?.jsonPrimitive?.content ?: ""
        val calendarId = request.arguments?.get("calendarId")?.jsonPrimitive?.content ?: DEPLOY_CALENDAR_ID
        val daysAhead = request.arguments?.get("daysAhead")?.jsonPrimitive?.intOrNull ?: 30
        val result = client.getEvents(calendarId, daysAhead, query)
        CallToolResult(content = listOf(TextContent(result)))
    }
}
