package cloud.airchive.shared.data.sync

import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.collectLatest

/** A platform-owned foreground job. Cancellation stops work, retaining its deadline for resume. */
class ForegroundRefreshLoop<K : Any>(
    private val elapsedRealtimeMillis: () -> Long,
    private val firstDelayMillis: Long = 0L,
    private val wait: suspend (Long) -> Unit = { delay(it) },
) {
    private var scheduleKey: K? = null
    private var nextAt = 0L
    private var backoff = ForegroundRefreshBackoff()

    /** The platform must cancel this collection when it leaves the foreground. */
    suspend fun run(contexts: Flow<K?>, refresh: suspend (K) -> Boolean) {
        contexts.collectLatest { context ->
            if (context == null) return@collectLatest
            if (scheduleKey != context) {
                scheduleKey = context
                backoff = ForegroundRefreshBackoff()
                nextAt = elapsedRealtimeMillis() + firstDelayMillis
            }
            while (true) {
                wait((nextAt - elapsedRealtimeMillis()).coerceAtLeast(0L))
                val failed = try {
                    refresh(context)
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Exception) {
                    true
                }
                nextAt = elapsedRealtimeMillis() + backoff.nextDelay(failed)
            }
        }
    }
}
