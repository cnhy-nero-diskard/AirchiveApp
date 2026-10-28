package cloud.airchive.personal.preview

import android.content.Intent
import android.app.UiModeManager
import android.os.ParcelFileDescriptor
import android.graphics.Bitmap
import android.os.Bundle
import android.os.SystemClock
import android.view.accessibility.AccessibilityNodeInfo
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import kotlinx.coroutines.runBlocking

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

    @Test fun accountSwitchClearsThinQFormAndSyntheticPatDraft() {
        val syntheticMarker = "synthetic-ui-pat-marker-2468"
        clearFixturePreferences()
        launchShell().use { scene ->
            awaitNode { it.text?.toString() == "Continue with Google" }
            clickText("Continue with Google")
            awaitNode { it.text?.toString() == "Connect ThinQ in Settings to read and control your AC." }

            clickText("Settings")
            clickText("Set up ThinQ PAT")
            setTextByHint("ThinQ Personal Access Token", syntheticMarker)
            setTextByHint("ThinQ account country", "US")
            awaitNode { it.text?.toString() == "US" }
            scene.onActivity { it.shellFixture!!.setNextOwner("shell-fixture-owner-b") }

            // The real production account confirmation path clears the
            // protected fake store and changes the lifecycle owner partition.
            clickText("Account actions")
            clickText("Switch Google account")
            awaitNode { it.text?.toString() == "Switch Google account?" }
            clickText("Confirm")

            awaitNode { it.text?.toString() == "Set up ThinQ PAT" }
            instrumentation.waitForIdleSync()
            val root = instrumentation.uiAutomation.rootInActiveWindow
            assertNull("a prior owner's token-entry field must not survive account change",
                find(root) { it.hintText?.toString() == "ThinQ Personal Access Token" })
            assertNull("a prior owner's country draft must not survive account change",
                find(root) { it.hintText?.toString() == "ThinQ account country" })
            assertNull("the synthetic plaintext marker must not resurface in the new owner UI",
                find(root) { it.text?.toString()?.contains(syntheticMarker) == true })
            scene.onActivity { fixtureActivity ->
                assertEquals("shell-fixture-owner-b", fixtureActivity.shellFixture!!.coordinator.session.value?.uid)
                assertEquals(1, fixtureActivity.shellFixture!!.counters.credentialClears.get())
            }
        }
    }

    @Test fun selectedHistoryDetailDoesNotReopenForAnotherOwnersMatchingSampleId() {
        clearFixturePreferences()
        launchShell().use { scene ->
            clickText("Continue with Google")
            awaitNode { it.text?.toString() == "Connect ThinQ in Settings to read and control your AC." }
            clickText("History")
            awaitNode { it.text?.toString() == "Sample status guide" }
            var row: AccessibilityNodeInfo? = null
            repeat(6) {
                if (row == null) {
                    row = find(instrumentation.uiAutomation.rootInActiveWindow) {
                        it.contentDescription?.toString()?.startsWith("Sample at ") == true
                    }
                    if (row == null) scrollForward()
                }
            }
            val selectedRow = row ?: throw AssertionError("A cached sample row must be reachable")
            clickNode(selectedRow)
            awaitNode { it.text?.toString() == "Sample detail" }

            val readsBefore = AtomicInteger()
            val writesBefore = AtomicInteger()
            scene.onActivity { activity ->
                readsBefore.set(activity.shellFixture!!.counters.telemetryReads.get())
                writesBefore.set(activity.shellFixture!!.counters.localHistoryWrites.get())
                activity.shellFixture!!.switchOwnerImmediately("sample-owner-b")
            }

            awaitTrue("the fixture must observe the new history partition") {
                var observed = false
                scene.onActivity { observed = it.shellFixture!!.coordinator.session.value?.uid == "sample-owner-b" }
                observed
            }
            awaitTrue("the new owner's matching sample IDs must be populated") {
                var populated = false
                scene.onActivity {
                    populated =
                        it.shellFixture!!.counters.telemetryReads.get() > readsBefore.get() &&
                        it.shellFixture!!.counters.localHistoryWrites.get() > writesBefore.get()
                }
                populated
            }
            instrumentation.waitForIdleSync()
            assertNull("a selected sample detail from owner A cannot reopen on owner B's matching sampleId",
                find(instrumentation.uiAutomation.rootInActiveWindow) { it.text?.toString() == "Sample detail" })
        }
    }

    @Test fun cancelledSetupStateAndSavedInstanceParcelDoNotRetainSyntheticPat() {
        val marker = SharedInstrumentShellFixture.SYNTHETIC_PAT_MARKER
        clearFixturePreferences()
        launchShell().use { scene ->
            clickText("Continue with Google")
            awaitNode { it.text?.toString() == "Connect ThinQ in Settings to read and control your AC." }
            clickText("Settings")
            clickText("Set up ThinQ PAT")
            setTextByHint("ThinQ Personal Access Token", marker)

            clickText("Cancel")
            awaitNode { it.text?.toString() == "Set up ThinQ PAT" }
            assertNull(
                "cancelled plaintext entry is cleared from the form",
                find(instrumentation.uiAutomation.rootInActiveWindow) {
                    it.hintText?.toString() == "ThinQ Personal Access Token"
                },
            )
            scene.onActivity { assertEquals(0, it.shellFixture!!.counters.credentialSaves.get()) }

            // Re-enter the marker only into the ephemeral password field, then
            // force Activity save/recreation. The fixture inspects the in-memory
            // Bundle parcel only; it never opens a credential file.
            clickText("Set up ThinQ PAT")
            setTextByHint("ThinQ Personal Access Token", marker)
            SharedInstrumentPreviewActivity.lastSavedStateContainsSyntheticPatMarker = false
            scene.recreate()
            assertFalse(
                "plaintext PAT input is not written into the Activity saved-instance Bundle",
                SharedInstrumentPreviewActivity.lastSavedStateContainsSyntheticPatMarker,
            )
        }
    }

    @Test fun syntheticPatIsAbsentFromPreferencesHistoryDiagnosticsAndCapturedSafeRecords() = runBlocking {
        val marker = SharedInstrumentShellFixture.SYNTHETIC_PAT_MARKER
        val context = instrumentation.targetContext
        clearFixturePreferences()
        launchShell().use { scene ->
            clickText("Continue with Google")
            awaitNode { it.text?.toString() == "Connect ThinQ in Settings to read and control your AC." }
            clickText("Settings")
            clickText("Set up ThinQ PAT")
            setTextByHint("ThinQ Personal Access Token", marker)
            clickText("Verify and save securely")
            awaitNode {
                it.text?.toString()?.startsWith("The configured AC and profile were verified;") == true
            }
            clickText("History and storage")
            clickText("Detailed")
            awaitNode { it.text?.toString() == "Local diagnostics · nonsecret" }

            val visibleUi = accessibilitySnapshot(instrumentation.uiAutomation.rootInActiveWindow)
            val fixture = AtomicReference<SharedInstrumentShellFixture>()
            scene.onActivity { fixture.set(it.shellFixture!!) }
            val capturedSafeRecords = runBlocking {
                fixture.get().captureSafeDiagnosticOutputs().joinToString("\n")
            }
            val displayPreferences = context.getSharedPreferences(
                SharedInstrumentShellFixture.FIXTURE_INTERFACE_PREFERENCES_FILE,
                android.content.Context.MODE_PRIVATE,
            ).all.values.joinToString("\n")

            assertTrue("the dedicated display preference is readable", displayPreferences.contains("detailed"))
            assertTrue("verification log record captured", capturedSafeRecords.contains("credential-verification="))
            assertTrue("setup result log record captured", capturedSafeRecords.contains("credential-setup="))
            assertFalse("visible UI/diagnostics never render the plaintext PAT", marker in visibleUi)
            assertFalse("history/state/credential/log projections omit the plaintext PAT", marker in capturedSafeRecords)
            assertFalse("the installation display preference never contains the PAT", marker in displayPreferences)
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

    @Test fun temperatureDraftSurvivesHomeDeviceSettingsAndDetailRoundTrips() {
        clearFixturePreferences()
        launchShell().use { scene ->
            clickText("Continue with Google")
            awaitNode { it.text?.toString() == "Connect ThinQ in Settings to read and control your AC." }
            clickText("Settings")
            clickText("Set up ThinQ PAT")
            setTextByHint("ThinQ Personal Access Token", SharedInstrumentShellFixture.SYNTHETIC_PAT_MARKER)
            clickText("Verify and save securely")
            awaitNode { it.text?.toString()?.startsWith("The configured AC and profile were verified;") == true }

            clickText("Home")
            awaitNode { it.text?.toString() == "25" }
            awaitNode { it.contentDescription?.toString() == "Increase target temperature" }.performAction(
                AccessibilityNodeInfo.ACTION_CLICK,
            )
            awaitNode { it.text?.toString() == "26" }
            awaitNode { it.text?.toString()?.contains("Currently 25") == true }
            scene.onActivity { assertEquals(0, it.shellFixture!!.counters.thinQCommandSubmissions.get()) }

            // The shell owns the draft, so Settings and its immediate display
            // preference changes cannot dispose it or submit a command.
            clickText("Settings")
            val operationsBeforeSelection = AtomicReference<List<Int>>()
            scene.onActivity { operationsBeforeSelection.set(operationSnapshot(it.shellFixture!!)) }
            clickText("Detailed")
            clickText("Simple")
            scene.onActivity {
                assertEquals(operationsBeforeSelection.get(), operationSnapshot(it.shellFixture!!))
            }
            val homeEntryReadsBefore = AtomicInteger()
            scene.onActivity { homeEntryReadsBefore.set(it.shellFixture!!.counters.thinQLiveStateReads.get()) }
            clickText("Home")
            awaitTrue("Home's normal route-entry read remains eligible after preference selection") {
                var reads = 0
                scene.onActivity { reads = it.shellFixture!!.counters.thinQLiveStateReads.get() }
                reads > homeEntryReadsBefore.get()
            }
            awaitNode { it.text?.toString() == "26" }
            awaitNode { it.text?.toString()?.contains("Currently 25") == true }
            scene.onActivity { assertEquals(0, it.shellFixture!!.counters.thinQCommandSubmissions.get()) }

            val deviceEntryReadsBefore = AtomicInteger()
            scene.onActivity { deviceEntryReadsBefore.set(it.shellFixture!!.counters.thinQLiveStateReads.get()) }
            clickText("Device")
            awaitTrue("Device's normal route-entry read remains attributable to navigation") {
                var reads = 0
                scene.onActivity { reads = it.shellFixture!!.counters.thinQLiveStateReads.get() }
                reads > deviceEntryReadsBefore.get()
            }
            awaitNode { it.text?.toString() == "26" }
            awaitNode { it.text?.toString()?.contains("Currently 25") == true }
            val deviceDetailsReadsBefore = AtomicInteger()
            scene.onActivity { deviceDetailsReadsBefore.set(it.shellFixture!!.counters.thinQLiveStateReads.get()) }
            clickText("Device details")
            awaitNode { it.text?.toString()?.startsWith("Device ID:") == true }
            clickText("Device details")
            scene.onActivity {
                assertEquals(deviceDetailsReadsBefore.get(), it.shellFixture!!.counters.thinQLiveStateReads.get())
            }
            clickText("Settings")
            val deviceReentryReadsBefore = AtomicInteger()
            scene.onActivity { deviceReentryReadsBefore.set(it.shellFixture!!.counters.thinQLiveStateReads.get()) }
            clickText("Device")
            awaitTrue("returning to Device may run its normal route-entry read") {
                var reads = 0
                scene.onActivity { reads = it.shellFixture!!.counters.thinQLiveStateReads.get() }
                reads > deviceReentryReadsBefore.get()
            }
            awaitNode { it.text?.toString() == "26" }
            awaitNode { it.text?.toString()?.contains("Currently 25") == true }
            scene.onActivity { assertEquals(0, it.shellFixture!!.counters.thinQCommandSubmissions.get()) }

            clickText("Home")
            awaitNode { it.text?.toString() == "26" }
            var apply = awaitNode { it.text?.toString() == "Apply" }
            while (!apply.isClickable && apply.parent != null) apply = apply.parent
            assertTrue("the explicit Apply action remains available", apply.performAction(AccessibilityNodeInfo.ACTION_CLICK))
            awaitTrue("only the explicit Apply submits a command") {
                var count = 0
                scene.onActivity { count = it.shellFixture!!.counters.thinQCommandSubmissions.get() }
                count == 1
            }
        }
    }

    @Test fun interfaceDetailSelectionPreservesSetupFormAndConfirmationDialogWithoutPortCalls() {
        clearFixturePreferences()
        launchShell().use { scene ->
            clickText("Continue with Google")
            awaitNode { it.text?.toString() == "Connect ThinQ in Settings to read and control your AC." }
            clickText("Settings")
            clickText("Set up ThinQ PAT")
            setTextByHint("ThinQ Personal Access Token", SharedInstrumentShellFixture.SYNTHETIC_PAT_MARKER)
            setTextByHint("ThinQ account country", "US")
            val tokenBefore = awaitNode { it.hintText?.toString() == "ThinQ Personal Access Token" }.text?.toString()
            assertNotNull("the synthetic PAT remains represented by the protected-entry field", tokenBefore)
            val formOperations = AtomicReference<List<Int>>()
            scene.onActivity { formOperations.set(operationSnapshot(it.shellFixture!!)) }

            clickText("Detailed")
            clickText("Simple")
            val tokenAfter = awaitNode { it.hintText?.toString() == "ThinQ Personal Access Token" }.text?.toString()
            assertEquals(tokenBefore, tokenAfter, "the same-owner password field value survives preference changes")
            awaitNode { it.text?.toString() == "US" }
            scene.onActivity { assertEquals(formOperations.get(), operationSnapshot(it.shellFixture!!)) }

            clickText("Verify and save securely")
            awaitNode { it.text?.toString()?.startsWith("The configured AC and profile were verified;") == true }
            clickText("Remove ThinQ PAT")
            awaitNode { it.text?.toString() == "Remove ThinQ PAT?" }
            val dialogOperations = AtomicReference<List<Int>>()
            scene.onActivity { fixture ->
                dialogOperations.set(operationSnapshot(fixture.shellFixture!!))
                fixture.shellFixture!!.interfacePreferences.select(cloud.airchive.shared.InterfaceDetail.DETAILED)
            }
            awaitNode { it.text?.toString() == "Remove ThinQ PAT?" }
            scene.onActivity { assertEquals(dialogOperations.get(), operationSnapshot(it.shellFixture!!)) }
            clickText("Cancel")
            awaitNode { it.text?.toString() == "Remove ThinQ PAT" }
        }
    }

    @Test fun interfaceDetailSelectionDoesNotRestartOrCancelPendingCommand() {
        clearFixturePreferences()
        launchShell().use { scene ->
            clickText("Continue with Google")
            awaitNode { it.text?.toString() == "Connect ThinQ in Settings to read and control your AC." }
            clickText("Settings")
            clickText("Set up ThinQ PAT")
            setTextByHint("ThinQ Personal Access Token", SharedInstrumentShellFixture.SYNTHETIC_PAT_MARKER)
            clickText("Verify and save securely")
            awaitNode { it.text?.toString()?.startsWith("The configured AC and profile were verified;") == true }
            clickText("Home")
            awaitNode { it.text?.toString() == "25" }
            awaitNode { it.contentDescription?.toString() == "Increase target temperature" }.performAction(
                AccessibilityNodeInfo.ACTION_CLICK,
            )
            awaitNode { it.text?.toString() == "26" }

            val fixture = AtomicReference<SharedInstrumentShellFixture>()
            scene.onActivity {
                fixture.set(it.shellFixture!!)
                fixture.get().thinQGate.hold()
            }
            var apply = awaitNode { it.text?.toString() == "Apply" }
            while (!apply.isClickable && apply.parent != null) apply = apply.parent
            assertTrue(apply.performAction(AccessibilityNodeInfo.ACTION_CLICK))
            awaitTrue("the fake command reaches its deferred gate") {
                fixture.get().counters.thinQCommandSubmissions.get() == 1
            }
            awaitTrue("the graph-owned command remains pending while held") {
                fixture.get().thinQContext.commandController.state.value is
                    cloud.airchive.shared.data.thinq.AcCommandUiState.Sending
            }

            clickText("Settings")
            val pendingOperations = operationSnapshot(fixture.get())
            clickText("Detailed")
            clickText("Simple")
            scene.onActivity { assertEquals(pendingOperations, operationSnapshot(it.shellFixture!!)) }
            assertTrue(
                "preference changes do not cancel the graph-owned command",
                fixture.get().thinQContext.commandController.state.value is
                    cloud.airchive.shared.data.thinq.AcCommandUiState.Sending,
            )

            fixture.get().thinQGate.release()
            awaitTrue("the original command completes exactly once") {
                fixture.get().thinQContext.commandController.state.value is
                    cloud.airchive.shared.data.thinq.AcCommandUiState.Confirmed
            }
            clickText("Home")
            awaitNode { it.text?.toString()?.contains("Confirmed: AC currently reports") == true }
            assertEquals(1, fixture.get().counters.thinQCommandSubmissions.get())
        }
    }

    @Test fun ownerSwitchDiscardsTheShellHeldTemperatureDraft() {
        clearFixturePreferences()
        launchShell().use { scene ->
            clickText("Continue with Google")
            awaitNode { it.text?.toString() == "Connect ThinQ in Settings to read and control your AC." }
            clickText("Settings")
            clickText("Set up ThinQ PAT")
            setTextByHint("ThinQ Personal Access Token", SharedInstrumentShellFixture.SYNTHETIC_PAT_MARKER)
            clickText("Verify and save securely")
            awaitNode { it.text?.toString()?.startsWith("The configured AC and profile were verified;") == true }

            clickText("Home")
            awaitNode { it.text?.toString() == "25" }
            awaitNode { it.contentDescription?.toString() == "Increase target temperature" }.performAction(
                AccessibilityNodeInfo.ACTION_CLICK,
            )
            awaitNode { it.text?.toString() == "26" }
            awaitNode { it.text?.toString()?.contains("Currently 25") == true }
            scene.onActivity { it.shellFixture!!.setNextOwner("temperature-draft-owner-b") }

            clickText("Settings")
            clickText("Account actions")
            clickText("Switch Google account")
            awaitNode { it.text?.toString() == "Switch Google account?" }
            clickText("Confirm")
            awaitNode { it.text?.toString() == "Set up ThinQ PAT" }
            clickText("Home")
            awaitTrue("the new synthetic owner must not inherit the old setpoint draft") {
                val root = instrumentation.uiAutomation.rootInActiveWindow
                find(root) { it.text?.toString()?.contains("Currently 25") == true } == null &&
                    find(root) { it.text?.toString() == "26" } == null
            }
            scene.onActivity {
                assertEquals("temperature-draft-owner-b", it.shellFixture!!.coordinator.session.value?.uid)
                assertEquals(0, it.shellFixture!!.counters.thinQCommandSubmissions.get())
            }
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

    private fun setTextByHint(hint: String, value: String) {
        val field = awaitNode {
            it.className?.toString()?.contains("EditText") == true && it.hintText?.toString() == hint
        }
        val arguments = Bundle().apply {
            putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, value)
        }
        assertTrue(field.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, arguments))
        instrumentation.waitForIdleSync()
    }

    private fun clearFixturePreferences() {
        instrumentation.targetContext.getSharedPreferences(
            SharedInstrumentShellFixture.FIXTURE_INTERFACE_PREFERENCES_FILE,
            android.content.Context.MODE_PRIVATE,
        ).edit().clear().commit()
    }

    private fun operationSnapshot(fixture: SharedInstrumentShellFixture): List<Int> = with(fixture.counters) {
        listOf(
            ownerSignInAttempts.get(),
            ownerSignOuts.get(),
            telemetryReads.get(),
            deviceIdentityReads.get(),
            localHistoryObserves.get(),
            localHistoryWrites.get(),
            thinQDeviceListReads.get(),
            thinQProfileReads.get(),
            thinQLiveStateReads.get(),
            thinQCommandSubmissions.get(),
            credentialSaves.get(),
            credentialTokenReads.get(),
            credentialClears.get(),
            credentialVerifications.get(),
        )
    }

    private fun accessibilitySnapshot(node: AccessibilityNodeInfo?): String {
        if (node == null) return ""
        return buildString {
            node.text?.toString()?.let { append(it).append('\n') }
            node.contentDescription?.toString()?.let { append(it).append('\n') }
            node.hintText?.toString()?.let { append(it).append('\n') }
            for (index in 0 until node.childCount) {
                append(accessibilitySnapshot(node.getChild(index)))
            }
        }
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
