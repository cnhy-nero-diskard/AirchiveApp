package cloud.airchive.personal.preview

import android.content.Context
import android.content.Intent
import android.os.SystemClock
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import cloud.airchive.personal.preview.SharedInstrumentShellFixture.Companion.FIXTURE_INTERFACE_PREFERENCES_FILE
import cloud.airchive.shared.InterfaceDetail
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Isolated preference restart probe. Normal instrumentation runs exercise
 * Activity recreation; a host may also invoke the `seed` and `verify` phases
 * as separate `am instrument` runs with `am force-stop` between them. The
 * fixture's fake ports and preference file are the only sources used here.
 */
@RunWith(AndroidJUnit4::class)
class SharedInstrumentProcessRestartTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()

    @Test fun installationChoiceSurvivesFreshShellLifecycle() {
        when (InstrumentationRegistry.getArguments().getString("restartPhase")) {
            "seed" -> seedForExternalRestart()
            "verify" -> verifyAfterExternalRestart()
            else -> verifyAcrossActivityRecreation()
        }
    }

    private fun seedForExternalRestart() {
        isolatedPreferences().edit().clear().commit()
        processProbePreferences().edit().clear().commit()
        launchShell().use { scene ->
            scene.onActivity { it.shellFixture!!.interfacePreferences.select(InterfaceDetail.DETAILED) }
            awaitStoredDetailedValue()
            assertEquals(InterfaceDetail.DETAILED,
                sceneFixtureDetail(scene))
            processProbePreferences().edit().putInt(PROCESS_PID_KEY, sceneProcessPid(scene)).commit()
        }
    }

    private fun verifyAfterExternalRestart() {
        assertEquals("the preceding process wrote only the fixture enum", "detailed",
            isolatedPreferences().getString("interface_detail", null))
        val previousPid = processProbePreferences().getInt(PROCESS_PID_KEY, 0)
        assertTrue("the seed phase recorded the process that wrote the fixture preference", previousPid > 0)
        launchShell().use { scene ->
            assertEquals("a new production-shell adapter reads the prior process choice",
                InterfaceDetail.DETAILED, sceneFixtureDetail(scene))
            assertNotEquals("the host restart probe must launch the shell in a different process",
                previousPid, sceneProcessPid(scene))
        }
        isolatedPreferences().edit().clear().commit()
        processProbePreferences().edit().clear().commit()
    }

    private fun verifyAcrossActivityRecreation() {
        isolatedPreferences().edit().clear().commit()
        processProbePreferences().edit().clear().commit()
        launchShell().use { scene ->
            scene.onActivity { it.shellFixture!!.interfacePreferences.select(InterfaceDetail.DETAILED) }
            awaitStoredDetailedValue()
            scene.recreate()
            assertEquals("Activity recreation uses a fresh fixture adapter",
                InterfaceDetail.DETAILED, sceneFixtureDetail(scene))
        }
        isolatedPreferences().edit().clear().commit()
        processProbePreferences().edit().clear().commit()
    }

    private fun sceneFixtureDetail(scene: ActivityScenario<SharedInstrumentPreviewActivity>): InterfaceDetail {
        var detail = InterfaceDetail.SIMPLE
        scene.onActivity { detail = it.shellFixture!!.interfacePreferences.detail.value }
        return detail
    }

    private fun sceneProcessPid(scene: ActivityScenario<SharedInstrumentPreviewActivity>): Int {
        var pid = 0
        scene.onActivity { pid = android.os.Process.myPid() }
        return pid
    }

    private fun awaitStoredDetailedValue() {
        val file = File(instrumentation.targetContext.applicationInfo.dataDir,
            "shared_prefs/$FIXTURE_INTERFACE_PREFERENCES_FILE.xml")
        val deadline = SystemClock.uptimeMillis() + 5_000
        var stored = false
        while (SystemClock.uptimeMillis() < deadline && !stored) {
            stored = file.isFile && file.readText().contains("<string name=\"interface_detail\">detailed</string>")
            if (!stored) SystemClock.sleep(25)
        }
        assertTrue("the isolated display enum reaches its private preference file", stored)
    }

    private fun launchShell() = ActivityScenario.launch<SharedInstrumentPreviewActivity>(
        Intent(ApplicationProvider.getApplicationContext(), SharedInstrumentPreviewActivity::class.java)
            .putExtra("shell", true),
    )

    private fun isolatedPreferences() = instrumentation.targetContext.getSharedPreferences(
        FIXTURE_INTERFACE_PREFERENCES_FILE,
        Context.MODE_PRIVATE,
    )

    private fun processProbePreferences() = instrumentation.targetContext.getSharedPreferences(
        PROCESS_PROBE_FILE,
        Context.MODE_PRIVATE,
    )

    private companion object {
        const val PROCESS_PROBE_FILE = "airchive_shell_process_restart_probe"
        const val PROCESS_PID_KEY = "seed_process_id"
    }
}
