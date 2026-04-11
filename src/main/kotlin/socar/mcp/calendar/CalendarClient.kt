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
