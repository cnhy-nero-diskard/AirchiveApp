package cloud.airchive.personal.preview

import android.content.Intent
import android.app.UiModeManager
import android.os.ParcelFileDescriptor
import android.graphics.Bitmap
import android.os.SystemClock
import android.view.accessibility.AccessibilityNodeInfo
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** The debug-only preview never constructs a production graph or contacts an AC. */
@RunWith(AndroidJUnit4::class)
class SharedInstrumentUiTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()

    private fun launch(dark: Boolean = false, stale: Boolean = false, route: Int = 0, detail: String? = "simple") = ActivityScenario.launch<SharedInstrumentPreviewActivity>(
        Intent(ApplicationProvider.getApplicationContext(), SharedInstrumentPreviewActivity::class.java)
            .putExtra("dark", dark).putExtra("stale", stale).putExtra("route", route).apply { if (detail != null) putExtra("detail", detail) },
    )

    private fun launchShell() = ActivityScenario.launch<SharedInstrumentPreviewActivity>(
        Intent(ApplicationProvider.getApplicationContext(), SharedInstrumentPreviewActivity::class.java)
            .putExtra("shell", true),
    )

    /**
     * OpenSpec task 1.3: the `shell` debug fixture composes the **actual**
     * [cloud.airchive.shared.ProductionAirchiveApp] over fake owner/history/
     * credential/verifier/ThinQ ports with a controlled clock, per-operation
     * counters and deferred-response gates — while provably constructing no
     * production Firebase graph, Android Keystore credential record or
     * Ktor/CIO client, and using its own dedicated preference namespace.
     */
    @Test fun shellFixtureRendersProductionShellOverFakePortsOnly() {
        val context = instrumentation.targetContext
        val sharedPrefsDir = File(context.applicationInfo.dataDir, "shared_prefs")
        val fixturePrefsFile = File(sharedPrefsDir, "${SharedInstrumentShellFixture.FIXTURE_INTERFACE_PREFERENCES_FILE}.xml")
        val productionPrefsFile = File(sharedPrefsDir, "airchive_interface.xml")
        val legacyPreviewPrefsFile = File(sharedPrefsDir, "airchive_preview_interface.xml")
        val thinqCredentialDir = File(context.noBackupFilesDir, "thinq")
        val productionCredentialFiles = listOf(
            File(thinqCredentialDir, "thinq_credential.enc"),
            File(thinqCredentialDir, "thinq_client_id"),
        )
        // Start from a clean dedicated namespace; fingerprint every production
        // artifact so a prior real run on this emulator cannot be misattributed.
        context.getSharedPreferences(SharedInstrumentShellFixture.FIXTURE_INTERFACE_PREFERENCES_FILE, android.content.Context.MODE_PRIVATE)
            .edit().remove("interface_detail").commit()
        val productionArtifactsBefore =
            (listOf(productionPrefsFile, legacyPreviewPrefsFile) + productionCredentialFiles)
                .associateWith { it.artifactFingerprint() }
        assertEquals("The fixture process must not initialize any named Firebase graph",
            0, com.google.firebase.FirebaseApp.getApps(context).size)

        var fixture: SharedInstrumentShellFixture? = null
        try {
        launchShell().use { scene ->
            scene.onActivity { fixture = it.shellFixture }
            val shell = fixture ?: throw AssertionError("The shell fixture was not constructed")

            // Signed-out Home renders from the fake ports only.
            awaitNode { it.text?.toString() == "Continue with Google" }

            // Deferred-response gate: hold the fake owner sign-in round trip.
            scene.onActivity { it.shellFixture!!.ownerSignInGate.hold() }
            clickText("Continue with Google")
            awaitNode { it.text?.toString() == "Signing in…" }
            awaitTrue("sign-in must reach the gated fake port") {
                shell.counters.ownerSignInAttempts.get() == 1
            }
            assertEquals("No access check may precede the gated sign-in",
                0, shell.counters.deviceIdentityReads.get())
            assertFalse("The owner gate stays held until released", shell.ownerSignInGate.isOpen)

            // Release: the fake sign-in completes and the shell authorizes via fake telemetry.
            scene.onActivity { it.shellFixture!!.ownerSignInGate.release() }
            awaitNode { it.text?.toString() == "Connect ThinQ in Settings to read and control your AC." }
            awaitTrue("the authorized access check must read the fake device identity") {
                shell.counters.deviceIdentityReads.get() >= 1
            }
            // Controlled clock: the bootstrap ran against the frozen fixture clock,
            // so the shell renders a deterministic zero-age sync label.
            awaitNode { it.text?.toString() == "History synced 0 sec ago" }
            assertTrue("the fake-port bootstrap must persist local history",
                shell.counters.localHistoryWrites.get() >= 1)
            assertEquals("No credential is stored, so no ThinQ read may happen",
                0, shell.counters.thinQLiveStateReads.get())

            // Advancing only the controlled clock moves the rendered age on the
            // next Home recomposition (route navigation is the trigger).
            scene.onActivity { it.shellFixture!!.clock.advanceBy(120_000) }
            clickText("Settings")
            clickText("Home")
            awaitNode { it.text?.toString() == "History synced 2 min ago" }

            // The display preference lands in the dedicated fixture namespace.
            clickText("Settings")
            clickText("Detailed")
            awaitTrue("the fixture preference must record the Detailed choice") {
                context.getSharedPreferences(
                    SharedInstrumentShellFixture.FIXTURE_INTERFACE_PREFERENCES_FILE,
                    android.content.Context.MODE_PRIVATE,
                ).getString("interface_detail", null) == "detailed"
            }
            scene.onActivity {
                assertEquals(
                    cloud.airchive.shared.InterfaceDetail.DETAILED,
                    it.shellFixture!!.interfacePreferences.detail.value,
                )
            }
        }
        } finally {
            context.getSharedPreferences(SharedInstrumentShellFixture.FIXTURE_INTERFACE_PREFERENCES_FILE, android.content.Context.MODE_PRIVATE)
                .edit().remove("interface_detail").commit()
        }

        // Production artifacts were never created or touched by the fixture shell.
        assertEquals(
            "Production preference and credential files must stay untouched",
            productionArtifactsBefore,
            (listOf(productionPrefsFile, legacyPreviewPrefsFile) + productionCredentialFiles)
                .associateWith { it.artifactFingerprint() },
        )
        assertEquals("No named Firebase graph may exist in the fixture process",
            0, com.google.firebase.FirebaseApp.getApps(context).size)

        // The composed graph record contains only fakes + shared coordinators,
        // and never any production Firebase/Keystore/Ktor/Room adapter type.
        val shell = fixture ?: throw AssertionError("The shell fixture was not constructed")
        val composed = shell.constructedGraph.joinToString()
        assertTrue(composed, composed.contains("FakeThinQClient"))
        shell.neverConstructedProductionGraph.forEach { productionType ->
            assertFalse(
                "Fixture graph must not compose $productionType; composed: $composed",
                productionType in shell.constructedGraph,
            )
        }
    }

    @Test fun temperatureIsOnlySentOnApplyAndStaleStateDisablesControls() {
        launch().use { scene ->
            awaitNode { it.contentDescription?.toString() == "Increase target temperature" }.performAction(AccessibilityNodeInfo.ACTION_CLICK)
            awaitNode { it.text?.toString()?.contains("Currently 25") == true }
            scene.onActivity { assertEquals(0, it.submittedCommands) }
            var apply = awaitNode { it.text?.toString() == "Apply" }
            while (!apply.isClickable && apply.parent != null) apply = apply.parent
            assertTrue(apply.performAction(AccessibilityNodeInfo.ACTION_CLICK))
            awaitNode { it.text?.toString() == "26" }
            scene.onActivity { assertEquals(1, it.submittedCommands) }
        }
        launch(stale = true).use { scene ->
            val step = awaitNode { it.contentDescription?.toString() == "Increase target temperature" }
            assertFalse(step.isEnabled)
            scene.onActivity { assertEquals(0, it.submittedCommands) }
        }
    }

    @Test fun captureLightDarkAndLargeText() {
        val scale = InstrumentationRegistry.getArguments().getString("fontScale", "1.0")
        val previousMode = instrumentation.targetContext.getSystemService(UiModeManager::class.java).nightMode
        fun systemTheme(mode: String) {
            ParcelFileDescriptor.AutoCloseInputStream(instrumentation.uiAutomation.executeShellCommand("cmd uimode night $mode"))
                .bufferedReader().use { it.readText() }
        }
        try {
        for (dark in listOf(false, true)) {
        systemTheme(if (dark) "yes" else "no")
        launch(dark).use {
            awaitNode { it.text?.toString() == "Sampled today" }
            instrumentation.waitForIdleSync()
            instrumentation.uiAutomation.waitForIdle(250, 5_000)
            val bitmap = instrumentation.uiAutomation.takeScreenshot()
            assertNotNull("Native screen capture", bitmap)
            val output = File(instrumentation.targetContext.getExternalFilesDir(null),
                "shared-instrument-${if (dark) "dark" else "light"}-$scale.png")
            output.outputStream().use { stream -> assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, stream)) }
            bitmap.recycle()
        }
        }
        } finally { systemTheme(when(previousMode) { UiModeManager.MODE_NIGHT_YES -> "yes"; UiModeManager.MODE_NIGHT_NO -> "no"; else -> "auto" }) }
    }

    @Test fun remainingRoutesPreservePeriodSelectionSampleDetailsAndConfirmation() {
        launch(route = 2).use {
            clickText("Week")
            awaitNode { it.text?.toString() == "Daily energy measures" }
            clickText("Month")
            awaitNode { it.text?.toString() == "Daily energy measures" }
        }
        launch(route = 3).use {
            var sample: AccessibilityNodeInfo? = null
            repeat(5) {
                if (sample == null) {
                    sample = find(instrumentation.uiAutomation.rootInActiveWindow) { it.contentDescription?.toString()?.startsWith("Sample at ") == true }
                    if (sample == null) scrollForward()
                }
            }
            clickNode(sample ?: throw AssertionError("A cached sample must remain reachable"))
            awaitNode { it.text?.toString() == "Sample detail" }
            clickText("Close")
        }
        launch(route = 4).use {
            clickText("Account actions")
            clickText("Clear local history cache")
            awaitNode { it.text?.toString() == "Clear local Airchive cache?" }
            clickText("Cancel")
            awaitNode { it.text?.toString() == "Clear local history cache" }
        }
    }

    @Test fun captureRemainingRoutes() {
        val scale = InstrumentationRegistry.getArguments().getString("fontScale", "1.0")
        val previousMode = instrumentation.targetContext.getSystemService(UiModeManager::class.java).nightMode
        fun systemTheme(mode: String) {
            ParcelFileDescriptor.AutoCloseInputStream(instrumentation.uiAutomation.executeShellCommand("cmd uimode night $mode"))
                .bufferedReader().use { it.readText() }
        }
        try {
            for (dark in listOf(false, true)) {
                systemTheme(if (dark) "yes" else "no")
                for ((route, name) in listOf(1 to "device", 2 to "energy", 3 to "history", 4 to "settings")) {
                    launch(dark = dark, route = route).use {
                        awaitNode { it.text?.toString() == when(route) { 1 -> "Device details"; 2 -> "Sampled energy"; 3 -> "Sample status guide"; else -> "Account actions" } }
                        if (route == 1) awaitNode { it.text?.toString() == "25" }
                        instrumentation.waitForIdleSync()
                        instrumentation.uiAutomation.waitForIdle(250, 5_000)
                        saveCapture("$name-${if (dark) "dark" else "light"}-$scale")
                    }
                }
            }
        } finally { systemTheme(when(previousMode) { UiModeManager.MODE_NIGHT_YES -> "yes"; UiModeManager.MODE_NIGHT_NO -> "no"; else -> "auto" }) }
    }

    @Test fun interfaceDetailAppliesAcrossScreensAndSurvivesRecreation() {
        launch(route = 4).use { scene ->
            clickText("Detailed")
            scene.onActivity { assertEquals(cloud.airchive.shared.InterfaceDetail.DETAILED,
                cloud.airchive.personal.data.preferences.AndroidInterfaceDetailPreferences(it, "airchive_preview_interface").detail.value) }
            clickText("Device")
            awaitNode { it.text?.toString() == "Live state and supported controls for your air conditioner." }
            scene.recreate()
            awaitNode { it.text?.toString() == "Detailed" }
            scene.onActivity { assertEquals(cloud.airchive.shared.InterfaceDetail.DETAILED,
                cloud.airchive.personal.data.preferences.AndroidInterfaceDetailPreferences(it, "airchive_preview_interface").detail.value) }
        }
        launch(route = 4, detail = null).use {
            awaitNode { it.text?.toString() == "Detailed" }
            clickText("Simple")
            clickText("Device")
            awaitNode { it.text?.toString() == "25" }
            assertNull(find(instrumentation.uiAutomation.rootInActiveWindow) {
                it.text?.toString() == "Live state and supported controls for your air conditioner."
            })
        }
    }

    @Test fun captureInterfaceDetailModes() {
        val scale = InstrumentationRegistry.getArguments().getString("fontScale", "1.0")
        val previousMode = instrumentation.targetContext.getSystemService(UiModeManager::class.java).nightMode
        fun systemTheme(mode: String) {
            ParcelFileDescriptor.AutoCloseInputStream(instrumentation.uiAutomation.executeShellCommand("cmd uimode night $mode"))
                .bufferedReader().use { it.readText() }
        }
        try {
            for (dark in listOf(false, true)) {
                systemTheme(if (dark) "yes" else "no")
                for (detail in listOf("simple", "detailed")) {
                    launch(dark = dark, route = 4, detail = detail).use {
                        awaitNode { it.text?.toString() == "Account actions" }
                        instrumentation.uiAutomation.waitForIdle(250, 5_000)
                        saveCapture("interface-$detail-${if (dark) "dark" else "light"}-$scale")
                    }
                }
            }
        } finally {
            instrumentation.targetContext.getSharedPreferences("airchive_preview_interface", android.content.Context.MODE_PRIVATE)
                .edit().remove("interface_detail").commit()
            systemTheme(when(previousMode) { UiModeManager.MODE_NIGHT_YES -> "yes"; UiModeManager.MODE_NIGHT_NO -> "no"; else -> "auto" })
        }
    }

    private fun saveCapture(name: String) {
        val bitmap = instrumentation.uiAutomation.takeScreenshot() ?: throw AssertionError("Native capture failed")
        File(instrumentation.targetContext.getExternalFilesDir(null), "$name.png").outputStream().use {
            assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it))
        }
        bitmap.recycle()
    }

    private fun clickNode(node: AccessibilityNodeInfo) {
        var target = node
        while (!target.isClickable && target.parent != null) target = target.parent
        assertTrue(target.performAction(AccessibilityNodeInfo.ACTION_CLICK))
        instrumentation.waitForIdleSync()
    }

    private fun clickText(text: String) {
        repeat(8) {
            instrumentation.waitForIdleSync()
            val node = find(instrumentation.uiAutomation.rootInActiveWindow) { it.text?.toString() == text }
            if (node != null) { clickNode(node); return }
            scrollForward()
        }
        clickNode(awaitNode { it.text?.toString() == text })
    }

    private fun scrollForward() {
        find(instrumentation.uiAutomation.rootInActiveWindow) { it.isScrollable }
            ?.performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD)
        instrumentation.waitForIdleSync()
    }

    private fun awaitNode(predicate: (AccessibilityNodeInfo) -> Boolean): AccessibilityNodeInfo {
        val deadline = SystemClock.uptimeMillis() + 10_000
        while (SystemClock.uptimeMillis() < deadline) {
            instrumentation.waitForIdleSync()
            find(instrumentation.uiAutomation.rootInActiveWindow, predicate)?.let { return it }
            SystemClock.sleep(50)
        }
        throw AssertionError("Expected accessible instrument content was not visible")
    }

    private fun awaitTrue(description: String, condition: () -> Boolean) {
        val deadline = SystemClock.uptimeMillis() + 10_000
        while (SystemClock.uptimeMillis() < deadline) {
            instrumentation.waitForIdleSync()
            if (condition()) return
            SystemClock.sleep(50)
        }
        throw AssertionError("Timed out waiting for: $description")
    }

    /** Stable identity+content fingerprint of an on-disk artifact; null when absent. */
    private fun java.io.File.artifactFingerprint(): String? =
        if (isFile) "${length()}:${lastModified()}" else null

    private fun find(node: AccessibilityNodeInfo?, predicate: (AccessibilityNodeInfo) -> Boolean): AccessibilityNodeInfo? {
        if (node == null) return null
        if (predicate(node)) return node
        for (i in 0 until node.childCount) find(node.getChild(i), predicate)?.let { return it }
        return null
    }
}
