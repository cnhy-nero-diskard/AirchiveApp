package cloud.airchive.personal.preview

import android.os.SystemClock
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.lifecycleScope
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import cloud.airchive.shared.data.sync.ForegroundRefreshLoop
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.Job
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Real Android pause/resume with the production loop, accelerated monotonic time, synthetic work only. */
@RunWith(AndroidJUnit4::class)
class ForegroundRefreshLifecycleTest {
    private class Harness(
        val contexts: MutableStateFlow<String?> = MutableStateFlow("owner-a"),
        firstDelay: Long = 0,
        val refresh: suspend (String) -> Boolean,
    ) : DefaultLifecycleObserver {
        val loop = ForegroundRefreshLoop<String>({ SystemClock.elapsedRealtime() * 1000 }, firstDelay,
            wait = { kotlinx.coroutines.delay((it + 999) / 1000) })
        private var job: Job? = null
        override fun onResume(owner: LifecycleOwner) {
            job = owner.lifecycleScope.launch { loop.run(contexts, refresh) }
        }
        override fun onPause(owner: LifecycleOwner) { job?.cancel(); job = null }
    }

    @Test fun pauseCancelsReadAndResumeUsesCurrentOwner() {
        val entered = CopyOnWriteArrayList<String>()
        val cancelled = CopyOnWriteArrayList<String>()
        val harness = Harness { owner ->
            entered += owner
            try { awaitCancellation() } finally { cancelled += owner }
        }
        ActivityScenario.launch(ForegroundRefreshTestActivity::class.java).use { scene ->
            scene.onActivity { it.lifecycle.addObserver(harness) }
            await { entered.size == 1 }
            scene.moveToState(Lifecycle.State.STARTED)
            await { cancelled.size == 1 }
            harness.contexts.value = "owner-b"
            SystemClock.sleep(350)
            assertEquals(listOf("owner-a"), entered.toList())
            scene.moveToState(Lifecycle.State.RESUMED)
            await { entered.size == 2 }
            assertEquals(listOf("owner-a", "owner-b"), entered.toList())
            harness.contexts.value = null
            await { cancelled.size == 2 }
            SystemClock.sleep(350)
            assertEquals(2, entered.size)
        }
    }

    @Test fun cadenceAndBackoffSurvivePauseAndOwnerChangeCancelsDelay() {
        val calls = CopyOnWriteArrayList<Pair<String, Long>>()
        val harness = Harness { owner -> calls += owner to SystemClock.elapsedRealtime(); true }
        ActivityScenario.launch(ForegroundRefreshTestActivity::class.java).use { scene ->
            scene.onActivity { it.lifecycle.addObserver(harness) }
            await { calls.size == 1 }
            scene.moveToState(Lifecycle.State.STARTED)
            scene.moveToState(Lifecycle.State.RESUMED)
            SystemClock.sleep(150)
            assertEquals("resume retains the failed attempt's 10-minute deadline", 1, calls.size)
            await { calls.size == 2 }
            assertTrue("failure backs off beyond the five-minute baseline", calls[1].second - calls[0].second >= 550)
            harness.contexts.value = "owner-b"
            await { calls.size == 3 }
            assertEquals("owner-b", calls[2].first)
        }
    }

    @Test fun liveLoopWaitsForBaselineAndContextChangeCancelsInFlightWork() {
        val entered = CopyOnWriteArrayList<String>()
        val cancelled = AtomicInteger()
        val harness = Harness(firstDelay = 300_000) { owner ->
            entered += owner
            try { awaitCancellation() } finally { cancelled.incrementAndGet() }
        }
        ActivityScenario.launch(ForegroundRefreshTestActivity::class.java).use { scene ->
            scene.onActivity { it.lifecycle.addObserver(harness) }
            SystemClock.sleep(100)
            assertTrue("entry read is not duplicated immediately", entered.isEmpty())
            await { entered.size == 1 }
            harness.contexts.value = "owner-b"
            await { cancelled.get() == 1 }
            await { entered.size == 2 }
            assertEquals(listOf("owner-a", "owner-b"), entered.toList())
            scene.moveToState(Lifecycle.State.STARTED)
            await { cancelled.get() == 2 }
        }
    }

    private fun await(condition: () -> Boolean) {
        val deadline = SystemClock.elapsedRealtime() + 5_000
        while (!condition() && SystemClock.elapsedRealtime() < deadline) SystemClock.sleep(10)
        assertTrue("condition reached before deadline", condition())
    }
}
