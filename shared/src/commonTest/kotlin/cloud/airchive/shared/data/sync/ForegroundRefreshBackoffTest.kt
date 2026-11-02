package cloud.airchive.shared.data.sync

import kotlin.test.Test
import kotlin.test.assertEquals

class ForegroundRefreshBackoffTest {
    @Test fun failuresBackOffAndSuccessRestoresCadence() {
        val backoff = ForegroundRefreshBackoff()
        val base = ForegroundRefreshBackoff.BASE_MILLIS
        assertEquals(base, backoff.nextDelay(false))
        assertEquals(base * 2, backoff.nextDelay(true))
        assertEquals(base * 4, backoff.nextDelay(true))
        repeat(5) { backoff.nextDelay(true) }
        assertEquals(base * 16, backoff.nextDelay(true))
        assertEquals(base, backoff.nextDelay(false))
    }
}
