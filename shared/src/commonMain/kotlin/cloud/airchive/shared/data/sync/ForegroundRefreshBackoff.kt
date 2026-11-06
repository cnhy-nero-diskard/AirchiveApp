package cloud.airchive.shared.data.sync

/** Delay between foreground attempts; failures back off to a bounded maximum. */
class ForegroundRefreshBackoff {
    private var failures = 0

    fun nextDelay(failed: Boolean): Long {
        failures = if (failed) (failures + 1).coerceAtMost(4) else 0
        return BASE_MILLIS * (1L shl failures)
    }

    companion object {
        const val BASE_MILLIS = 5 * 60 * 1000L
    }
}
