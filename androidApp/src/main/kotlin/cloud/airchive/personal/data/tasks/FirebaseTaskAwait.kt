package cloud.airchive.personal.data.tasks

import com.google.android.gms.tasks.Task
import java.util.concurrent.Executor
import kotlin.coroutines.cancellation.CancellationException
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.suspendCancellableCoroutine

private val directTaskExecutor = Executor { it.run() }

/** Waits without blocking a thread, so caller cancellation and timeouts can finish promptly. */
internal suspend fun <T> Task<T>.awaitCancellable(): T = suspendCancellableCoroutine { continuation ->
    addOnCompleteListener(directTaskExecutor) { completed ->
        if (!continuation.isActive) return@addOnCompleteListener
        if (completed.isCanceled) {
            continuation.cancel(CancellationException("Firebase task was cancelled."))
        } else {
            val failure = completed.exception
            if (failure != null) continuation.resumeWithException(failure)
            else continuation.resume(completed.result)
        }
    }
}
