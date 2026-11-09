package cloud.airchive.shared.data.update

import io.ktor.client.HttpClient
import io.ktor.client.engine.HttpClientEngine
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsChannel
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.URLBuilder
import io.ktor.http.Url
import io.ktor.http.isSuccess
import io.ktor.http.takeFrom
import io.ktor.utils.io.ByteReadChannel
import io.ktor.utils.io.readAvailable

data class AppUpdateHttpTimeouts(
    val connectTimeoutMillis: Long = AppUpdateHttpClient.CONNECT_TIMEOUT_MILLIS,
    val requestTimeoutMillis: Long = AppUpdateHttpClient.REQUEST_TIMEOUT_MILLIS,
    val socketTimeoutMillis: Long = AppUpdateHttpClient.SOCKET_TIMEOUT_MILLIS,
)

sealed class AppUpdateHttpException(message: String) : Exception(message) {
    class DisallowedUrl : AppUpdateHttpException("The update URL must use HTTPS on an approved host.")
    class RedirectLimitExceeded : AppUpdateHttpException("The update server exceeded the redirect limit.")
    class RedirectMissingLocation : AppUpdateHttpException("The update server returned a redirect without a destination.")
    class ManifestTooLarge : AppUpdateHttpException("The update manifest exceeds 64 KiB.")
    class HttpStatus(val statusCode: Int) : AppUpdateHttpException("The update server returned HTTP $statusCode.")
}

/** Credential-free HTTP boundary for update manifests and APK assets. */
class AppUpdateHttpClient(
    engine: HttpClientEngine,
    private val userAgent: String,
    timeouts: AppUpdateHttpTimeouts = AppUpdateHttpTimeouts(),
) {
    private val client = HttpClient(engine) {
        followRedirects = false
        expectSuccess = false
        install(HttpTimeout) {
            connectTimeoutMillis = timeouts.connectTimeoutMillis
            requestTimeoutMillis = timeouts.requestTimeoutMillis
            socketTimeoutMillis = timeouts.socketTimeoutMillis
        }
    }

    init {
        require(userAgent.startsWith("Airchive/") && userAgent.length > "Airchive/".length) {
            "Update User-Agent must use the Airchive/<versionName> format."
        }
        require(timeouts.connectTimeoutMillis > 0L && timeouts.requestTimeoutMillis > 0L && timeouts.socketTimeoutMillis > 0L) {
            "Update HTTP timeouts must be positive."
        }
    }

    /** Fetches and strictly parses a v1 manifest after enforcing the 64 KiB cap. */
    suspend fun fetchManifest(url: String): UpdateManifest {
        val response = get(url)
        if (!response.status.isSuccess()) throw AppUpdateHttpException.HttpStatus(response.status.value)
        val bytes = readLimited(response.bodyAsChannel(), MAX_MANIFEST_BYTES)
        return parseUpdateManifest(bytes.decodeToString())
    }

    /** Returns an approved successful or error response for streaming callers. */
    suspend fun get(url: String): HttpResponse {
        var destination = try {
            Url(url)
        } catch (_: IllegalArgumentException) {
            throw AppUpdateHttpException.DisallowedUrl()
        }
        validateDestination(destination)
        var redirectsFollowed = 0

        while (true) {
            val response = client.get(destination) {
                header(HttpHeaders.UserAgent, userAgent)
                header(HttpHeaders.Accept, "application/json, application/vnd.android.package-archive, */*")
            }
            if (!response.status.isRedirect()) return response

            val location = response.headers[HttpHeaders.Location]
                ?: throw AppUpdateHttpException.RedirectMissingLocation()
            if (redirectsFollowed >= MAX_REDIRECTS) throw AppUpdateHttpException.RedirectLimitExceeded()

            destination = try {
                URLBuilder(destination).apply {
                    parameters.clear()
                    takeFrom(location)
                }.build()
            } catch (_: IllegalArgumentException) {
                throw AppUpdateHttpException.DisallowedUrl()
            } catch (_: IllegalStateException) {
                throw AppUpdateHttpException.DisallowedUrl()
            }
            validateDestination(destination)
            redirectsFollowed += 1
        }
    }

    fun close() = client.close()

    private fun validateDestination(url: Url) {
        val allowedHost = url.host.equals(ALLOWED_GITHUB_HOST, ignoreCase = true) ||
            url.host.endsWith(ALLOWED_GITHUB_CONTENT_SUFFIX, ignoreCase = true)
        if (url.protocol.name != "https" || !allowedHost || url.port != HTTPS_PORT || url.user != null || url.password != null) {
            throw AppUpdateHttpException.DisallowedUrl()
        }
    }

    private suspend fun readLimited(channel: ByteReadChannel, maximumBytes: Int): ByteArray {
        val buffer = ByteArray(maximumBytes + 1)
        var count = 0
        while (count < buffer.size) {
            val read = channel.readAvailable(buffer, count, buffer.size - count)
            if (read < 0) break
            if (read > 0) count += read
        }
        if (count > maximumBytes) throw AppUpdateHttpException.ManifestTooLarge()
        return buffer.copyOf(count)
    }

    private fun HttpStatusCode.isRedirect(): Boolean = value in REDIRECT_STATUS_CODES

    companion object {
        const val CONNECT_TIMEOUT_MILLIS = 15_000L
        const val REQUEST_TIMEOUT_MILLIS = 30_000L
        const val SOCKET_TIMEOUT_MILLIS = 30_000L
        const val MAX_MANIFEST_BYTES = 64 * 1024
        const val MAX_REDIRECTS = 5
        private const val HTTPS_PORT = 443
        private const val ALLOWED_GITHUB_HOST = "github.com"
        private const val ALLOWED_GITHUB_CONTENT_SUFFIX = ".githubusercontent.com"
        private val REDIRECT_STATUS_CODES = setOf(300, 301, 302, 303, 307, 308)
    }
}
