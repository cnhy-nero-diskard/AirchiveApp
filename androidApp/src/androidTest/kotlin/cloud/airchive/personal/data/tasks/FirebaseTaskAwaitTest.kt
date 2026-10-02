package cloud.airchive.personal.data.tasks

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.android.gms.tasks.CancellationTokenSource
import com.google.android.gms.tasks.TaskCompletionSource
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Uses only local Tasks; never initializes Firebase or contacts the live project. */
@RunWith(AndroidJUnit4::class)
class FirebaseTaskAwaitTest {
    @Test(timeout = 5_000L)
    fun completedTaskReturnsItsResult() = runBlocking {
        val source = TaskCompletionSource<String>()
        source.setResult("fixture-result")
        assertEquals("fixture-result", source.task.awaitCancellable())
    }

    @Test(timeout = 5_000L)
    fun delayedCompletionResumesTheSuspendedCaller() = runBlocking {
        val source = TaskCompletionSource<String>()
        val waiter = async(start = CoroutineStart.UNDISPATCHED) { source.task.awaitCancellable() }
        source.setResult("fixture-result")
        assertEquals("fixture-result", withTimeout(1_000L) { waiter.await() })
    }

    @Test(timeout = 5_000L)
    fun taskFailurePreservesTheOriginalException() = runBlocking {
        val source = TaskCompletionSource<String>()
        val failure = IllegalStateException("fixture-failure")
        source.setException(failure)
        val actual = try {
            source.task.awaitCancellable()
            null
        } catch (caught: Exception) {
            caught
        }
        assertSame(failure, actual)
    }

    @Test(timeout = 5_000L)
    fun unfinishedTaskCanTimeOutWithoutWaitingForRemoteCompletion() = runBlocking {
        val source = TaskCompletionSource<String>()
        val result = withTimeoutOrNull(50L) { source.task.awaitCancellable() }
        assertNull(result)
        // Late callbacks after timeout must not resume a cancelled action or crash.
        source.setResult("late-fixture-result")
    }

    @Test(timeout = 5_000L)
    fun callerCancellationFinishesWithoutWaitingForTheTask() = runBlocking {
        val source = TaskCompletionSource<String>()
        var cancellationObserved = false
        val waiter = launch(start = CoroutineStart.UNDISPATCHED) {
            try {
                source.task.awaitCancellable()
            } catch (cancelled: CancellationException) {
                cancellationObserved = true
                throw cancelled
            }
        }
        withTimeout(1_000L) { waiter.cancelAndJoin() }
        assertTrue(cancellationObserved)
        source.setResult("late-fixture-result")
    }

    @Test(timeout = 5_000L)
    fun cancelledTaskCancelsTheCaller() = runBlocking {
        val token = CancellationTokenSource()
        val source = TaskCompletionSource<String>(token.token)
        token.cancel()
        val failure = try {
            withTimeout(1_000L) { source.task.awaitCancellable() }
            null
        } catch (cancelled: CancellationException) {
            cancelled
        }
        assertTrue(failure is CancellationException)
        assertFalse("Task cancellation must finish before the test guard expires", failure is TimeoutCancellationException)
    }
}
