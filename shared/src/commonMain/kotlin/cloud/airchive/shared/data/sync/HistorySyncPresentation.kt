package cloud.airchive.shared.data.sync

import cloud.airchive.shared.contract.history.HistorySnapshot

/**
 * User-facing, cache-first description of the latest history synchronization.
 * The screen always renders its [HistorySnapshot] independently of this status;
 * a denied/offline refresh changes the message, never the locally saved rows.
 */
data class HistorySyncPresentation(
    val hasCachedHistory: Boolean,
    val message: String,
    val lastSuccessfulAgeMillis: Long?,
    val lastErrorAgeMillis: Long?,
    val shouldAnnounceUpdate: Boolean,
    val retryLabel: String,
)

/** Pure status projection used by Home and deterministic offline/error tests. */
fun presentHistorySync(
    snapshot: HistorySnapshot,
    nowEpochMillis: Long,
): HistorySyncPresentation {
    val hasCachedHistory = snapshot.observations.isNotEmpty() || snapshot.dailyTotals.isNotEmpty()
    val sync = snapshot.sync
    val lastSuccessAge = age(nowEpochMillis, sync?.lastSuccessfulSyncEpochMillis)
    val lastErrorAge = age(nowEpochMillis, sync?.lastErrorEpochMillis)
    val error = sync?.lastError

    val message = when (error) {
        "ACCESS_DENIED" -> if (hasCachedHistory) {
            "Airchive access is pending or denied. Showing cached history; saved rows are unchanged."
        } else {
            "No history is cached yet. This account needs Airchive owner access before history can load."
        }

        "OFFLINE" -> if (hasCachedHistory) {
            "Airchive could not be reached. Showing cached history; it stays available offline."
        } else {
            "No history is cached yet. Connect to the internet and retry to load it."
        }

        null -> when {
            !hasCachedHistory -> "No history is cached yet. Refresh to try loading Airchive data."
            lastSuccessAge != null && lastSuccessAge >= ForegroundRefreshController.MINIMUM_FOREGROUND_REFRESH_AGE_MILLIS ->
                "Showing cached history. The last successful sync is at least ten minutes old."
            else -> "Showing saved Airchive history."
        }

        else -> if (hasCachedHistory) {
            "The last Airchive refresh failed. Showing cached history; saved rows are unchanged."
        } else {
            "No history is cached yet. The last sync attempt failed; retry when ready."
        }
    }

    return HistorySyncPresentation(
        hasCachedHistory = hasCachedHistory,
        message = message,
        lastSuccessfulAgeMillis = lastSuccessAge,
        lastErrorAgeMillis = lastErrorAge,
        shouldAnnounceUpdate = error != null,
        retryLabel = if (!hasCachedHistory || error != null) "Retry history sync" else "Refresh history",
    )
}

private fun age(nowEpochMillis: Long, eventEpochMillis: Long?): Long? = eventEpochMillis
    ?.takeIf { it <= nowEpochMillis }
    ?.let { nowEpochMillis - it }
