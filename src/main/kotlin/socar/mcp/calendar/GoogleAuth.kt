package socar.mcp.calendar

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File
import java.net.InetSocketAddress
import java.net.URI
import java.net.URLEncoder
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.awt.Desktop
import com.sun.net.httpserver.HttpServer
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

private val CLIENT_ID = System.getenv("GOOGLE_CLIENT_ID")
    ?: error("GOOGLE_CLIENT_ID 환경변수가 설정되지 않았습니다.")
private val CLIENT_SECRET = System.getenv("GOOGLE_CLIENT_SECRET")
    ?: error("GOOGLE_CLIENT_SECRET 환경변수가 설정되지 않았습니다.")
private const val REDIRECT_URI = "http://localhost:9876/callback"
private const val SCOPE = "https://www.googleapis.com/auth/calendar.readonly"
private const val AUTH_URL = "https://accounts.google.com/o/oauth2/v2/auth"
private const val TOKEN_URL = "https://oauth2.googleapis.com/token"

private val json = Json { ignoreUnknownKeys = true }
private val httpClient = HttpClient.newHttpClient()

@Serializable
data class TokenData(
    @SerialName("access_token") val accessToken: String,
    @SerialName("refresh_token") val refreshToken: String? = null,
    @SerialName("expires_in") val expiresIn: Long = 3600,
    @SerialName("token_type") val tokenType: String = "Bearer"
)

@Serializable
data class StoredToken(
    @SerialName("access_token") val accessToken: String,
    @SerialName("refresh_token") val refreshToken: String,
    @SerialName("expires_at") val expiresAt: Long
)

class GoogleAuth {
    private val tokenFile = File(System.getProperty("user.home"), ".calendar-mcp/token.json")
    private var currentToken: StoredToken? = null

    /**
     * MCP transport 연결 전에 호출하여 인증을 미리 완료한다.
     * 토큰이 없으면 브라우저를 열어 OAuth 인증을 진행한다.
     */
    fun ensureAuthenticated() {
        getAccessToken()
    }

    fun getAccessToken(): String {
        val token = currentToken ?: loadToken()
        if (token != null && token.expiresAt > System.currentTimeMillis() + 60_000) {
            return token.accessToken
        }
        if (token != null) {
            return refreshAccessToken(token.refreshToken)
        }
        return authorize()
    }

    private fun loadToken(): StoredToken? {
        if (!tokenFile.exists()) return null
        return try {
            json.decodeFromString<StoredToken>(tokenFile.readText()).also { currentToken = it }
        } catch (e: Exception) {
            null
        }
    }

    private fun saveToken(stored: StoredToken) {
        tokenFile.parentFile.mkdirs()
        tokenFile.writeText(json.encodeToString(StoredToken.serializer(), stored))
        currentToken = stored
    }

    private fun refreshAccessToken(refreshToken: String): String {
        val body = listOf(
            "client_id" to CLIENT_ID,
            "client_secret" to CLIENT_SECRET,
            "refresh_token" to refreshToken,
            "grant_type" to "refresh_token"
        ).formEncode()

        val request = HttpRequest.newBuilder()
            .uri(URI.create(TOKEN_URL))
            .header("Content-Type", "application/x-www-form-urlencoded")
            .POST(HttpRequest.BodyPublishers.ofString(body))
            .build()

        val response = httpClient.send(request, HttpResponse.BodyHandlers.ofString())
        val tokenData = json.decodeFromString<TokenData>(response.body())
        val stored = StoredToken(
            accessToken = tokenData.accessToken,
            refreshToken = refreshToken,
            expiresAt = System.currentTimeMillis() + tokenData.expiresIn * 1000
        )
        saveToken(stored)
        return stored.accessToken
    }

    private fun authorize(): String {
        val latch = CountDownLatch(1)
        var authCode: String? = null

        val server = HttpServer.create(InetSocketAddress(9876), 0)
        server.createContext("/callback") { exchange ->
            val query = exchange.requestURI.query ?: ""
            val params = query.split("&").associate {
                val (k, v) = it.split("=", limit = 2)
                k to v
            }
            authCode = params["code"]

            val html = "<html><body><h2>Calendar MCP 인증 완료!</h2><p>이 창을 닫아도 됩니다.</p></body></html>"
            exchange.sendResponseHeaders(200, html.toByteArray().size.toLong())
            exchange.responseBody.use { it.write(html.toByteArray()) }
            latch.countDown()
        }
        server.start()

        val authUrl = "$AUTH_URL?" + listOf(
            "client_id" to CLIENT_ID,
            "redirect_uri" to REDIRECT_URI,
            "response_type" to "code",
            "scope" to SCOPE,
            "access_type" to "offline",
            "prompt" to "consent"
        ).formEncode()

        System.err.println("브라우저에서 Google 로그인을 진행해주세요...")
        if (!openBrowser(authUrl)) {
            System.err.println("브라우저를 자동으로 열 수 없습니다. 아래 URL을 직접 열어주세요:")
            System.err.println(authUrl)
        }

        latch.await(120, TimeUnit.SECONDS)
        server.stop(0)

        val code = authCode ?: error("인증 시간이 초과되었습니다. 다시 시도해주세요.")
        return exchangeCodeForToken(code)
    }

    private fun exchangeCodeForToken(code: String): String {
        val body = listOf(
            "client_id" to CLIENT_ID,
            "client_secret" to CLIENT_SECRET,
            "code" to code,
            "redirect_uri" to REDIRECT_URI,
            "grant_type" to "authorization_code"
        ).formEncode()

        val request = HttpRequest.newBuilder()
            .uri(URI.create(TOKEN_URL))
            .header("Content-Type", "application/x-www-form-urlencoded")
            .POST(HttpRequest.BodyPublishers.ofString(body))
            .build()

        val response = httpClient.send(request, HttpResponse.BodyHandlers.ofString())
        val tokenData = json.decodeFromString<TokenData>(response.body())
        val stored = StoredToken(
            accessToken = tokenData.accessToken,
            refreshToken = tokenData.refreshToken ?: error("refresh_token을 받지 못했습니다."),
            expiresAt = System.currentTimeMillis() + tokenData.expiresIn * 1000
        )
        saveToken(stored)
        return stored.accessToken
    }
}

private fun openBrowser(url: String): Boolean {
    val os = System.getProperty("os.name").lowercase()
    return try {
        when {
            os.contains("mac") -> ProcessBuilder("open", url).start()
            os.contains("linux") -> ProcessBuilder("xdg-open", url).start()
            os.contains("win") -> ProcessBuilder("rundll32", "url.dll,FileProtocolHandler", url).start()
            else -> {
                if (Desktop.isDesktopSupported()) {
                    Desktop.getDesktop().browse(URI.create(url))
                } else {
                    return false
                }
            }
        }
        true
    } catch (e: Exception) {
        false
    }
}

private fun List<Pair<String, String>>.formEncode(): String =
    joinToString("&") { (k, v) ->
        "${URLEncoder.encode(k, Charsets.UTF_8)}=${URLEncoder.encode(v, Charsets.UTF_8)}"
    }
