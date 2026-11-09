package cloud.airchive.shared.data.update

import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockRequestHandleScope
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.HttpRequestData
import io.ktor.client.request.HttpResponseData
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.ktor.utils.io.ByteReadChannel
import java.io.File
import java.nio.file.Files
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class AndroidAppUpdateDownloaderHostTest {

    @Test
    fun streamsApkAndReturnsItsSizeAndSha256() = runBlocking {
        val directory = temporaryDirectory()
        val httpClient = client { respond(ByteReadChannel(byteArrayOf(1, 2, 3)), HttpStatusCode.OK) }
        try {
            val result = downloader(httpClient, directory).download(manifest(size = 3L))

            assertEquals(3L, result.size)
            assertEquals("039058c6f2c0cb492c533b0a4d14ef77cc0f78abccced5287d84a1a2011cfb81", result.sha256)
            assertContentEquals(byteArrayOf(1, 2, 3), result.file.readBytes())
            assertFalse(File(directory, "updates/9.apk.part").exists())
        } finally {
            httpClient.close()
            directory.deleteRecursively()
        }
    }

    @Test
    fun abortsWhenStreamExceedsDeclaredSizeAndDeletesPartialFile() = runBlocking {
        val directory = temporaryDirectory()
        val httpClient = client {
            respond(ByteReadChannel(byteArrayOf(1, 2, 3, 4)), HttpStatusCode.OK)
        }
        try {
            val error = assertFailsWith<AppUpdateDownloadException.TooLarge> {
                downloader(httpClient, directory).download(manifest(size = 3L))
            }

            assertTrue(error.message!!.contains("exceeds"))
            assertFalse(File(directory, "updates/9.apk.part").exists())
            assertFalse(File(directory, "updates/9.apk").exists())
        } finally {
            httpClient.close()
            directory.deleteRecursively()
        }
    }

    @Test
    fun cancellationDeletesPartialFile() = runBlocking {
        val directory = temporaryDirectory()
        val responseBody = ByteReadChannel(byteArrayOf(1, 2, 3, 4, 5))
        val httpClient = client { respond(responseBody, HttpStatusCode.OK) }
        try {
            val progress = CompletableDeferred<Long>()
            val download = async {
                downloader(httpClient, directory).download(manifest(size = 6L)) { bytes ->
                    progress.complete(bytes)
                    currentCoroutineContext().cancel(kotlinx.coroutines.CancellationException("cancelled by owner"))
                }
            }

            assertEquals(5L, withTimeout(5_000L) { progress.await() })
            download.cancelAndJoin()

            assertFalse(File(directory, "updates/9.apk.part").exists())
            assertFalse(File(directory, "updates/9.apk").exists())
        } finally {
            responseBody.cancel(kotlinx.coroutines.CancellationException("test cleanup"))
            httpClient.close()
            directory.deleteRecursively()
        }
    }

    @Test
    fun startupCleanupDeletesPartialFilesAndKeepsCompletedApks() {
        val directory = temporaryDirectory()
        val updates = File(directory, "updates").apply { mkdirs() }
        val abandonedApk = File(updates, "8.apk.part").apply { writeBytes(byteArrayOf(1)) }
        val abandonedOther = File(updates, "stale.part").apply { writeBytes(byteArrayOf(2)) }
        val completedApk = File(updates, "7.apk").apply { writeBytes(byteArrayOf(3)) }
        val httpClient = client { respond("", HttpStatusCode.OK) }
        val downloader = downloader(httpClient, directory)

        try {
            assertEquals(2, downloader.cleanupPartialFiles())
            assertFalse(abandonedApk.exists())
            assertFalse(abandonedOther.exists())
            assertTrue(completedApk.exists())
        } finally {
            httpClient.close()
            directory.deleteRecursively()
        }
    }

    @Test
    fun insufficientSpaceStopsBeforeRequestingTheApk() = runBlocking {
        val directory = temporaryDirectory()
        var requests = 0
        val httpClient = client {
            requests += 1
            respond("", HttpStatusCode.OK)
        }
        try {
            val error = assertFailsWith<AppUpdateDownloadException.InsufficientStorage> {
                downloader(httpClient, directory, availableBytes = { 1L })
                    .download(manifest(size = 3L))
            }

            assertTrue(error.message!!.contains("storage"))
            assertEquals(0, requests)
            assertFalse(File(directory, "updates/9.apk.part").exists())
        } finally {
            httpClient.close()
            directory.deleteRecursively()
        }
    }

    private fun client(
        handler: suspend MockRequestHandleScope.(HttpRequestData) -> HttpResponseData,
    ): AppUpdateHttpClient = AppUpdateHttpClient(
        MockEngine { request -> handler(request) },
        "Airchive/0.3.0",
    )

    private fun downloader(
        httpClient: AppUpdateHttpClient,
        directory: File,
        availableBytes: () -> Long = { 64L * 1024L * 1024L },
    ) = AndroidAppUpdateDownloader(httpClient, directory, availableBytes)

    private fun manifest(size: Long) = UpdateManifest(
        schemaVersion = 1,
        channel = "stable",
        packageName = "cloud.airchive.personal",
        versionCode = 9L,
        versionName = "0.3.0",
        commit = "a".repeat(40),
        publishedAt = "2026-11-09T00:00:00Z",
        releaseNotes = "Test update",
        apk = UpdateApk(
            url = "https://github.com/example/app.apk",
            sha256 = "b".repeat(64),
            size = size,
            signingCertSha256 = "c".repeat(64),
        ),
    )

    private fun temporaryDirectory(): File = Files.createTempDirectory("airchive-update-download").toFile()
}
