package cloud.airchive.shared.data.sync

import cloud.airchive.shared.contract.history.HistoryPartition
import cloud.airchive.shared.contract.history.LocalHistoryPort
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Event-driven refresh gate for app open/resume and explicit manual requests.
 *
 * This class owns no timer, polling loop, listener, or background worker: the
 * platform calls [onForeground] only when its app becomes active. A foreground
 * refresh runs when there has never been a successful sync or that success is
 * at least ten minutes old. [onManualRefresh] deliberately bypasses the age
 * threshold. Both events serialize on one mutex; a second concurrent foreground
 * callback re-reads the successful-sync age after the first finishes and skips
 * if it is now fresh.
 *
 * Callers provide the clock explicitly. The time is used only to decide whether
 * the previous success is old enough and is passed to the incremental
 * coordinator for its last-success timestamp; mutation watermarks still come
 * exclusively from the server-returned `persistedAt`/`reconciledAt` values.
 */
class ForegroundRefreshController(
    private val local: LocalHistoryPort,
    private val partition: HistoryPartition,
    private val refresh: suspend (nowEpochMillis: Long) -> IncrementalSyncResult,
) {

    private val mutex = Mutex()

    /** Attempts one app-open/resume refresh if the partition's success is stale. */
    suspend fun onForeground(nowEpochMillis: Long): ForegroundRefreshOutcome = mutex.withLock {
        val lastSuccessful = local.observe(partition).first().sync?.lastSuccessfulSyncEpochMillis
        if (!isForegroundRefreshDue(lastSuccessful, nowEpochMillis)) {
            ForegroundRefreshOutcome.SkippedFresh(
                lastSuccessfulSyncEpochMillis = requireNotNull(lastSuccessful),
            )
        } else {
            ForegroundRefreshOutcome.Refreshed(refresh(nowEpochMillis))
        }
    }

    /** Always attempts one refresh, even if the previous sync just succeeded. */
    suspend fun onManualRefresh(nowEpochMillis: Long): IncrementalSyncResult = mutex.withLock {
        refresh(nowEpochMillis)
    }

    companion object {
        /** Minimum age of the last success before a foreground event refreshes. */
        const val MINIMUM_FOREGROUND_REFRESH_AGE_MILLIS: Long = 10 * 60 * 1000L

        /** Pure gate used by the controller and boundary tests. */
        fun isForegroundRefreshDue(
            lastSuccessfulSyncEpochMillis: Long?,
            nowEpochMillis: Long,
        ): Boolean {
            if (lastSuccessfulSyncEpochMillis == null) return true
            if (nowEpochMillis < lastSuccessfulSyncEpochMillis) return false
            return nowEpochMillis - lastSuccessfulSyncEpochMillis >= MINIMUM_FOREGROUND_REFRESH_AGE_MILLIS
        }
    }
}

/** Result of an app-open/resume refresh gate decision. */
sealed interface ForegroundRefreshOutcome {
    /** A prior success is younger than ten minutes, so no query was attempted. */
    data class SkippedFresh(val lastSuccessfulSyncEpochMillis: Long) : ForegroundRefreshOutcome

    /** Both mutation streams and their atomic local commit completed. */
    data class Refreshed(val result: IncrementalSyncResult) : ForegroundRefreshOutcome
}
