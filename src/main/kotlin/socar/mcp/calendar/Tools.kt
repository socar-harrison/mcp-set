package socar.mcp.calendar

import io.modelcontextprotocol.kotlin.sdk.server.Server
import io.modelcontextprotocol.kotlin.sdk.types.CallToolResult
import io.modelcontextprotocol.kotlin.sdk.types.TextContent
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId

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

    server.addTool(
        name = "get_healing_room",
        description = "4층/5층 힐링룸의 오늘 예약 현황과 남은 빈 시간을 한번에 조회합니다."
    ) {
        val result = client.getAllHealingRoomAvailability()
        CallToolResult(content = listOf(TextContent(result)))
    }

    server.addTool(
        name = "book_healing_room",
        description = "힐링룸을 예약합니다 (30분 고정, 정시/30분 시작만 가능, 1인 주 1회). floor(층수: 4 또는 5, 필수), nickname(닉네임, 필수), time(시작 시간, 필수, 예: '14:00' 또는 '14:30'). 이미 예약된 시간이거나 이번 주에 이미 예약한 사용자면 거부됩니다."
    ) { request ->
        val floor = request.arguments?.get("floor")?.jsonPrimitive?.intOrNull
            ?: return@addTool CallToolResult(content = listOf(TextContent("층수를 입력해주세요. (4 또는 5)")))
        val calendarId = HEALING_ROOM_CALENDARS[floor]
            ?: return@addTool CallToolResult(content = listOf(TextContent("${floor}층 힐링룸은 없습니다. 4층 또는 5층만 가능합니다.")))
        val nickname = request.arguments?.get("nickname")?.jsonPrimitive?.content
            ?: return@addTool CallToolResult(content = listOf(TextContent("닉네임을 입력해주세요.")))
        val timeStr = request.arguments?.get("time")?.jsonPrimitive?.content
            ?: return@addTool CallToolResult(content = listOf(TextContent("시간을 입력해주세요. 예: 14:00, 14:30")))

        val startTime = if (timeStr.length <= 5) {
            val time = LocalTime.parse(timeStr)
            LocalDate.now(ZoneId.of("Asia/Seoul")).atTime(time).atZone(ZoneId.of("Asia/Seoul")).toInstant().toString()
        } else {
            timeStr
        }

        val result = client.createHealingRoomEvent(calendarId, nickname, startTime)
        CallToolResult(content = listOf(TextContent(result)))
    }
}
