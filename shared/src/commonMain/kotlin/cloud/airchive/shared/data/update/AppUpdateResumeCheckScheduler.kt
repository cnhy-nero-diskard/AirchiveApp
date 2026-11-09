package cloud.airchive.shared.data.update

import cloud.airchive.shared.data.sync.ForegroundRefreshBackoff
import kotlin.coroutines.cancellation.CancellationException

/** Lifecycle-owned resume checks; failed attempts back off independently of other refreshes. */
class AppUpdateResumeCheckScheduler(
    private val preferences: AppUpdatePreferences,
    private val check: suspend () -> Boolean,
    private val nowEpochMillis: () -> Long,
    private val elapsedRealtimeMillis: () -> Long,
    private val backoff: ForegroundRefreshBackoff = ForegroundRefreshBackoff(),
) {
    private var nextAttemptAtElapsedRealtime = 0L

    /** Null means a recent successful check makes a resume request unnecessary. */
    fun delayUntilNextAttemptMillis(): Long? {
        val lastSuccess = preferences.appUpdateState.value.lastSuccessfulCheckEpochMillis
        if (lastSuccess != null && nowEpochMillis() - lastSuccess < CHECK_INTERVAL_MILLIS) return null
        return (nextAttemptAtElapsedRealtime - elapsedRealtimeMillis()).coerceAtLeast(0L)
    }

    /** Returns success/failure when an attempt runs, or null when it is not due. */
    suspend fun attemptIfDue(): Boolean? {
        val delay = delayUntilNextAttemptMillis() ?: return null
        if (delay > 0L) return null
        val succeeded = try {
            check()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            false
        }
        nextAttemptAtElapsedRealtime = elapsedRealtimeMillis() + backoff.nextDelay(failed = !succeeded)
        return succeeded
    }

    companion object {
        const val CHECK_INTERVAL_MILLIS = 6 * 60 * 60 * 1_000L
    }
}
