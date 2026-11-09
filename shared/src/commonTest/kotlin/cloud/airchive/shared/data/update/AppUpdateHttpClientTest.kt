package cloud.airchive.shared.data.update

import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockRequestHandleScope
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.HttpRequestData
import io.ktor.client.request.HttpResponseData
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.io.IOException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse

class AppUpdateHttpClientTest {
    private val validManifest = """
        {
          "schemaVersion": 1,
          "channel": "stable",
          "packageName": "cloud.airchive.personal",
          "versionCode": 9,
          "versionName": "0.3.0",
          "commit": "${"a".repeat(40)}",
          "publishedAt": "2026-11-07T00:00:00Z",
          "releaseNotes": "Notes",
          "apk": {
            "url": "https://github.com/example/app.apk",
            "sha256": "${"b".repeat(64)}",
            "size": 1000,
            "signingCertSha256": "${"c".repeat(64)}"
          }
        }
    """.trimIndent()

    @Test
    fun parsesFeedAndSendsOnlyUpdaterHeaders() = runBlocking {
        val requests = mutableListOf<HttpRequestData>()
        val http = client(requests) { request ->
            respond(validManifest, HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json"))
        }

        val manifest = http.fetchManifest(FEED_URL)

        assertEquals(9L, manifest.versionCode)
        assertEquals(1, requests.size)
        assertEquals("Airchive/0.3.0", requests.single().headers[HttpHeaders.UserAgent])
        assertFalse(requests.single().headers.contains(HttpHeaders.Authorization))
        assertFalse(requests.single().headers.contains(HttpHeaders.Cookie))
        http.close()
    }

    @Test
    fun rejectsRedirectsToUnapprovedHostsAndPlainHttpBeforeRequestingThem() = runBlocking {
        listOf("https://evil.example/update.json", "http://github.com/update.json").forEach { location ->
            val requests = mutableListOf<HttpRequestData>()
            val http = client(requests) {
                respond("", HttpStatusCode.Found, headersOf(HttpHeaders.Location, location))
            }

            assertFailsWith<AppUpdateHttpException.DisallowedUrl> { http.fetchManifest(FEED_URL) }
            assertEquals(1, requests.size)
            http.close()
        }
    }

    @Test
    fun followsAtMostFiveRedirects() = runBlocking {
        val requests = mutableListOf<HttpRequestData>()
        val http = client(requests) { request ->
            val index = request.url.encodedPath.substringAfterLast('/').removePrefix("redirect-").toInt()
            respond("", HttpStatusCode.Found, headersOf(HttpHeaders.Location, "/redirect-${index + 1}"))
        }

        assertFailsWith<AppUpdateHttpException.RedirectLimitExceeded> { http.fetchManifest("$FEED_URL/redirect-0") }
        assertEquals(6, requests.size)
        http.close()
    }

    @Test
    fun rejectsManifestLargerThan64Kib() = runBlocking {
        val http = client(mutableListOf()) {
            respond("x".repeat(AppUpdateHttpClient.MAX_MANIFEST_BYTES + 100), HttpStatusCode.OK)
        }

        assertFailsWith<AppUpdateHttpException.ManifestTooLarge> { http.fetchManifest(FEED_URL) }
        http.close()
    }

    @Test
    fun reportsHttp404() = runBlocking {
        val http = client(mutableListOf()) { respond("missing", HttpStatusCode.NotFound) }

        assertEquals(404, assertFailsWith<AppUpdateHttpException.HttpStatus> { http.fetchManifest(FEED_URL) }.statusCode)
        http.close()
    }

    @Test
    fun propagatesOfflineFailure() = runBlocking {
        val http = client(mutableListOf()) { throw IOException("synthetic offline fixture") }

        assertFailsWith<IOException> { http.fetchManifest(FEED_URL) }
        http.close()
    }

    @Test
    fun requestTimeoutBoundsSlowFeed() = runBlocking {
        val engine = MockEngine {
            delay(100)
            respond(validManifest, HttpStatusCode.OK)
        }
        val http = AppUpdateHttpClient(
            engine = engine,
            userAgent = "Airchive/0.3.0",
            timeouts = AppUpdateHttpTimeouts(connectTimeoutMillis = 10, requestTimeoutMillis = 10, socketTimeoutMillis = 10),
        )

        assertFailsWith<Exception> { http.fetchManifest(FEED_URL) }
        http.close()
    }

    private fun client(
        requests: MutableList<HttpRequestData>,
        handler: suspend MockRequestHandleScope.(HttpRequestData) -> HttpResponseData,
    ): AppUpdateHttpClient {
        val engine = MockEngine { request ->
            requests += request
            handler(request)
        }
        return AppUpdateHttpClient(engine, "Airchive/0.3.0")
    }

    companion object {
        private const val FEED_URL = "https://github.com/example/update.json"
    }
}
