package cloud.airchive.shared.data.update

import io.ktor.client.plugins.HttpRequestTimeoutException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.io.IOException
import kotlin.coroutines.cancellation.CancellationException

/** Read/check contract consumed by the shared Settings UI. */
interface AppUpdateCheckPort {
    val state: StateFlow<AppUpdateState>
    suspend fun check(): Boolean
}

/** Download/cancel actions that start only from the owner's explicit Settings action. */
interface AppUpdateDownloadActions {
    fun download(manifest: UpdateManifest)
    fun cancelDownload()
}

/** Owner-confirmed installation actions supplied when platform install support is ready. */
interface AppUpdateActions : AppUpdateDownloadActions {
    fun install(manifest: UpdateManifest)
    fun requestInstallPermission(manifest: UpdateManifest)
}

/** Platform-independent update checker with an isolated feed client and preferences. */
class AppUpdateService(
    private val httpClient: AppUpdateHttpClient,
    private val feedUrl: String,
    expectedPackageName: String,
    pinnedSignerSha256: String,
    private val installedVersionCode: Long,
    private val preferences: AppUpdatePreferences,
    private val nowEpochMillis: () -> Long,
) : AppUpdateCheckPort {
    private val machine = AppUpdateStateMachine(expectedPackageName, pinnedSignerSha256)
    private val mutableState = MutableStateFlow(machine.state)
    private val checkMutex = Mutex()
    private val stateMutex = Mutex()
    override val state: StateFlow<AppUpdateState> = mutableState.asStateFlow()

    override suspend fun check(): Boolean = checkMutex.withLock { checkOnce() }

    private suspend fun checkOnce(): Boolean {
        val checkStarted = stateMutex.withLock {
            when (mutableState.value) {
                is AppUpdateState.Downloading,
                is AppUpdateState.Verifying,
                is AppUpdateState.ReadyToInstall,
                is AppUpdateState.NeedsInstallPermission,
                is AppUpdateState.Installing,
                -> false

                else -> {
                    mutableState.value = machine.dispatch(AppUpdateEvent.CheckStarted)
                    true
                }
            }
        }
        if (!checkStarted) return false

        return try {
            val manifest = httpClient.fetchManifest(feedUrl)
            stateMutex.withLock {
                if (mutableState.value != AppUpdateState.Checking) return@withLock false
                val result = machine.dispatch(AppUpdateEvent.OfferResolved(manifest, installedVersionCode))
                mutableState.value = result
                if (result is AppUpdateState.Available || result is AppUpdateState.UpToDate) {
                    preferences.recordSuccessfulCheck(nowEpochMillis())
                    true
                } else {
                    false
                }
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: UpdateManifestParseException.UnsupportedSchemaVersion) {
            unavailable(UpdateUnavailableCause.UNKNOWN_SCHEMA)
        } catch (_: HttpRequestTimeoutException) {
            unavailable(UpdateUnavailableCause.TIMEOUT)
        } catch (_: IOException) {
            unavailable(UpdateUnavailableCause.OFFLINE)
        } catch (_: Exception) {
            unavailable(UpdateUnavailableCause.FEED_ERROR)
        }
    }

    /** Transitions into Downloading only while the exact offered manifest is still current. */
    suspend fun startDownload(manifest: UpdateManifest): Boolean = stateMutex.withLock {
        val available = mutableState.value as? AppUpdateState.Available ?: return@withLock false
        if (available.manifest != manifest) return@withLock false
        mutableState.value = machine.dispatch(AppUpdateEvent.DownloadStarted)
        true
    }

    suspend fun reportDownloadProgress(bytesReceived: Long) {
        stateMutex.withLock {
            if (mutableState.value is AppUpdateState.Downloading) {
                mutableState.value = machine.dispatch(AppUpdateEvent.DownloadProgress(bytesReceived))
            }
        }
    }

    suspend fun completeDownload(): Boolean = stateMutex.withLock {
        if (mutableState.value !is AppUpdateState.Downloading) return@withLock false
        mutableState.value = machine.dispatch(AppUpdateEvent.DownloadCompleted)
        true
    }

    suspend fun cancelDownload() {
        stateMutex.withLock {
            if (mutableState.value is AppUpdateState.Downloading || mutableState.value is AppUpdateState.Verifying) {
                mutableState.value = machine.dispatch(AppUpdateEvent.DownloadCancelled)
            }
        }
    }

    suspend fun completeVerification(): Boolean = stateMutex.withLock {
        if (mutableState.value !is AppUpdateState.Verifying) return@withLock false
        mutableState.value = machine.dispatch(AppUpdateEvent.VerificationSucceeded)
        true
    }

    suspend fun failVerification(reason: UpdateFailureReason) {
        stateMutex.withLock {
            if (mutableState.value is AppUpdateState.Verifying) {
                mutableState.value = machine.dispatch(AppUpdateEvent.VerificationFailed(reason))
            }
        }
    }

    suspend fun failDownload(reason: UpdateFailureReason) {
        stateMutex.withLock {
            if (mutableState.value is AppUpdateState.Downloading || mutableState.value is AppUpdateState.Verifying) {
                mutableState.value = machine.dispatch(AppUpdateEvent.UpdateFailed(reason))
            }
        }
    }

    fun close() = httpClient.close()

    private suspend fun unavailable(cause: UpdateUnavailableCause): Boolean = stateMutex.withLock {
        if (mutableState.value != AppUpdateState.Checking) return@withLock false
        mutableState.value = machine.dispatch(AppUpdateEvent.CheckFailed(cause))
        false
    }
}

/** Android or another platform supplies this binding only when updates are enabled. */
data class AppUpdateBindings(
    val service: AppUpdateCheckPort,
    val preferences: AppUpdatePreferences,
    val currentVersionName: String,
    val currentVersionCode: Long,
    val feedUrl: String = "",
    val actions: AppUpdateActions? = null,
    val downloadActions: AppUpdateDownloadActions? = actions,
) {
    val state: StateFlow<AppUpdateState> get() = service.state
}

fun <T> releaseOnlyAppUpdateBinding(isReleaseBuild: Boolean, create: () -> T): T? =
    if (isReleaseBuild) create() else null
