package cloud.airchive.personal.data.preferences

import android.content.Context
import cloud.airchive.shared.InterfaceDetail
import cloud.airchive.shared.InterfaceDetailPreferences
import cloud.airchive.shared.data.update.AppUpdatePreferences
import cloud.airchive.shared.data.update.AppUpdatePreferencesSnapshot
import cloud.airchive.shared.data.update.PendingInstallRecord
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Nonsecret, installation-wide display choice. Independent of credentials and account cache. */
class AndroidInterfaceDetailPreferences(
    context: Context,
    name: String = "airchive_interface",
) : InterfaceDetailPreferences, AppUpdatePreferences {
    private val preferences = context.applicationContext.getSharedPreferences(name, Context.MODE_PRIVATE)
    private val state = MutableStateFlow(InterfaceDetail.fromStoredValue(preferences.getString(KEY, null)))
    override val detail = state.asStateFlow()
    private val updateState = MutableStateFlow(readUpdateSnapshot())
    override val appUpdateState = updateState.asStateFlow()

    override fun select(detail: InterfaceDetail) {
        preferences.edit().putString(KEY, detail.storedValue).apply()
        state.value = detail
    }

    override fun recordSuccessfulCheck(epochMillis: Long) {
        require(epochMillis > 0L) { "Successful check time must be positive." }
        preferences.edit().putLong(KEY_LAST_SUCCESSFUL_UPDATE_CHECK, epochMillis).apply()
        updateState.value = updateState.value.copy(lastSuccessfulCheckEpochMillis = epochMillis)
    }

    override fun dismissVersion(versionCode: Long) {
        require(versionCode > 0L) { "Dismissed version code must be positive." }
        preferences.edit().putLong(KEY_DISMISSED_UPDATE_VERSION, versionCode).apply()
        updateState.value = updateState.value.copy(dismissedVersionCode = versionCode)
    }

    override fun setPendingInstall(record: PendingInstallRecord?) {
        val editor = preferences.edit()
        if (record == null) {
            editor.remove(KEY_PENDING_UPDATE_VERSION).remove(KEY_PENDING_UPDATE_STARTED_AT)
        } else {
            editor.putLong(KEY_PENDING_UPDATE_VERSION, record.targetVersionCode)
                .putLong(KEY_PENDING_UPDATE_STARTED_AT, record.startedAtEpochMillis)
        }
        editor.apply()
        updateState.value = updateState.value.copy(pendingInstall = record)
    }

    private fun readUpdateSnapshot(): AppUpdatePreferencesSnapshot {
        val lastCheck = preferences.getLong(KEY_LAST_SUCCESSFUL_UPDATE_CHECK, MISSING_LONG).takeIf { it > 0L }
        val dismissedVersion = preferences.getLong(KEY_DISMISSED_UPDATE_VERSION, MISSING_LONG).takeIf { it > 0L }
        val pendingVersion = preferences.getLong(KEY_PENDING_UPDATE_VERSION, MISSING_LONG)
        val pendingStartedAt = preferences.getLong(KEY_PENDING_UPDATE_STARTED_AT, MISSING_LONG)
        val pendingInstall = if (pendingVersion > 0L && pendingStartedAt > 0L) {
            PendingInstallRecord(pendingVersion, pendingStartedAt)
        } else {
            null
        }
        return AppUpdatePreferencesSnapshot(lastCheck, dismissedVersion, pendingInstall)
    }

    private companion object {
        const val KEY = "interface_detail"
        const val KEY_LAST_SUCCESSFUL_UPDATE_CHECK = "app_update_last_successful_check"
        const val KEY_DISMISSED_UPDATE_VERSION = "app_update_dismissed_version"
        const val KEY_PENDING_UPDATE_VERSION = "app_update_pending_version"
        const val KEY_PENDING_UPDATE_STARTED_AT = "app_update_pending_started_at"
        const val MISSING_LONG = -1L
    }
}
