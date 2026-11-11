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

/** Resolves a prior install without consuming its durable outcome before Settings presents it. */
fun pendingInstallStartupResolution(
    record: PendingInstallRecord?,
    installedVersionCode: Long,
    installedVersionName: String,
    hasActiveInstallSession: Boolean,
): AppUpdateState? = when {
    record == null -> null
    installedVersionCode >= record.targetVersionCode -> AppUpdateState.Updated(installedVersionName)
    hasActiveInstallSession -> null
    else -> AppUpdateState.NotCompleted(installedVersionName)
}

fun resolvePendingInstallAtStartup(
    preferences: AppUpdatePreferences,
    installedVersionCode: Long,
    installedVersionName: String,
    hasActiveInstallSession: Boolean,
): AppUpdateState? {
    return pendingInstallStartupResolution(
        record = preferences.appUpdateState.value.pendingInstall,
        installedVersionCode = installedVersionCode,
        installedVersionName = installedVersionName,
        hasActiveInstallSession = hasActiveInstallSession,
    )
}

/** Clears a recovered result only after the running version proves which outcome is valid. */
fun acknowledgePendingInstallOutcome(
    preferences: AppUpdatePreferences,
    installedVersionCode: Long,
    state: AppUpdateState,
): Boolean {
    val record = preferences.appUpdateState.value.pendingInstall ?: return false
    val canAcknowledge = when (state) {
        is AppUpdateState.Updated -> installedVersionCode >= record.targetVersionCode
        is AppUpdateState.NotCompleted -> installedVersionCode < record.targetVersionCode
        else -> false
    }
    if (!canAcknowledge) return false
    preferences.setPendingInstall(null)
    return true
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
