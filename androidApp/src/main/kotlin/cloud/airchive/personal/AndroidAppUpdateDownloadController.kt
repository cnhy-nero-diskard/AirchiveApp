package cloud.airchive.personal

import cloud.airchive.shared.data.update.AppUpdateDownloadActions
import cloud.airchive.shared.data.update.AppUpdateDownloadException
import cloud.airchive.shared.data.update.AppUpdateDownloadResult
import cloud.airchive.shared.data.update.AppUpdateService
import cloud.airchive.shared.data.update.UpdateFailureReason
import cloud.airchive.shared.data.update.UpdateManifest
import cloud.airchive.shared.data.update.AndroidAppUpdateDownloader
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Process-owned download work, independent of Activity and screen lifecycles. */
class AndroidAppUpdateDownloadController(
    private val service: AppUpdateService,
    private val downloader: AndroidAppUpdateDownloader,
    private val appScope: CoroutineScope,
) : AppUpdateDownloadActions {
    private val lock = Any()
    private var activeToken: Any? = null
    private var downloadJob: Job? = null

    @Volatile
    var completedDownload: AppUpdateDownloadResult? = null
        private set

    override fun download(manifest: UpdateManifest) {
        val token = Any()
        synchronized(lock) {
            if (downloadJob?.isActive == true) return
            activeToken = token
            downloadJob = appScope.launch {
                try {
                    if (!service.startDownload(manifest)) return@launch
                    completedDownload = null
                    try {
                        val result = downloader.download(manifest) { bytesReceived ->
                            service.reportDownloadProgress(bytesReceived)
                        }
                        if (service.completeDownload()) {
                            completedDownload = result
                        } else {
                            result.file.delete()
                        }
                    } catch (cancelled: CancellationException) {
                        withContext(NonCancellable) { service.cancelDownload() }
                        throw cancelled
                    } catch (_: AppUpdateDownloadException.InsufficientStorage) {
                        service.failDownload(UpdateFailureReason.STORAGE)
                    } catch (_: AppUpdateDownloadException.TooLarge) {
                        service.failDownload(UpdateFailureReason.DOWNLOAD_TOO_LARGE)
                    } catch (_: Exception) {
                        service.failDownload(UpdateFailureReason.DOWNLOAD)
                    }
                } finally {
                    synchronized(lock) {
                        if (activeToken === token) {
                            activeToken = null
                            downloadJob = null
                        }
                    }
                }
            }
        }
    }

    override fun cancelDownload() {
        synchronized(lock) { downloadJob?.cancel() }
    }
}
