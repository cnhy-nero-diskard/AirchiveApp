package cloud.airchive.shared.data.update

import io.ktor.client.statement.bodyAsChannel
import io.ktor.http.HttpHeaders
import io.ktor.http.isSuccess
import io.ktor.utils.io.ByteReadChannel
import io.ktor.utils.io.cancel
import io.ktor.utils.io.readAvailable
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.security.MessageDigest
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

data class AppUpdateDownloadResult(
    val file: File,
    val size: Long,
    val sha256: String,
)

sealed class AppUpdateDownloadException(message: String, cause: Throwable? = null) : Exception(message, cause) {
    class InsufficientStorage(cause: Throwable? = null) :
        AppUpdateDownloadException("There is not enough storage for the update download.", cause)

    class TooLarge : AppUpdateDownloadException("The update APK exceeds its declared or maximum size.")

    class Incomplete : AppUpdateDownloadException("The update download ended before the declared size.")
}

/** Streams a release APK into app-private cache while enforcing its declared and hard size limits. */
class AndroidAppUpdateDownloader(
    private val httpClient: AppUpdateHttpClient,
    cacheDirectory: File,
    private val availableBytes: () -> Long,
) {
    private val updatesDirectory = File(cacheDirectory, UPDATE_DIRECTORY_NAME)

    suspend fun download(
        manifest: UpdateManifest,
        onProgress: (suspend (Long) -> Unit)? = null,
    ): AppUpdateDownloadResult = withContext(Dispatchers.IO) {
        val declaredSize = manifest.apk.size
        if (declaredSize > MAX_APK_SIZE_BYTES) throw AppUpdateDownloadException.TooLarge()

        val requiredSpace = declaredSize + maxOf(MINIMUM_HEADROOM_BYTES, declaredSize / HEADROOM_DIVISOR)
        if (availableBytes() < requiredSpace) throw AppUpdateDownloadException.InsufficientStorage()

        if (!updatesDirectory.isDirectory && !updatesDirectory.mkdirs()) {
            throw AppUpdateDownloadException.InsufficientStorage()
        }

        val partialFile = File(updatesDirectory, "${manifest.versionCode}.apk.part")
        val completedFile = File(updatesDirectory, "${manifest.versionCode}.apk")
        if (partialFile.exists() && !partialFile.delete()) throw AppUpdateDownloadException.InsufficientStorage()
        if (completedFile.exists() && !completedFile.delete()) throw AppUpdateDownloadException.InsufficientStorage()

        var completed = false
        try {
            val response = httpClient.get(manifest.apk.url)
            if (!response.status.isSuccess()) {
                throw AppUpdateHttpException.HttpStatus(response.status.value)
            }
            response.headers[HttpHeaders.ContentLength]?.toLongOrNull()?.let { contentLength ->
                if (contentLength > declaredSize || contentLength > MAX_APK_SIZE_BYTES) {
                    throw AppUpdateDownloadException.TooLarge()
                }
            }

            val body = response.bodyAsChannel()
            val digest = MessageDigest.getInstance("SHA-256")
            var bytesReceived = 0L
            try {
                val output = try {
                    FileOutputStream(partialFile, false)
                } catch (error: IOException) {
                    throw AppUpdateDownloadException.InsufficientStorage(error)
                }
                output.use { stream ->
                    val buffer = ByteArray(STREAM_BUFFER_BYTES)
                    while (true) {
                        currentCoroutineContext().ensureActive()
                        val count = body.readAvailable(buffer, 0, buffer.size)
                        if (count < 0) break
                        if (count == 0) continue
                        if (bytesReceived > declaredSize - count || bytesReceived > MAX_APK_SIZE_BYTES - count) {
                            throw AppUpdateDownloadException.TooLarge()
                        }
                        try {
                            stream.write(buffer, 0, count)
                        } catch (error: IOException) {
                            throw AppUpdateDownloadException.InsufficientStorage(error)
                        }
                        digest.update(buffer, 0, count)
                        bytesReceived += count
                        onProgress?.invoke(bytesReceived)
                    }
                    try {
                        stream.fd.sync()
                    } catch (error: IOException) {
                        throw AppUpdateDownloadException.InsufficientStorage(error)
                    }
                }
            } catch (error: Throwable) {
                body.cancel(error as? CancellationException)
                throw error
            }

            if (bytesReceived != declaredSize) throw AppUpdateDownloadException.Incomplete()
            currentCoroutineContext().ensureActive()
            if (!partialFile.renameTo(completedFile)) throw AppUpdateDownloadException.InsufficientStorage()
            completed = true
            AppUpdateDownloadResult(completedFile, bytesReceived, digest.digest().toHexString())
        } finally {
            if (!completed && partialFile.exists()) partialFile.delete()
        }
    }

    /** Removes abandoned transfer files without touching completed APKs. */
    fun cleanupPartialFiles(): Int {
        if (!updatesDirectory.isDirectory) return 0
        return updatesDirectory.listFiles()
            ?.asSequence()
            ?.filter { it.isFile && it.name.endsWith(PARTIAL_FILE_SUFFIX) }
            ?.count { it.delete() }
            ?: 0
    }

    private fun ByteArray.toHexString(): String = buildString(size * 2) {
        for (byte in this@toHexString) {
            val value = byte.toInt() and 0xff
            append(HEX_DIGITS[value ushr 4])
            append(HEX_DIGITS[value and 0x0f])
        }
    }

    companion object {
        const val MAX_APK_SIZE_BYTES = 200L * 1024L * 1024L
        const val MINIMUM_HEADROOM_BYTES = 16L * 1024L * 1024L
        private const val HEADROOM_DIVISOR = 10L
        private const val STREAM_BUFFER_BYTES = 32 * 1024
        private const val UPDATE_DIRECTORY_NAME = "updates"
        private const val PARTIAL_FILE_SUFFIX = ".part"
        private const val HEX_DIGITS = "0123456789abcdef"
    }
}
