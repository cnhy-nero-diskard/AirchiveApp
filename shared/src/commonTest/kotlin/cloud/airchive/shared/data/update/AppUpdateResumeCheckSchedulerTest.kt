package cloud.airchive.shared.data.update

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking

class AppUpdateResumeCheckSchedulerTest {
    @Test
    fun resumeWithinSixHoursOfSuccessfulCheckDoesNotRequestFeed() = runBlocking {
        val now = 1_800_000_000_000L
        val preferences = InMemoryAppUpdatePreferences(
            AppUpdatePreferencesSnapshot(lastSuccessfulCheckEpochMillis = now - AppUpdateResumeCheckScheduler.CHECK_INTERVAL_MILLIS + 1L),
        )
        var requests = 0
        val scheduler = scheduler(preferences, now, elapsed = 0L) {
            requests += 1
            true
        }

        assertNull(scheduler.delayUntilNextAttemptMillis())
        assertNull(scheduler.attemptIfDue())
        assertEquals(0, requests)
    }

    @Test
    fun failedCheckBacksOffWithoutChangingOwnerRefreshState() = runBlocking {
        val now = 1_800_000_000_000L
        val oldSuccess = now - AppUpdateResumeCheckScheduler.CHECK_INTERVAL_MILLIS - 1L
        val preferences = InMemoryAppUpdatePreferences(
            AppUpdatePreferencesSnapshot(lastSuccessfulCheckEpochMillis = oldSuccess),
        )
        var elapsed = 100L
        var requests = 0
        val historyRefreshState = MutableStateFlow("history refresh idle")
        val deviceRefreshState = MutableStateFlow("device refresh ready")
        val scheduler = AppUpdateResumeCheckScheduler(
            preferences = preferences,
            check = { requests += 1; false },
            nowEpochMillis = { now },
            elapsedRealtimeMillis = { elapsed },
        )

        assertEquals(0L, scheduler.delayUntilNextAttemptMillis())
        assertFalse(scheduler.attemptIfDue()!!)
        assertEquals(1, requests)
        assertEquals(oldSuccess, preferences.appUpdateState.value.lastSuccessfulCheckEpochMillis)
        assertEquals(10 * 60_000L, scheduler.delayUntilNextAttemptMillis())
        assertNull(scheduler.attemptIfDue(), "the retry is not due before its backoff expires")

        elapsed += 10 * 60_000L
        assertFalse(scheduler.attemptIfDue()!!)
        assertEquals(2, requests)
        assertEquals(20 * 60_000L, scheduler.delayUntilNextAttemptMillis())
        assertEquals("history refresh idle", historyRefreshState.value)
        assertEquals("device refresh ready", deviceRefreshState.value)
    }

    @Test
    fun successfulCheckRecordsTimeAndStopsFurtherResumeAttempts() = runBlocking {
        val now = 1_800_000_000_000L
        val preferences = InMemoryAppUpdatePreferences()
        val scheduler = AppUpdateResumeCheckScheduler(
            preferences = preferences,
            check = {
                preferences.recordSuccessfulCheck(now)
                true
            },
            nowEpochMillis = { now },
            elapsedRealtimeMillis = { 100L },
        )

        assertTrue(scheduler.attemptIfDue()!!)
        assertEquals(now, preferences.appUpdateState.value.lastSuccessfulCheckEpochMillis)
        assertNull(scheduler.delayUntilNextAttemptMillis())
    }

    private fun scheduler(
        preferences: AppUpdatePreferences,
        now: Long,
        elapsed: Long,
        check: suspend () -> Boolean,
) = AppUpdateResumeCheckScheduler(
        preferences = preferences,
        check = check,
        nowEpochMillis = { now },
        elapsedRealtimeMillis = { elapsed },
    )
}
