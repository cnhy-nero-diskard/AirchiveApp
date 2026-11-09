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
