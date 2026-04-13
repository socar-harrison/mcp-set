package socar.mcp.calendar

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.net.URI
import java.net.URLEncoder
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit

const val DEPLOY_CALENDAR_ID = "c_e1si5hdm55i3he7rn88iunk820@group.calendar.google.com"

val HEALING_ROOM_CALENDARS = mapOf(
    4 to "c_944f0dd6hup5t8pun018qhho7o@group.calendar.google.com",
    5 to "c_bkekuaeipntt2jjmrkekva9p7c@group.calendar.google.com"
)

private val json = Json { ignoreUnknownKeys = true }
private val httpClient = HttpClient.newHttpClient()
private val KST = ZoneId.of("Asia/Seoul")
private val KST_FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm").withZone(KST)

@Serializable
data class CalendarListResponse(val items: List<CalendarEntry> = emptyList())

@Serializable
data class CalendarEntry(
    val id: String = "",
    val summary: String = "",
    val description: String = "",
    val accessRole: String = ""
)

@Serializable
data class EventsResponse(val items: List<EventEntry> = emptyList())

@Serializable
data class EventEntry(
    val id: String = "",
    val summary: String = "",
    val description: String = "",
    val location: String = "",
    val start: EventDateTime? = null,
    val end: EventDateTime? = null,
    val status: String = "",
    @SerialName("htmlLink") val htmlLink: String = ""
)

@Serializable
data class EventDateTime(
    val dateTime: String? = null,
    val date: String? = null,
    val timeZone: String? = null
)

class CalendarClient(private val auth: GoogleAuth) {

    fun listCalendars(): String {
        val token = auth.getAccessToken()
        val request = HttpRequest.newBuilder()
            .uri(URI.create("https://www.googleapis.com/calendar/v3/users/me/calendarList"))
            .header("Authorization", "Bearer $token")
            .GET()
            .build()

        val response = httpClient.send(request, HttpResponse.BodyHandlers.ofString())
        if (response.statusCode() != 200) return "캘린더 목록 조회 실패: ${response.body()}"

        val calendars = json.decodeFromString<CalendarListResponse>(response.body())
        if (calendars.items.isEmpty()) return "접근 가능한 캘린더가 없습니다."

        return calendars.items.joinToString("\n\n") { cal ->
            buildString {
                appendLine("📅 ${cal.summary}")
                appendLine("   ID: ${cal.id}")
                if (cal.description.isNotBlank()) appendLine("   설명: ${cal.description}")
                appendLine("   권한: ${cal.accessRole}")
            }
        }
    }

    fun getEvents(calendarId: String, daysAhead: Int, query: String? = null): String {
        val token = auth.getAccessToken()
        val now = Instant.now()
        val until = now.plus(daysAhead.toLong(), ChronoUnit.DAYS)

        val params = mutableListOf(
            "timeMin" to now.toString(),
            "timeMax" to until.toString(),
            "singleEvents" to "true",
            "orderBy" to "startTime",
            "maxResults" to "50"
        )
        if (!query.isNullOrBlank()) {
            params.add("q" to query)
        }

        val queryString = params.joinToString("&") { (k, v) ->
            "$k=${URLEncoder.encode(v, Charsets.UTF_8)}"
        }
        val encodedCalendarId = URLEncoder.encode(calendarId, Charsets.UTF_8)
        val url = "https://www.googleapis.com/calendar/v3/calendars/$encodedCalendarId/events?$queryString"

        val request = HttpRequest.newBuilder()
            .uri(URI.create(url))
            .header("Authorization", "Bearer $token")
            .GET()
            .build()

        val response = httpClient.send(request, HttpResponse.BodyHandlers.ofString())
        if (response.statusCode() != 200) return "일정 조회 실패: ${response.body()}"

        val events = json.decodeFromString<EventsResponse>(response.body())
        if (events.items.isEmpty()) return "해당 기간에 일정이 없습니다."

        return events.items.joinToString("\n\n") { event ->
            formatEvent(event)
        }
    }

    fun getAllHealingRoomAvailability(): String {
        val today = Instant.now().atZone(KST).toLocalDate()
        val now = Instant.now()

        return buildString {
            HEALING_ROOM_CALENDARS.forEach { (floor, calendarId) ->
                appendLine("🏢 ${floor}층 힐링룸")
                val (bookedSlots, freeSlots) = getTodaySlots(calendarId, today, now)
                if (bookedSlots.isEmpty()) {
                    appendLine("   예약 없음")
                } else {
                    bookedSlots.forEach { appendLine("   🔴 $it") }
                }
                appendLine("   🟢 남은 시간:")
                if (freeSlots.isEmpty()) {
                    appendLine("      남은 시간이 없습니다.")
                } else {
                    freeSlots.forEach { appendLine("      $it") }
                }
                appendLine()
            }
        }
    }

    private fun getTodaySlots(calendarId: String, today: java.time.LocalDate, now: Instant): Pair<List<String>, List<String>> {
        val token = auth.getAccessToken()
        val dayStart = today.atStartOfDay(KST).toInstant()
        val dayEnd = today.plusDays(1).atStartOfDay(KST).toInstant()

        val params = listOf(
            "timeMin" to dayStart.toString(),
            "timeMax" to dayEnd.toString(),
            "singleEvents" to "true",
            "orderBy" to "startTime",
            "maxResults" to "50"
        )
        val queryString = params.joinToString("&") { (k, v) ->
            "$k=${URLEncoder.encode(v, Charsets.UTF_8)}"
        }
        val encodedCalendarId = URLEncoder.encode(calendarId, Charsets.UTF_8)
        val url = "https://www.googleapis.com/calendar/v3/calendars/$encodedCalendarId/events?$queryString"

        val request = HttpRequest.newBuilder()
            .uri(URI.create(url))
            .header("Authorization", "Bearer $token")
            .GET()
            .build()

        val response = httpClient.send(request, HttpResponse.BodyHandlers.ofString())
        if (response.statusCode() != 200) return listOf("조회 실패") to emptyList()

        val events = json.decodeFromString<EventsResponse>(response.body())
        val bookedSlots = events.items.map { event ->
            val start = formatDateTime(event.start)
            val end = formatDateTime(event.end)
            "${event.summary} ($start ~ $end)"
        }

        val bookedRanges = events.items.mapNotNull { event ->
            val start = event.start?.dateTime?.let { Instant.parse(it) } ?: return@mapNotNull null
            val end = event.end?.dateTime?.let { Instant.parse(it) } ?: return@mapNotNull null
            start to end
        }

        val slotStart = today.atTime(9, 0).atZone(KST).toInstant()
        val slotEnd = today.atTime(18, 0).atZone(KST).toInstant()
        val freeSlots = mutableListOf<String>()

        var cursor = if (now.isAfter(slotStart)) {
            val mins = now.atZone(KST).minute
            val roundedUp = if (mins % 30 == 0) mins else ((mins / 30) + 1) * 30
            today.atTime(now.atZone(KST).hour, 0).plusMinutes(roundedUp.toLong()).atZone(KST).toInstant()
        } else slotStart

        while (cursor.isBefore(slotEnd)) {
            val nextSlot = cursor.plus(30, ChronoUnit.MINUTES)
            val isBooked = bookedRanges.any { (s, e) -> cursor.isBefore(e) && nextSlot.isAfter(s) }
            if (!isBooked) {
                freeSlots.add("${KST_FORMATTER.format(cursor)} ~ ${KST_FORMATTER.format(nextSlot)}")
            }
            cursor = nextSlot
        }

        return bookedSlots to freeSlots
    }

    private fun checkConflict(calendarId: String, start: Instant, end: Instant): String? {
        val token = auth.getAccessToken()
        val params = listOf(
            "timeMin" to start.toString(),
            "timeMax" to end.toString(),
            "singleEvents" to "true",
            "maxResults" to "10"
        )
        val queryString = params.joinToString("&") { (k, v) ->
            "$k=${URLEncoder.encode(v, Charsets.UTF_8)}"
        }
        val encodedCalendarId = URLEncoder.encode(calendarId, Charsets.UTF_8)
        val url = "https://www.googleapis.com/calendar/v3/calendars/$encodedCalendarId/events?$queryString"

        val request = HttpRequest.newBuilder()
            .uri(URI.create(url))
            .header("Authorization", "Bearer $token")
            .GET()
            .build()

        val response = httpClient.send(request, HttpResponse.BodyHandlers.ofString())
        if (response.statusCode() != 200) return null

        val events = json.decodeFromString<EventsResponse>(response.body())
        val conflict = events.items.firstOrNull { event ->
            val eStart = event.start?.dateTime?.let { Instant.parse(it) } ?: return@firstOrNull false
            val eEnd = event.end?.dateTime?.let { Instant.parse(it) } ?: return@firstOrNull false
            start.isBefore(eEnd) && end.isAfter(eStart)
        }

        return conflict?.let {
            val s = formatDateTime(it.start)
            val e = formatDateTime(it.end)
            "이미 ${it.summary}님이 $s ~ $e 에 예약했습니다."
        }
    }

    fun createHealingRoomEvent(calendarId: String, nickname: String, startTime: String): String {
        val start = try {
            Instant.parse(startTime)
        } catch (e: Exception) {
            try {
                java.time.LocalDateTime.parse(startTime).atZone(KST).toInstant()
            } catch (e2: Exception) {
                return "시간 형식이 올바르지 않습니다. 예: 2026-04-13T14:00 또는 14:00"
            }
        }

        // 정시 또는 30분 시작만 허용
        val minute = start.atZone(KST).minute
        if (minute != 0 && minute != 30) {
            return "❌ 예약 불가: 정시(00분) 또는 30분에만 예약할 수 있습니다. 예: 14:00, 14:30"
        }

        val end = start.plus(30, ChronoUnit.MINUTES)

        // 충돌 체크
        val conflict = checkConflict(calendarId, start, end)
        if (conflict != null) return "❌ 예약 불가: $conflict"

        // 주 1회 제한 체크 (이번 주 월~일 전체 캘린더에서 같은 닉네임 검색)
        val weeklyConflict = checkWeeklyLimit(nickname)
        if (weeklyConflict != null) return "❌ 예약 불가: $weeklyConflict"

        val token = auth.getAccessToken()
        val body = """
            {
                "summary": "$nickname",
                "start": { "dateTime": "$start", "timeZone": "Asia/Seoul" },
                "end": { "dateTime": "$end", "timeZone": "Asia/Seoul" }
            }
        """.trimIndent()

        val encodedCalendarId = URLEncoder.encode(calendarId, Charsets.UTF_8)
        val request = HttpRequest.newBuilder()
            .uri(URI.create("https://www.googleapis.com/calendar/v3/calendars/$encodedCalendarId/events"))
            .header("Authorization", "Bearer $token")
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(body))
            .build()

        val response = httpClient.send(request, HttpResponse.BodyHandlers.ofString())
        if (response.statusCode() !in 200..299) return "일정 생성 실패: ${response.body()}"

        val startStr = KST_FORMATTER.format(start)
        val endStr = KST_FORMATTER.format(end)
        return "✅ 예약 완료!\n   이름: $nickname\n   시간: $startStr ~ $endStr"
    }

    private fun checkWeeklyLimit(nickname: String): String? {
        val today = Instant.now().atZone(KST).toLocalDate()
        val monday = today.with(java.time.DayOfWeek.MONDAY)
        val sunday = monday.plusDays(7)
        val weekStart = monday.atStartOfDay(KST).toInstant()
        val weekEnd = sunday.atStartOfDay(KST).toInstant()

        for ((floor, calendarId) in HEALING_ROOM_CALENDARS) {
            val token = auth.getAccessToken()
            val params = listOf(
                "timeMin" to weekStart.toString(),
                "timeMax" to weekEnd.toString(),
                "singleEvents" to "true",
                "q" to nickname,
                "maxResults" to "50"
            )
            val queryString = params.joinToString("&") { (k, v) ->
                "$k=${URLEncoder.encode(v, Charsets.UTF_8)}"
            }
            val encodedCalendarId = URLEncoder.encode(calendarId, Charsets.UTF_8)
            val url = "https://www.googleapis.com/calendar/v3/calendars/$encodedCalendarId/events?$queryString"

            val request = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .header("Authorization", "Bearer $token")
                .GET()
                .build()

            val response = httpClient.send(request, HttpResponse.BodyHandlers.ofString())
            if (response.statusCode() != 200) continue

            val events = json.decodeFromString<EventsResponse>(response.body())
            val existing = events.items.firstOrNull { it.summary == nickname }
            if (existing != null) {
                val date = formatDateTime(existing.start)
                return "${nickname}님은 이번 주에 이미 ${floor}층 힐링룸을 예약했습니다. ($date) 1인당 주 1회만 가능합니다."
            }
        }
        return null
    }

    private fun formatEvent(event: EventEntry): String = buildString {
        appendLine("📌 ${event.summary}")
        val startStr = formatDateTime(event.start)
        val endStr = formatDateTime(event.end)
        appendLine("   시간: $startStr ~ $endStr")
        if (event.location.isNotBlank()) appendLine("   장소: ${event.location}")
        if (event.description.isNotBlank()) {
            val desc = event.description.take(200).replace("\n", "\n   ")
            appendLine("   설명: $desc")
        }
        if (event.htmlLink.isNotBlank()) appendLine("   링크: ${event.htmlLink}")
    }

    private fun formatDateTime(dt: EventDateTime?): String {
        if (dt == null) return "미정"
        dt.dateTime?.let {
            return try {
                KST_FORMATTER.format(Instant.parse(it))
            } catch (e: Exception) {
                it
            }
        }
        return dt.date ?: "미정"
    }
}
