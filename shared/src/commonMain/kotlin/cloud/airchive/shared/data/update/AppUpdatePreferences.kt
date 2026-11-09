package cloud.airchive.shared.data.update

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

data class PendingInstallRecord(
    val targetVersionCode: Long,
    val startedAtEpochMillis: Long,
) {
    init {
        require(targetVersionCode > 0L) { "Pending update version code must be positive." }
        require(startedAtEpochMillis > 0L) { "Pending update start time must be positive." }
    }
}

/** Persisted updater values belong to this installation, not to an owner account. */
data class AppUpdatePreferencesSnapshot(
    val lastSuccessfulCheckEpochMillis: Long? = null,
    val dismissedVersionCode: Long? = null,
    val pendingInstall: PendingInstallRecord? = null,
)

interface AppUpdatePreferences {
    val appUpdateState: StateFlow<AppUpdatePreferencesSnapshot>

    fun recordSuccessfulCheck(epochMillis: Long)
    fun dismissVersion(versionCode: Long)
    fun setPendingInstall(record: PendingInstallRecord?)
}

data class PendingInstallStartupResolution(
    val state: AppUpdateState?,
    val shouldClearRecord: Boolean,
)

/** Resolves a prior install only after checking whether PackageInstaller still owns an active session. */
fun pendingInstallStartupResolution(
    record: PendingInstallRecord?,
    installedVersionCode: Long,
    installedVersionName: String,
    hasActiveInstallSession: Boolean,
): PendingInstallStartupResolution = when {
    record == null -> PendingInstallStartupResolution(state = null, shouldClearRecord = false)
    installedVersionCode >= record.targetVersionCode -> PendingInstallStartupResolution(
        state = AppUpdateState.Updated(installedVersionName),
        shouldClearRecord = true,
    )
    hasActiveInstallSession -> PendingInstallStartupResolution(state = null, shouldClearRecord = false)
    else -> PendingInstallStartupResolution(
        state = AppUpdateState.NotCompleted(installedVersionName),
        shouldClearRecord = true,
    )
}

fun resolvePendingInstallAtStartup(
    preferences: AppUpdatePreferences,
    installedVersionCode: Long,
    installedVersionName: String,
    hasActiveInstallSession: Boolean,
): AppUpdateState? {
    val resolution = pendingInstallStartupResolution(
        record = preferences.appUpdateState.value.pendingInstall,
        installedVersionCode = installedVersionCode,
        installedVersionName = installedVersionName,
        hasActiveInstallSession = hasActiveInstallSession,
    )
    if (resolution.shouldClearRecord) preferences.setPendingInstall(null)
    return resolution.state
}

/** Ensures the durable outcome record is visible before PackageInstaller can replace this process. */
fun recordPendingInstallBeforeSessionCommit(
    preferences: AppUpdatePreferences,
    record: PendingInstallRecord,
    commitSession: () -> Unit,
) {
    preferences.setPendingInstall(record)
    commitSession()
}

/** Common in-memory implementation for isolated test and preview graphs. */
class InMemoryAppUpdatePreferences(
    initial: AppUpdatePreferencesSnapshot = AppUpdatePreferencesSnapshot(),
) : AppUpdatePreferences {
    private val state = MutableStateFlow(initial)
    override val appUpdateState = state.asStateFlow()

    override fun recordSuccessfulCheck(epochMillis: Long) {
        require(epochMillis > 0L) { "Successful check time must be positive." }
        state.value = state.value.copy(lastSuccessfulCheckEpochMillis = epochMillis)
    }

    override fun dismissVersion(versionCode: Long) {
        require(versionCode > 0L) { "Dismissed version code must be positive." }
        state.value = state.value.copy(dismissedVersionCode = versionCode)
    }

    override fun setPendingInstall(record: PendingInstallRecord?) {
        state.value = state.value.copy(pendingInstall = record)
    }
}
