package cloud.airchive.shared.data.update

import io.ktor.client.plugins.HttpRequestTimeoutException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.io.IOException
import kotlin.coroutines.cancellation.CancellationException

/** Platform-independent update checker with an isolated feed client and preferences. */
class AppUpdateService(
    private val httpClient: AppUpdateHttpClient,
    private val feedUrl: String,
    expectedPackageName: String,
    pinnedSignerSha256: String,
    private val installedVersionCode: Long,
    private val preferences: AppUpdatePreferences,
    private val nowEpochMillis: () -> Long,
) {
    private val machine = AppUpdateStateMachine(expectedPackageName, pinnedSignerSha256)
    private val mutableState = MutableStateFlow(machine.state)
    val state: StateFlow<AppUpdateState> = mutableState.asStateFlow()

    suspend fun check(): Boolean {
        mutableState.value = machine.dispatch(AppUpdateEvent.CheckStarted)
        return try {
            val manifest = httpClient.fetchManifest(feedUrl)
            val result = machine.dispatch(AppUpdateEvent.OfferResolved(manifest, installedVersionCode))
            mutableState.value = result
            if (result is AppUpdateState.Available || result is AppUpdateState.UpToDate) {
                preferences.recordSuccessfulCheck(nowEpochMillis())
                true
            } else {
                false
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

    fun close() = httpClient.close()

    private fun unavailable(cause: UpdateUnavailableCause): Boolean {
        mutableState.value = machine.dispatch(AppUpdateEvent.CheckFailed(cause))
        return false
    }
}

/** Android or another platform supplies this binding only when updates are enabled. */
data class AppUpdateBindings(
    val service: AppUpdateService,
    val preferences: AppUpdatePreferences,
    val currentVersionName: String,
    val currentVersionCode: Long,
) {
    val state: StateFlow<AppUpdateState> get() = service.state
}

fun <T> releaseOnlyAppUpdateBinding(isReleaseBuild: Boolean, create: () -> T): T? =
    if (isReleaseBuild) create() else null
