package cloud.airchive.personal.preview

import android.content.Intent
import android.app.UiModeManager
import android.os.ParcelFileDescriptor
import android.graphics.Bitmap
import android.os.Bundle
import android.os.SystemClock
import android.graphics.Rect
import android.view.accessibility.AccessibilityNodeInfo
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.StaleObjectException
import androidx.test.uiautomator.UiDevice
import java.io.File
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.flow.first

/** The debug-only preview never constructs a production graph or contacts an AC. */
@RunWith(AndroidJUnit4::class)
class SharedInstrumentUiTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val uiDevice by lazy { UiDevice.getInstance(instrumentation) }

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
            awaitShellSettled(scene)

            // Advancing only the controlled clock moves the usable-sample age
            // on the next Home composition. History may independently finish
            // its access-authorized bootstrap while the shell settles.
            scene.onActivity { it.shellFixture!!.clock.advanceBy(120_000) }
            clickTab("Settings")
            awaitNode { it.text?.toString() == "Interface detail" }
            clickTab("Home")
            awaitNode { it.text?.toString() == "Airchive" }
            awaitNode { it.text?.toString() == "Latest usable sample 3 min ago" }

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

            clickTab("Settings")
            clickText("Set up ThinQ PAT")
            awaitVisibleNode { it.text?.toString() == "Verify and save securely" }
            setTextByHint("ThinQ Personal Access Token", syntheticMarker)
            setTextByHint("ThinQ account country", "US")
            awaitNode { it.text?.toString() == "US" }
            scene.onActivity { it.shellFixture!!.setNextOwner("shell-fixture-owner-b") }

            // The real production account confirmation path clears the
            // protected fake store and changes the lifecycle owner partition.
            clickDisclosure("Account actions")
            clickText("Switch Google account")
            awaitNode { it.text?.toString() == "Switch Google account?" }
            clickText("Confirm")

            awaitVisibleNode { it.text?.toString() == "Set up ThinQ PAT" }
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
            awaitShellSettled(scene)
            clickText("Set up ThinQ PAT")
            awaitVisibleNode { it.text?.toString() == "Verify and save securely" }
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
            awaitVisibleNode { it.text?.toString() == "Verify and save securely" }
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
            awaitVisibleNode { it.text?.toString() == "Verify and save securely" }
            setTextByHint("ThinQ Personal Access Token", marker)
            clickText("Verify and save securely")
            awaitNode {
                it.text?.toString()?.startsWith("The configured AC and profile were verified;") == true
            }
            clickDisclosure("History and storage")
            selectShellDetail(scene, cloud.airchive.shared.InterfaceDetail.DETAILED)
            awaitVisibleNode { it.text?.toString() == "Local diagnostics · nonsecret" }

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
            awaitNode { it.text?.toString()?.contains("Currently 26") == true }
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
            awaitVisibleNode { it.text?.toString() == "Verify and save securely" }
            setTextByHint("ThinQ Personal Access Token", SharedInstrumentShellFixture.SYNTHETIC_PAT_MARKER)
            clickText("Verify and save securely")
            awaitNode { it.text?.toString()?.startsWith("The configured AC and profile were verified;") == true }

            clickTab("Home")
            awaitNode { it.text?.toString() == "Device read 0 sec ago" }
            awaitNode { it.text?.toString() == "25" }
            awaitVisibleNode { it.text?.toString() == "Temperature" }
            val increase = awaitVisibleNode { it.contentDescription?.toString() == "Increase target temperature" }
            clickNode(increase)
            awaitNode { it.text?.toString() == "26" }
            awaitNode { it.text?.toString()?.contains("Currently 25") == true }
            scene.onActivity { assertEquals(0, it.shellFixture!!.counters.thinQCommandSubmissions.get()) }

            // The shell owns the draft, so Settings and its immediate display
            // preference changes cannot dispose it or submit a command.
            clickText("Settings")
            val operationsBeforeSelection = AtomicReference<List<Int>>()
            scene.onActivity { operationsBeforeSelection.set(operationSnapshot(it.shellFixture!!)) }
            selectShellDetail(scene, cloud.airchive.shared.InterfaceDetail.DETAILED)
            selectShellDetail(scene, cloud.airchive.shared.InterfaceDetail.SIMPLE)
            scene.onActivity {
                assertEquals(operationsBeforeSelection.get(), operationSnapshot(it.shellFixture!!))
            }
            val homeEntryReadsBefore = AtomicInteger()
            scene.onActivity { homeEntryReadsBefore.set(it.shellFixture!!.counters.thinQLiveStateReads.get()) }
            clickTab("Home")
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
            clickTab("Device")
            awaitTrue("Device's normal route-entry read remains attributable to navigation") {
                var reads = 0
                scene.onActivity { reads = it.shellFixture!!.counters.thinQLiveStateReads.get() }
                reads > deviceEntryReadsBefore.get()
            }
            awaitNode { it.text?.toString() == "26" }
            awaitNode { it.text?.toString()?.contains("Currently 25") == true }
            val deviceDetailsReadsBefore = AtomicInteger()
            scene.onActivity { deviceDetailsReadsBefore.set(it.shellFixture!!.counters.thinQLiveStateReads.get()) }
            clickDisclosure("Device details")
            awaitNode { it.text?.toString()?.startsWith("Device ID:") == true }
            clickDisclosure("Device details")
            scene.onActivity {
                assertEquals(deviceDetailsReadsBefore.get(), it.shellFixture!!.counters.thinQLiveStateReads.get())
            }
            clickText("Settings")
            val deviceReentryReadsBefore = AtomicInteger()
            scene.onActivity { deviceReentryReadsBefore.set(it.shellFixture!!.counters.thinQLiveStateReads.get()) }
            clickTab("Device")
            awaitTrue("returning to Device may run its normal route-entry read") {
                var reads = 0
                scene.onActivity { reads = it.shellFixture!!.counters.thinQLiveStateReads.get() }
                reads > deviceReentryReadsBefore.get()
            }
            awaitNode { it.text?.toString() == "26" }
            awaitNode { it.text?.toString()?.contains("Currently 25") == true }
            scene.onActivity { assertEquals(0, it.shellFixture!!.counters.thinQCommandSubmissions.get()) }

            clickTab("Home")
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
            awaitVisibleNode { it.text?.toString() == "Verify and save securely" }
            setTextByHint("ThinQ Personal Access Token", SharedInstrumentShellFixture.SYNTHETIC_PAT_MARKER)
            setTextByHint("ThinQ account country", "US")
            assertEquals("both private setup inputs remain present", 2, awaitEditableFields().size)
            val formOperations = AtomicReference<List<Int>>()
            scene.onActivity { formOperations.set(operationSnapshot(it.shellFixture!!)) }

            selectShellDetail(scene, cloud.airchive.shared.InterfaceDetail.DETAILED)
            selectShellDetail(scene, cloud.airchive.shared.InterfaceDetail.SIMPLE)
            assertEquals("both private setup inputs remain present after preference changes", 2, awaitEditableFields().size)
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
            awaitVisibleNode { it.text?.toString() == "Verify and save securely" }
            setTextByHint("ThinQ Personal Access Token", SharedInstrumentShellFixture.SYNTHETIC_PAT_MARKER)
            clickText("Verify and save securely")
            awaitNode { it.text?.toString()?.startsWith("The configured AC and profile were verified;") == true }
            clickTab("Home")
            awaitNode { it.text?.toString() == "Device read 0 sec ago" }
            awaitNode { it.text?.toString() == "25" }
            clickNode(awaitNode { it.contentDescription?.toString() == "Increase target temperature" })
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
            selectShellDetail(scene, cloud.airchive.shared.InterfaceDetail.DETAILED)
            selectShellDetail(scene, cloud.airchive.shared.InterfaceDetail.SIMPLE)
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
            clickTab("Home")
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
            awaitShellSettled(scene)
            clickText("Set up ThinQ PAT")
            awaitVisibleNode { it.text?.toString() == "Verify and save securely" }
            setTextByHint("ThinQ Personal Access Token", SharedInstrumentShellFixture.SYNTHETIC_PAT_MARKER)
            clickText("Verify and save securely")
            awaitNode { it.text?.toString()?.startsWith("The configured AC and profile were verified;") == true }

            clickTab("Home")
            awaitNode { it.text?.toString() == "Device read 0 sec ago" }
            awaitNode { it.text?.toString() == "25" }
            clickNode(awaitNode { it.contentDescription?.toString() == "Increase target temperature" })
            awaitNode { it.text?.toString() == "26" }
            awaitNode { it.text?.toString()?.contains("Currently 25") == true }
            scene.onActivity { it.shellFixture!!.setNextOwner("temperature-draft-owner-b") }

            clickText("Settings")
            clickDisclosure("Account actions")
            assertDisclosureState("Account actions", "Expanded")
            clickVisibleText(
                "Switch Google account",
                awaitVisibleNode { it.text?.toString() == "Switch Google account" },
            )
            awaitNode { it.text?.toString() == "Switch Google account?" }
            clickText("Confirm")
            awaitVisibleNode { it.text?.toString() == "Set up ThinQ PAT" }
            clickTab("Home")
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

    @Test fun installationInterfaceChoiceSurvivesCredentialCacheAndSignOutClearing() {
        clearFixturePreferences()
        launchShell().use { scene ->
            clickText("Continue with Google")
            awaitNode { it.text?.toString() == "Connect ThinQ in Settings to read and control your AC." }
            awaitShellSettled(scene)

            clickText("Settings")
            awaitVisibleNode { it.text?.toString() == "Interface detail" }
            selectShellDetail(scene, cloud.airchive.shared.InterfaceDetail.DETAILED)
            val fixture = AtomicReference<SharedInstrumentShellFixture>()
            scene.onActivity { fixture.set(it.shellFixture!!) }
            assertEquals(cloud.airchive.shared.InterfaceDetail.DETAILED,
                fixture.get().interfacePreferences.detail.value)
            assertEquals("detailed", instrumentation.targetContext.getSharedPreferences(
                SharedInstrumentShellFixture.FIXTURE_INTERFACE_PREFERENCES_FILE,
                android.content.Context.MODE_PRIVATE,
            ).getString("interface_detail", null))

            // Seed and clear only the in-memory synthetic credential through
            // the same production lifecycle binding; no PAT text enters UI,
            // Android Keystore, settings storage, or the debug preference file.
            val bindings = fixture.get().thinQContext.bindings()
            val setup = runBlocking {
                bindings.setupCredential(SharedInstrumentShellFixture.SYNTHETIC_PAT_MARKER, "US")
            }
            assertTrue(setup.result is cloud.airchive.shared.data.onboarding.ThinQCredentialSetupResult.Success)
            assertTrue(runBlocking { bindings.removeCredential() }.removed)
            scene.onActivity {
                assertEquals(cloud.airchive.shared.InterfaceDetail.DETAILED,
                    it.shellFixture!!.interfacePreferences.detail.value)
            }

            val owner = requireNotNull(fixture.get().coordinator.session.value)
            fixture.get().accountController.requestClearCache()
            assertTrue(runBlocking { fixture.get().accountController.clearCacheConfirmed() }
                is cloud.airchive.shared.data.onboarding.OwnerSettingsActionResult.Success)
            scene.onActivity {
                assertEquals(cloud.airchive.shared.InterfaceDetail.DETAILED,
                    it.shellFixture!!.interfacePreferences.detail.value)
                assertEquals(owner.uid, it.shellFixture!!.coordinator.session.value?.uid)
            }

            fixture.get().accountController.requestSignOut()
            assertTrue(runBlocking { fixture.get().accountController.signOutConfirmed() }
                is cloud.airchive.shared.data.onboarding.OwnerSettingsActionResult.Success)
            scene.onActivity {
                assertEquals(cloud.airchive.shared.InterfaceDetail.DETAILED,
                    it.shellFixture!!.interfacePreferences.detail.value)
                assertNull(it.shellFixture!!.coordinator.session.value)
            }

            clickTab("Home")
            clickText("Continue with Google")
            awaitNode { it.text?.toString() == "Connect ThinQ in Settings to read and control your AC." }
            awaitShellSettled(scene)
            clickText("Settings")
            scene.onActivity {
                assertEquals("a new signed-in session reuses the installation choice",
                    cloud.airchive.shared.InterfaceDetail.DETAILED,
                    it.shellFixture!!.interfacePreferences.detail.value)
            }
        }
        clearFixturePreferences()
    }

    @Test fun syntheticOwnerSignOutRequiresAndCompletesTheSettingsConfirmation() {
        clearFixturePreferences()
        launchShell().use { scene ->
            clickText("Continue with Google")
            awaitNode { it.text?.toString() == "Connect ThinQ in Settings to read and control your AC." }
            awaitShellSettled(scene)
            clickTab("Settings")
            clickDisclosure("Account actions")
            clickText("Sign out of Google")
            awaitNode { it.text?.toString() == "Sign out of Google?" }
            scene.onActivity {
                assertNotNull("the fake owner remains signed in until confirmation",
                    it.shellFixture!!.coordinator.session.value)
            }

            clickText("Confirm")
            awaitNode { it.text?.toString() == "Google account: signed out" }
            scene.onActivity {
                assertNull("the Settings confirmation ends only the synthetic session",
                    it.shellFixture!!.coordinator.session.value)
                assertEquals(1, it.shellFixture!!.counters.ownerSignOuts.get())
                assertEquals(0, it.shellFixture!!.counters.thinQCommandSubmissions.get())
            }
        }
        clearFixturePreferences()
    }

    @Test fun productionFreshnessSourcesRemainIndependentUnderControlledClock() {
        clearFixturePreferences()
        launchShell().use { scene ->
            clickText("Continue with Google")
            awaitNode { it.text?.toString() == "Connect ThinQ in Settings to read and control your AC." }
            clickText("Settings")
            awaitShellSettled(scene)
            clickText("Set up ThinQ PAT")
            awaitVisibleNode { it.text?.toString() == "Verify and save securely" }
            setTextByHint("ThinQ Personal Access Token", SharedInstrumentShellFixture.SYNTHETIC_PAT_MARKER)
            clickText("Verify and save securely")
            awaitNode { it.text?.toString()?.startsWith("The configured AC and profile were verified;") == true }
            clickTab("Home")
            awaitVisibleNode { it.text?.toString() == "Device read 0 sec ago" }
            awaitVisibleNode { it.text?.toString()?.startsWith("Latest usable sample ") == true }
            awaitVisibleNode { it.text?.toString() == "History synced 0 sec ago" }

            val fixture = AtomicReference<SharedInstrumentShellFixture>()
            scene.onActivity { fixture.set(it.shellFixture!!) }
            val owner = requireNotNull(fixture.get().coordinator.session.value)
            val partition = fixture.get().coordinator.partitionFor(owner)
            val initialSnapshot = runBlocking { fixture.get().coordinator.local.observe(partition).first() }
            val successfulSyncBefore = requireNotNull(initialSnapshot.sync?.lastSuccessfulSyncEpochMillis)
            val latestUsableSampleEnd = initialSnapshot.observations
                .filter { sample ->
                    sample.quality == cloud.airchive.shared.contract.telemetry.ObservationQuality.NORMAL &&
                        sample.energyValue?.isFinite() == true && sample.recordedUnit != null &&
                        sample.energySourceFailure?.ok != false
                }
                .maxOf { it.intervalEndEpochMillis }
            val initialDeviceReadAt = currentDeviceReadAt(fixture.get())
            val telemetryReadsBeforeDetailRecompose = fixture.get().counters.telemetryReads.get()
            assertEquals(0, fixture.get().counters.thinQCommandSubmissions.get())

            // Advance only the controlled clock. The rendered ages retain
            // their separate device, usable-sample and history-sync origins.
            scene.onActivity { fixture.get().clock.advanceBy(5 * 60_000L) }
            selectShellDetail(scene, cloud.airchive.shared.InterfaceDetail.DETAILED)
            selectShellDetail(scene, cloud.airchive.shared.InterfaceDetail.SIMPLE)
            assertEquals("detail selection alone does not refresh history",
                telemetryReadsBeforeDetailRecompose, fixture.get().counters.telemetryReads.get())
            assertEquals("detail selection preserves the last successful sync origin",
                successfulSyncBefore,
                runBlocking { fixture.get().coordinator.local.observe(partition).first().sync?.lastSuccessfulSyncEpochMillis },
            )
            assertEquals("the device observation timestamp remains independent of elapsed time",
                initialDeviceReadAt, currentDeviceReadAt(fixture.get()))
            assertEquals(5 * 60_000L, fixture.get().clock.now() - currentDeviceReadAt(fixture.get()))
            assertEquals("usable sample age advances without counting newer unusable rows", 6L,
                (fixture.get().clock.now() - latestUsableSampleEnd) / 60_000L)
            assertEquals("history age advances independently", 5L,
                (fixture.get().clock.now() - successfulSyncBefore) / 60_000L)

            val telemetryBeforeRefresh = AtomicInteger()
            scene.onActivity { telemetryBeforeRefresh.set(fixture.get().counters.telemetryReads.get()) }
            clickText("Refresh history")
            awaitTrue("manual history refresh reaches the isolated telemetry fake") {
                fixture.get().counters.telemetryReads.get() > telemetryBeforeRefresh.get()
            }
            awaitShellSettled(scene)
            assertEquals("history refresh does not replace the live device-read timestamp",
                initialDeviceReadAt, currentDeviceReadAt(fixture.get()))
            val afterManualRefresh = runBlocking { fixture.get().coordinator.local.observe(partition).first() }
            val syncAtManualRefresh = requireNotNull(afterManualRefresh.sync?.lastSuccessfulSyncEpochMillis)
            assertEquals(fixture.get().clock.now(), syncAtManualRefresh)
            assertEquals(latestUsableSampleEnd, afterManualRefresh.observations
                .filter { sample -> sample.quality == cloud.airchive.shared.contract.telemetry.ObservationQuality.NORMAL &&
                    sample.energyValue?.isFinite() == true && sample.recordedUnit != null &&
                    sample.energySourceFailure?.ok != false }
                .maxOf { it.intervalEndEpochMillis })

            // Now only the fake device-read timestamp moves to the controlled
            // clock; sampled-data and sync freshness remain independent.
            val liveReadsBeforeDeviceEntry = AtomicInteger()
            scene.onActivity {
                liveReadsBeforeDeviceEntry.set(fixture.get().counters.thinQLiveStateReads.get())
                fixture.get().setThinQLiveReadTimestamp(fixture.get().clock.now())
            }
            clickText("Device")
            awaitTrue("Device route entry performs its own live read") {
                fixture.get().counters.thinQLiveStateReads.get() > liveReadsBeforeDeviceEntry.get()
            }
            awaitTrue("the synthetic live read publishes its current state") {
                fixture.get().thinQContext.liveStateController.state.value is
                    cloud.airchive.shared.data.thinq.ThinQLiveRefreshState.Current
            }
            assertEquals("Device route reads use the controlled device-read clock",
                fixture.get().clock.now(), currentDeviceReadAt(fixture.get()))
            clickTab("Home")

            scene.onActivity { fixture.get().clock.advanceBy(2 * 60_000L) }
            selectShellDetail(scene, cloud.airchive.shared.InterfaceDetail.DETAILED)
            selectShellDetail(scene, cloud.airchive.shared.InterfaceDetail.SIMPLE)
            assertEquals("only time passage advances the independent device-read age",
                2 * 60_000L, fixture.get().clock.now() - currentDeviceReadAt(fixture.get()))
            assertEquals("usable sample age advances on the same fixed sample", 8L,
                (fixture.get().clock.now() - latestUsableSampleEnd) / 60_000L)
            assertEquals("history age advances from its own last-success timestamp", 2L,
                (fixture.get().clock.now() - syncAtManualRefresh) / 60_000L)
            assertEquals("freshness checks never submit device commands", 0,
                fixture.get().counters.thinQCommandSubmissions.get())
        }
        clearFixturePreferences()
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
            clickDisclosure("Account actions")
            clickText("Clear local history cache")
            awaitNode { it.text?.toString() == "Clear local Airchive cache?" }
            clickText("Cancel")
            awaitNode { it.text?.toString() == "Clear local history cache" }
        }
    }

    @Test fun productionDisclosureDefaultsRespectVisibleRouteSections() {
        clearFixturePreferences()
        launchShell().use { scene ->
            // A recovery-required disclosure outranks Simple's normal collapse
            // default, even before an owner has signed in.
            clickTab("Settings")
            awaitVisibleNode { it.text?.toString() == "Interface detail" }
            assertDisclosureState("History access", "Expanded")
            assertTextPresent("Status: signed out of Google.")

            clickTab("Home")
            clickText("Continue with Google")
            awaitNode { it.text?.toString() == "Connect ThinQ in Settings to read and control your AC." }
            awaitTrue("the fake history access check completes") {
                var reads = 0
                scene.onActivity { reads = it.shellFixture!!.counters.deviceIdentityReads.get() }
                reads > 0
            }
            awaitNode { it.text?.toString() == "History synced 0 sec ago" }
            awaitShellSettled(scene)

            // Simple keeps all optional information and action sections closed.
            clickTab("Device")
            assertDisclosureState("Device details", "Collapsed")
            assertTextAbsent("Device ID:")

            clickTab("Energy")
            assertDisclosureState("Coverage and measurement details", "Collapsed")
            assertDisclosureState("Daily record", "Collapsed")
            assertDisclosureState("Units and conversions", "Collapsed")
            assertTextAbsent("Period summary")
            assertTextAbsent("Elapsed five-minute slots:")
            assertTextAbsent("Interval values retain their recorded units.")
            // A manual override is permitted, and exposes the daily children.
            clickDisclosure("Daily record")
            assertDisclosureState("Daily record", "Expanded")
            awaitVisibleNode { it.text?.toString()?.startsWith("Elapsed five-minute slots:") == true }

            clickTab("History")
            awaitNode { it.text?.toString() == "Cached observations (newest first)" }
            assertDisclosureState("Sync and collector health", "Collapsed")
            assertDisclosureState("Sample status guide", "Collapsed")
            assertTextAbsent("What each sample status means")

            clickTab("Settings")
            scrollToTop()
            awaitVisibleNode { it.text?.toString() == "Interface detail" }
            selectShellDetail(scene, cloud.airchive.shared.InterfaceDetail.DETAILED)
            selectShellDetail(scene, cloud.airchive.shared.InterfaceDetail.SIMPLE)
            scene.onActivity {
                assertEquals(cloud.airchive.shared.InterfaceDetail.SIMPLE,
                    it.shellFixture!!.interfacePreferences.detail.value)
            }
            assertDisclosureState("Connection details", "Collapsed")
            assertDisclosureState("Account actions", "Collapsed")
            assertTextAbsent("LG client ID:")
            assertTextAbsent("Switch Google account")

            // Manual expansion is visible until a mode change; changing modes
            // resets informational sections to their defaults.
            clickDisclosure("Connection details")
            assertDisclosureState("Connection details", "Expanded")
            awaitVisibleNode { it.text?.toString()?.startsWith("LG client ID:") == true }
            scrollToTop()
            selectShellDetail(scene, cloud.airchive.shared.InterfaceDetail.DETAILED)
            assertDisclosureState("Connection details", "Expanded")
            assertDisclosureState("Account actions", "Collapsed")
            awaitVisibleNode { it.text?.toString()?.startsWith("LG client ID:") == true }
            assertTextAbsent("Switch Google account")

            clickDisclosure("Connection details")
            assertDisclosureState("Connection details", "Collapsed")
            scrollToTop()
            selectShellDetail(scene, cloud.airchive.shared.InterfaceDetail.SIMPLE)
            assertDisclosureState("Connection details", "Collapsed")
            selectShellDetail(scene, cloud.airchive.shared.InterfaceDetail.DETAILED)
            assertDisclosureState("Connection details", "Expanded")
            assertDisclosureState("Account actions", "Collapsed")

            // The action section never follows Detailed's informational-open rule.
            clickDisclosure("Account actions")
            assertDisclosureState("Account actions", "Expanded")
            assertTextPresent("Switch Google account")
            scrollToTop()
            selectShellDetail(scene, cloud.airchive.shared.InterfaceDetail.SIMPLE)
            assertDisclosureState("Account actions", "Collapsed")
            assertTextAbsent("Switch Google account")
            scrollToTop()
            selectShellDetail(scene, cloud.airchive.shared.InterfaceDetail.DETAILED)
            assertDisclosureState("Account actions", "Collapsed")

            // Settings/cache confirmation is outside the resetting disclosure.
            clickDisclosure("Account actions")
            scene.onActivity { it.shellFixture!!.accountController.requestClearCache() }
            awaitNode { it.text?.toString() == "Clear local Airchive cache?" }
            scene.onActivity {
                it.shellFixture!!.interfacePreferences.select(cloud.airchive.shared.InterfaceDetail.SIMPLE)
            }
            awaitNode { it.text?.toString() == "Clear local Airchive cache?" }
            scene.onActivity { it.shellFixture!!.accountController.dismissConfirmation() }

            // Detailed informational sections expand across the other routes;
            // returning to Simple restores the collapsed defaults.
            scrollToTop()
            selectShellDetail(scene, cloud.airchive.shared.InterfaceDetail.DETAILED)
            clickTab("Device")
            assertDisclosureState("Device details", "Expanded")
            assertTextPresent("Device ID:")

            clickTab("Energy")
            awaitVisibleNode { it.text?.toString() == "Sampled five-minute interval energy" }
            awaitVisibleNode { it.text?.toString()?.startsWith("Elapsed five-minute slots:") == true }
            awaitVisibleNode { it.text?.toString()?.startsWith("Interval values retain their recorded units.") == true }

            val fixture = AtomicReference<SharedInstrumentShellFixture>()
            scene.onActivity { fixture.set(it.shellFixture!!) }
            val owner = requireNotNull(fixture.get().coordinator.session.value)
            runBlocking {
                fixture.get().coordinator.local.clear(fixture.get().coordinator.partitionFor(owner))
            }
            clickTab("History")
            assertDisclosureState("Sync and collector health", "Expanded")
            assertDisclosureState("Sample status guide", "Expanded")
            assertTextPresent("What each sample status means")
            awaitVisibleNode { it.text?.toString()?.contains("A null or missing energy value is a gap") == true }

            clickTab("Settings")
            scrollToTop()
            selectShellDetail(scene, cloud.airchive.shared.InterfaceDetail.SIMPLE)
            clickTab("Device")
            assertDisclosureState("Device details", "Collapsed")
            clickTab("Energy")
            assertDisclosureState("Daily record", "Collapsed")
            clickTab("History")
            assertDisclosureState("Sample status guide", "Collapsed")
            assertTextAbsent("What each sample status means")
            // Home intentionally has no secondary disclosures.
            clickTab("Home")
            awaitNode { it.text?.toString() == "Airchive" }
            assertTextAbsent("Device details")
            assertTextAbsent("Daily record")
            assertTextAbsent("Sample status guide")
        }
        clearFixturePreferences()
    }

    @Test fun energySummaryAndCoverageBadgeAreIdenticalInSimpleAndDetailed() {
        clearFixturePreferences()
        launchShell().use { scene ->
            clickText("Continue with Google")
            awaitNode { it.text?.toString() == "Connect ThinQ in Settings to read and control your AC." }
            awaitNode { it.text?.toString() == "History synced 0 sec ago" }
            awaitShellSettled(scene)

            clickTab("Settings")
            selectShellDetail(scene, cloud.airchive.shared.InterfaceDetail.DETAILED)
            clickTab("Energy")
            awaitNode { it.text?.toString() == "Sampled energy" }
            val detailedTruth = energySummarySnapshot()

            clickTab("Settings")
            selectShellDetail(scene, cloud.airchive.shared.InterfaceDetail.SIMPLE)
            clickTab("Energy")
            awaitNode { it.text?.toString() == "Sampled energy" }
            assertEquals(detailedTruth, energySummarySnapshot())
        }
        clearFixturePreferences()
    }

    @Test fun energyPeriodBrowsingIsCachedUntilExplicitLoadAndCoveredRevisitsAreLocal() {
        clearFixturePreferences()
        launchShell().use { scene ->
            clickText("Continue with Google")
            awaitNode { it.text?.toString() == "Connect ThinQ in Settings to read and control your AC." }
            awaitNode { it.text?.toString() == "History synced 0 sec ago" }
            awaitShellSettled(scene)
            clickText("Energy")
            awaitVisibleNode { it.text?.toString() == "Load sampled intervals" }

            val fixture = AtomicReference<SharedInstrumentShellFixture>()
            val readsBeforeBrowsing = AtomicInteger()
            scene.onActivity {
                fixture.set(it.shellFixture!!)
                readsBeforeBrowsing.set(fixture.get().counters.telemetryReads.get())
            }
            clickText("Week")
            clickText("Month")
            clickText("Previous")
            clickText("Next")
            clickText("Day")
            scene.onActivity {
                assertEquals("period and date selection only changes cached presentation",
                    readsBeforeBrowsing.get(), fixture.get().counters.telemetryReads.get())
            }

            // The current month extends well beyond the synthetic bootstrap
            // cache, so only this explicit action may request the missing range.
            clickText("Month")
            val readsBeforeLoad = AtomicInteger()
            val writesBeforeLoad = AtomicInteger()
            scene.onActivity {
                readsBeforeLoad.set(fixture.get().counters.telemetryReads.get())
                writesBeforeLoad.set(fixture.get().counters.localHistoryWrites.get())
            }
            clickText("Load sampled intervals")
            awaitNode { it.text?.toString()?.contains("remote page(s) fetched") == true }
            scene.onActivity {
                assertTrue(fixture.get().counters.telemetryReads.get() > readsBeforeLoad.get())
                assertTrue(fixture.get().counters.localHistoryWrites.get() > writesBeforeLoad.get())
            }
            awaitShellSettled(scene)
            val owner = requireNotNull(fixture.get().coordinator.session.value)
            val partition = fixture.get().coordinator.partitionFor(owner)
            val now = fixture.get().clock.now()
            val selectedDate = kotlinx.datetime.LocalDate.parse(
                java.time.Instant.ofEpochMilli(now).atZone(java.time.ZoneId.of("Asia/Manila")).toLocalDate().toString(),
            )
            val month = cloud.airchive.shared.data.energy.energyPeriodRange(
                cloud.airchive.shared.data.energy.EnergyPeriodType.MONTH,
                selectedDate,
            )
            val afterFirstLoad = runBlocking {
                fixture.get().coordinator.local.observe(partition).first()
            }
            val remainingGaps = cloud.airchive.shared.data.sync.HistoricalRangeLoader.uncoveredRanges(
                month.startEpochMillis,
                minOf(month.endExclusiveEpochMillis, now),
                afterFirstLoad.coveredRanges,
            )
            assertTrue("the first explicit load records coverage for the elapsed month: $remainingGaps", remainingGaps.isEmpty())

            val readsAfterLoad = AtomicInteger()
            scene.onActivity { readsAfterLoad.set(fixture.get().counters.telemetryReads.get()) }
            clickText("Load sampled intervals")
            awaitLoadButtonEnabled()
            awaitShellSettled(scene)
            scene.onActivity {
                assertEquals("a covered revisit performs no telemetry reads",
                    readsAfterLoad.get(), fixture.get().counters.telemetryReads.get())
            }

            val readsBeforeFuture = AtomicInteger()
            scene.onActivity { readsBeforeFuture.set(fixture.get().counters.telemetryReads.get()) }
            clickText("Next")
            clickText("Load sampled intervals")
            awaitLoadButtonEnabled()
            scene.onActivity {
                assertEquals("an entirely future period is skipped locally",
                    readsBeforeFuture.get(), fixture.get().counters.telemetryReads.get())
            }
        }
        clearFixturePreferences()
    }

    @Test fun productionRoutesPreserveInstrumentHierarchyAndConfiguredTruth() {
        clearFixturePreferences()
        launchShell().use { scene ->
            clickText("Continue with Google")
            awaitNode { it.text?.toString() == "Connect ThinQ in Settings to read and control your AC." }
            clickText("Settings")
            awaitShellSettled(scene)
            clickText("Set up ThinQ PAT")
            awaitVisibleNode { it.text?.toString() == "Verify and save securely" }
            setTextByHint("ThinQ Personal Access Token", SharedInstrumentShellFixture.SYNTHETIC_PAT_MARKER)
            clickText("Verify and save securely")
            awaitNode { it.text?.toString()?.startsWith("The configured AC and profile were verified;") == true }
            clickTab("Home")
            awaitNode { it.text?.toString() == "Device read 0 sec ago" }

            listOf("Home", "Device", "Energy", "History", "Settings").forEach(::assertTextPresent)
            assertTextPresent("Set temperature")
            assertTextPresent("Sampled today")
            assertTextPresent("Temperature")
            assertTextPresent("Apply")
            assertTextPresent("Mode")
            assertTextPresent("Fan speed")

            val fixture = AtomicReference<SharedInstrumentShellFixture>()
            scene.onActivity { fixture.set(it.shellFixture!!) }
            fixture.get().setThinQReadFailure(IllegalStateException("synthetic fake read failure"))
            clickText("Device")
            awaitNode { it.text?.toString() == "The device read failed. Check the connection and retry." }
            assertTextAbsent("Sampled today")
            assertFalse(awaitNode { it.contentDescription?.toString() == "Increase target temperature" }.isEnabled)

            fixture.get().setThinQReadFailure(null)
            clickText("Refresh device state")
            awaitTrue("a successful synthetic re-read restores supported controls") {
                find(instrumentation.uiAutomation.rootInActiveWindow) {
                    it.contentDescription?.toString() == "Increase target temperature"
                }?.isEnabled == true
            }
            assertTextAbsent("The device read failed. Check the connection and retry.")
            assertTextPresent("Set temperature")
            assertTextPresent("Apply")
            assertTextPresent("Mode")
            assertTextPresent("Fan speed")

            clickText("Energy")
            awaitNode { it.text?.toString() == "Sampled energy" }
            awaitVisibleNode { it.text?.toString() == "Load sampled intervals" }
            assertTextPresent("Coverage and measurement details")
            val energyTree = accessibilitySnapshot(instrumentation.uiAutomation.rootInActiveWindow)
            assertTrue(energyTree.indexOf("Sampled energy") < energyTree.indexOf("Download this period"))

            clickText("History")
            awaitVisibleNode { it.text?.toString() == "Cached observations (newest first)" }
            val firstSample = awaitNode { it.contentDescription?.toString()?.startsWith("Sample at ") == true }
            clickNode(firstSample)
            awaitNode { it.text?.toString() == "Sample detail" }
            clickText("Close")

            clickText("Settings")
            awaitNode { it.text?.toString() == "Interface detail" }
            assertTextPresent("AC connection")
            assertTextPresent("Connection details")
            assertTextPresent("History access")
            assertTextPresent("History and storage")
            assertTextPresent("Account actions")
            assertEquals(0, fixture.get().counters.thinQCommandSubmissions.get())
        }
        clearFixturePreferences()
    }

    @Test fun historyEmptyNoUsableOfflineDeniedLoadingAndErrorStatesStayTruthful() {
        clearFixturePreferences()
        launchShell().use { scene ->
            clickText("Continue with Google")
            awaitNode { it.text?.toString() == "Connect ThinQ in Settings to read and control your AC." }
            awaitNode { it.text?.toString() == "History synced 0 sec ago" }
            awaitShellSettled(scene)
            val fixture = AtomicReference<SharedInstrumentShellFixture>()
            scene.onActivity { fixture.set(it.shellFixture!!) }
            val owner = fixture.get().coordinator.session.value
                ?: throw AssertionError("the synthetic owner sign-in must complete")
            val partition = fixture.get().coordinator.partitionFor(owner)
            val now = fixture.get().clock.now()

            runBlocking { fixture.get().coordinator.local.clear(partition) }
            clickText("History")
            awaitNode { it.text?.toString() == "No cached observations yet" }
            assertTextAbsent("Cached, but no usable measurements yet")

            val invalidSamples = listOf(
                cloud.airchive.shared.contract.telemetry.Observation(
                    sampleId = "4-4-anomalous", intervalStartEpochMillis = now - 600_000L,
                    intervalEndEpochMillis = now - 300_000L, energyValue = 45.0,
                    recordedUnit = cloud.airchive.shared.contract.telemetry.EnergyUnit.WH,
                    quality = cloud.airchive.shared.contract.telemetry.ObservationQuality.ANOMALOUS,
                ),
                cloud.airchive.shared.contract.telemetry.Observation(
                    sampleId = "4-4-unknown", intervalStartEpochMillis = now - 300_000L,
                    intervalEndEpochMillis = now, energyValue = 25.0, recordedUnit = null,
                ),
            )
            runBlocking { fixture.get().coordinator.local.upsertObservations(partition, invalidSamples) }
            awaitNode { it.text?.toString() == "Cached, but no usable measurements yet" }
            awaitNode { it.text?.toString()?.contains("never counted as measured zero") == true }

            val retainedSample = cloud.airchive.shared.contract.telemetry.Observation(
                sampleId = "4-4-retained", intervalStartEpochMillis = now - 600_000L,
                intervalEndEpochMillis = now - 300_000L, energyValue = 21.0,
                recordedUnit = cloud.airchive.shared.contract.telemetry.EnergyUnit.WH,
            )
            runBlocking {
                fixture.get().coordinator.local.clear(partition)
                fixture.get().coordinator.local.upsertObservations(partition, listOf(retainedSample))
            }
            clickTab("Home")
            awaitNode { it.text?.toString() == "Airchive" }

            fixture.get().setTelemetryFailure(cloud.airchive.shared.contract.telemetry.TelemetryReadFailure.Offline())
            clickText("Refresh history")
            awaitNode { it.text?.toString()?.contains("could not be reached. Showing cached history") == true }
            awaitShellSettled(scene)
            clickText("History")
            awaitNode { it.contentDescription?.toString()?.contains("21 Wh") == true }

            clickTab("Home")
            awaitNode { it.text?.toString() == "Airchive" }
            awaitShellSettled(scene)
            fixture.get().setTelemetryFailure(cloud.airchive.shared.contract.telemetry.TelemetryReadFailure.AccessDenied())
            val readsBeforeDenied = AtomicInteger()
            scene.onActivity { readsBeforeDenied.set(fixture.get().counters.telemetryReads.get()) }
            clickText("Refresh history")
            awaitTrue("the denied-history retry reaches the synthetic telemetry fake") {
                fixture.get().counters.telemetryReads.get() > readsBeforeDenied.get()
            }
            try {
                awaitNode { it.text?.toString()?.contains("access is pending or denied. Showing cached history") == true }
            } catch (failure: AssertionError) {
                throw AssertionError("history status=${visibleHistorySyncLabels()}", failure)
            }

            awaitShellSettled(scene)
            fixture.get().setTelemetryFailure(cloud.airchive.shared.contract.telemetry.TelemetryReadFailure.RemoteFailure())
            clickText("Refresh history")
            awaitNode { it.text?.toString()?.contains("last Airchive refresh failed. Showing cached history") == true }
            awaitShellSettled(scene)

            fixture.get().setTelemetryFailure(null)
            fixture.get().telemetryReadGate.hold()
            val readsBeforeLoading = AtomicInteger()
            scene.onActivity { readsBeforeLoading.set(fixture.get().counters.telemetryReads.get()) }
            clickText("Refresh history")
            awaitTrue("the synthetic history operation is suspended in its loading gate") {
                fixture.get().counters.telemetryReads.get() > readsBeforeLoading.get()
            }
            try {
                awaitNode { it.text?.toString()?.contains("Refreshing history…") == true }
            } catch (failure: AssertionError) {
                throw AssertionError(
                    "loading sync=${fixture.get().coordinator.isRefreshing.value} status=${visibleHistorySyncLabels()} " +
                        "labels=${safeVisibleLabels()}",
                    failure,
                )
            }
            fixture.get().telemetryReadGate.release()
            awaitNode { it.text?.toString() == "History synced 0 sec ago" }
            assertEquals(0, fixture.get().counters.thinQCommandSubmissions.get())
        }
        clearFixturePreferences()
    }

    @Test fun historyThousandsRowLedgerScrollsAndSelectsNewestRowsLazily() {
        clearFixturePreferences()
        launchShell().use { scene ->
            clickText("Continue with Google")
            awaitNode { it.text?.toString() == "Connect ThinQ in Settings to read and control your AC." }
            awaitNode { it.text?.toString() == "History synced 0 sec ago" }
            awaitShellSettled(scene)
            val fixture = AtomicReference<SharedInstrumentShellFixture>()
            scene.onActivity { fixture.set(it.shellFixture!!) }
            val owner = fixture.get().coordinator.session.value
                ?: throw AssertionError("the synthetic owner sign-in must complete")
            val partition = fixture.get().coordinator.partitionFor(owner)
            val end = (fixture.get().clock.now() / 300_000L) * 300_000L
            val observations = (0 until 3_000).map { index ->
                val start = end - (3_000L - index) * 300_000L
                cloud.airchive.shared.contract.telemetry.Observation(
                    sampleId = "ledger-4-4-$index",
                    intervalStartEpochMillis = start,
                    intervalEndEpochMillis = start + 300_000L,
                    energyValue = 100_000.0 + index,
                    recordedUnit = cloud.airchive.shared.contract.telemetry.EnergyUnit.WH,
                )
            }
            runBlocking {
                fixture.get().coordinator.local.clear(partition)
                fixture.get().coordinator.local.upsertObservations(partition, observations)
            }
            val telemetryReadsBefore = AtomicInteger()
            val writesBefore = AtomicInteger()
            scene.onActivity {
                telemetryReadsBefore.set(fixture.get().counters.telemetryReads.get())
                writesBefore.set(fixture.get().counters.localHistoryWrites.get())
            }

            clickText("History")
            awaitNode { it.text?.toString() == "3000 saved observations" }
            val firstRow = awaitNode {
                it.contentDescription?.toString()?.startsWith("Sample at ") == true &&
                    it.contentDescription?.toString()?.contains("102999") == true
            }
            assertNull("the oldest row is not eagerly composed in the first viewport",
                find(instrumentation.uiAutomation.rootInActiveWindow) {
                    it.contentDescription?.toString()?.contains("100000") == true
                })
            assertEquals(1, countNodes(instrumentation.uiAutomation.rootInActiveWindow) { it.isScrollable })

            clickNode(firstRow)
            awaitNode { it.text?.toString() == "ledger-4-4-2999" }
            clickText("Close")
            scene.onActivity {
                assertEquals(telemetryReadsBefore.get(), fixture.get().counters.telemetryReads.get())
                assertEquals(writesBefore.get(), fixture.get().counters.localHistoryWrites.get())
            }

            scrollForward()
            val scrolledRow = awaitNode {
                it.contentDescription?.toString()?.startsWith("Sample at ") == true &&
                    it.contentDescription?.toString()?.contains("102999") != true
            }
            clickNode(scrolledRow)
            val selectedLedgerId = awaitNode { it.text?.toString()?.startsWith("ledger-4-4-") == true }.text.toString()
            assertTrue(selectedLedgerId.substringAfterLast('-').toInt() < 2999)
            clickText("Close")
            scene.onActivity {
                assertEquals("scroll/detail remain local", telemetryReadsBefore.get(), fixture.get().counters.telemetryReads.get())
                assertEquals(writesBefore.get(), fixture.get().counters.localHistoryWrites.get())
            }
        }
        clearFixturePreferences()
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
        val original = node
        var target = node
        fun hasClickAction(candidate: AccessibilityNodeInfo) =
            candidate.isClickable || candidate.actionList.any { it.id == AccessibilityNodeInfo.ACTION_CLICK }
        while (!hasClickAction(target) && target.parent != null) target = target.parent
        val clickedByAction = hasClickAction(target) && target.performAction(AccessibilityNodeInfo.ACTION_CLICK)
        if (!clickedByAction) {
            tapNodeCenter(original)
        }
        instrumentation.waitForIdleSync()
    }

    private fun tapNodeCenter(node: AccessibilityNodeInfo) {
        val bounds = Rect()
        node.getBoundsInScreen(bounds)
        assertTrue("The target node must have visible screen bounds", bounds.width() > 0 && bounds.height() > 0)
        val x = (bounds.left + bounds.right) / 2
        val y = (bounds.top + bounds.bottom) / 2
        assertTrue("Native emulator tap succeeds", uiDevice.click(x, y))
        instrumentation.waitForIdleSync()
    }

    private fun clickText(text: String) {
        if (text in NAVIGATION_DESTINATIONS) {
            clickTab(text)
            return
        }
        repeat(8) {
            instrumentation.waitForIdleSync()
            val node = find(instrumentation.uiAutomation.rootInActiveWindow) { it.text?.toString() == text }
            if (node != null && node.isVisibleToUser) { clickVisibleText(text, node); return }
            scrollBackward()
        }
        repeat(16) {
            instrumentation.waitForIdleSync()
            val node = find(instrumentation.uiAutomation.rootInActiveWindow) { it.text?.toString() == text }
            if (node != null && node.isVisibleToUser) { clickVisibleText(text, node); return }
            scrollForward()
        }
        clickNode(awaitNode { it.text?.toString() == text })
    }

    private fun clickVisibleText(text: String, node: AccessibilityNodeInfo) {
        instrumentation.waitForIdleSync()
        try {
            uiDevice.findObject(By.text(text))?.let {
                it.click()
                instrumentation.waitForIdleSync()
                return
            }
        } catch (_: StaleObjectException) {
            // Re-read the current accessibility root below after Compose settles.
        }
        val current = find(instrumentation.uiAutomation.rootInActiveWindow) {
            it.text?.toString() == text && it.isVisibleToUser
        }
        clickNode(current ?: node)
    }

    /** Navigation labels are visually tappable even when Compose exposes their merged click action inconsistently. */
    private fun clickTab(label: String) {
        val screenHeight = instrumentation.targetContext.resources.displayMetrics.heightPixels
        val deadline = SystemClock.uptimeMillis() + 10_000
        while (SystemClock.uptimeMillis() < deadline) {
            val candidates = allNodes(instrumentation.uiAutomation.rootInActiveWindow)
                .filter { it.text?.toString() == label && it.isVisibleToUser }
            val tab = candidates.maxByOrNull { candidate ->
                val bounds = Rect()
                candidate.getBoundsInScreen(bounds)
                bounds.top
            }
            if (tab != null) {
                val bounds = Rect()
                tab.getBoundsInScreen(bounds)
                if (bounds.top > screenHeight * .6f) {
                    tapNodeCenter(tab)
                    return
                }
            }
            instrumentation.waitForIdleSync()
        }
        throw AssertionError("Could not locate the bottom-navigation destination '$label'")
    }

    private fun setTextByHint(hint: String, value: String) {
        val fields = awaitEditableFields()
        assertEquals("the isolated setup form exposes exactly token and country inputs", 2, fields.size)
        val index = when (hint) {
            "ThinQ Personal Access Token" -> 0
            "ThinQ account country" -> 1
            else -> throw AssertionError("No isolated-shell field mapping for hint '$hint'")
        }
        val field = fields[index]
        if (index == 0) {
            assertTrue("the first private setup input remains password-transformed", field.isPassword)
        } else {
            assertFalse("the country input is not password-transformed", field.isPassword)
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

    private fun disclosureHeader(title: String): AccessibilityNodeInfo {
        var node = awaitVisibleNode { it.text?.toString() == title }
        while (node.stateDescription == null && node.parent != null) node = node.parent
        assertNotNull("$title has expanded/collapsed disclosure semantics", node.stateDescription)
        return node
    }

    private fun disclosureStateNow(title: String): String? {
        var node = find(instrumentation.uiAutomation.rootInActiveWindow) { it.text?.toString() == title }
            ?: return null
        while (node.stateDescription == null && node.parent != null) node = node.parent
        return node.stateDescription?.toString()
    }

    private fun clickDisclosure(title: String) {
        var header = disclosureHeader(title)
        // A long instrument page can place the final disclosure directly above
        // the fixed navigation bar. Move its hit target farther into the
        // scrolling viewport before injecting a click, rather than tapping an
        // accessibility node whose center may be overlapped by navigation.
        for (attempt in 0..2) {
            val bounds = Rect()
            header.getBoundsInScreen(bounds)
            val safeBottom = instrumentation.targetContext.resources.displayMetrics.heightPixels * 0.76f
            if (bounds.bottom <= safeBottom) break
            scrollForward()
            header = disclosureHeader(title)
        }
        val previous = header.stateDescription?.toString()
            ?: throw AssertionError("$title disclosure has no state description")
        clickNode(header)
        if (waitForDisclosureTransition(title, previous)) return
        if (disclosureStateNow(title)?.let { it != previous } == true) return

        try {
            uiDevice.findObject(By.text(title))?.click()
        } catch (_: StaleObjectException) {
            // Reacquire through the semantics node and try a native tap below.
        }
        instrumentation.waitForIdleSync()
        if (waitForDisclosureTransition(title, previous)) return
        if (disclosureStateNow(title)?.let { it != previous } == true) return

        tapNodeCenter(disclosureHeader(title))
        assertTrue("$title disclosure toggles from $previous", waitForDisclosureTransition(title, previous))
    }

    private fun waitForDisclosureTransition(title: String, previous: String): Boolean {
        val deadline = SystemClock.uptimeMillis() + 1_500
        while (SystemClock.uptimeMillis() < deadline) {
            instrumentation.waitForIdleSync()
            if (disclosureStateNow(title)?.let { it != previous } == true) return true
            SystemClock.sleep(40)
        }
        return false
    }

    private fun assertDisclosureState(title: String, expected: String) {
        if (disclosureStateNow(title) == null) awaitVisibleNode { it.text?.toString() == title }
        try {
            awaitTrue("$title disclosure state becomes $expected") {
                disclosureStateNow(title) == expected
            }
        } catch (failure: AssertionError) {
            throw AssertionError(
                "$title semantics: ${disclosureDebugInfo(title)} scrollables=${scrollableDebugInfo()} " +
                    "visibleLabels=${safeVisibleLabels()}",
                failure,
            )
        }
    }

    private fun safeVisibleLabels(): String {
        val fixedLabels = setOf(
            "Home", "Device", "Energy", "History", "Settings", "Day", "Week", "Month",
            "Coverage and measurement details", "Daily record", "Daily records", "Units and conversions",
            "Period summary", "Sampled energy", "Sampled five-minute interval energy", "Load sampled intervals",
            "History access", "History and storage", "Connection details", "Account actions",
            "Sync and collector health", "Sample status guide", "Cached observations (newest first)",
            "No cached observations yet", "Set up ThinQ PAT", "Set temperature", "Apply",
            "Refresh history", "Refreshing history…", "Retry history sync",
        )
        return allNodes(instrumentation.uiAutomation.rootInActiveWindow)
            .filter { it.isVisibleToUser }
            .mapNotNull { it.text?.toString() }
            .filter { it in fixedLabels }
            .distinct()
            .joinToString(" | ")
            .ifEmpty { "none" }
    }

    private fun visibleHistorySyncLabels(): String {
        val safeMessages = setOf(
            "Airchive access is pending or denied. Showing cached history; saved rows are unchanged.",
            "Airchive could not be reached. Showing cached history; it stays available offline.",
            "The last Airchive refresh failed. Showing cached history; saved rows are unchanged.",
            "Showing saved Airchive history.",
            "Refreshing history… Cached observations remain available.",
            "Refreshing history… No history is cached yet.",
        )
        return allNodes(instrumentation.uiAutomation.rootInActiveWindow)
            .filter { it.isVisibleToUser }
            .mapNotNull { it.text?.toString() }
            .filter { it in safeMessages }
            .joinToString(" | ")
            .ifEmpty { "none" }
    }

    private fun disclosureDebugInfo(title: String): String {
        val matches = mutableListOf<String>()
        fun visit(node: AccessibilityNodeInfo?, depth: Int = 0) {
            if (node == null) return
            if (node.text?.toString() == title) {
                var parent: AccessibilityNodeInfo? = node
                val ancestors = mutableListOf<String>()
                repeat(4) {
                    val current = parent ?: return@repeat
                    val bounds = Rect()
                    current.getBoundsInScreen(bounds)
                    ancestors += "${current.className}:${current.stateDescription}:${current.isClickable}:$bounds:${current.actionList.map { action -> action.id }}"
                    parent = current.parent
                }
                matches += "${" ".repeat(depth)}${ancestors.joinToString(" -> ")}"
            }
            for (index in 0 until node.childCount) visit(node.getChild(index), depth + 1)
        }
        visit(instrumentation.uiAutomation.rootInActiveWindow)
        return matches.joinToString(" | ").ifEmpty { "no text nodes found" }
    }

    private fun scrollableDebugInfo(): String {
        val entries = mutableListOf<String>()
        fun visit(node: AccessibilityNodeInfo?) {
            if (node == null) return
            if (node.isScrollable) {
                val bounds = Rect()
                node.getBoundsInScreen(bounds)
                val range = node.rangeInfo?.let { "${it.current}/${it.max}" } ?: "no-range"
                entries += "${node.className}:$bounds:range=$range:actions=${node.actionList.map { it.id }}"
            }
            for (index in 0 until node.childCount) visit(node.getChild(index))
        }
        visit(instrumentation.uiAutomation.rootInActiveWindow)
        return entries.joinToString(" | ").ifEmpty { "none" }
    }

    private fun assertTextPresent(text: String) {
        awaitVisibleNode { it.text?.toString()?.contains(text) == true }
    }

    private fun assertTextAbsent(text: String) {
        assertNull("$text should not be visible while its disclosure is collapsed",
            find(instrumentation.uiAutomation.rootInActiveWindow) { it.text?.toString()?.contains(text) == true })
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

    private fun awaitEditableFields(): List<AccessibilityNodeInfo> {
        val deadline = SystemClock.uptimeMillis() + 10_000
        while (SystemClock.uptimeMillis() < deadline) {
            instrumentation.waitForIdleSync()
            val fields = editableNodes(instrumentation.uiAutomation.rootInActiveWindow)
            if (fields.size >= 2) return fields
            scrollForward()
            SystemClock.sleep(50)
        }
        throw AssertionError("Expected two synthetic setup fields; found ${editableNodes().size}")
    }

    private fun editableNodes(node: AccessibilityNodeInfo? = instrumentation.uiAutomation.rootInActiveWindow): List<AccessibilityNodeInfo> {
        if (node == null) return emptyList()
        return buildList {
            if (node.isEditable) add(node)
            for (index in 0 until node.childCount) addAll(editableNodes(node.getChild(index)))
        }
    }

    private fun energySummarySnapshot(): List<String> {
        val lines = accessibilitySnapshot(instrumentation.uiAutomation.rootInActiveWindow).lineSequence().toList()
        val labelIndex = lines.indexOf("Sampled energy")
        assertTrue("the summary label is in the accessibility tree", labelIndex >= 0 && labelIndex + 2 < lines.size)
        val coverage = lines.firstOrNull { line ->
            line.startsWith("No elapsed intervals") || line.startsWith("No usable samples") ||
                line.startsWith("Recorded coverage complete") || line.startsWith("Incomplete coverage")
        } ?: throw AssertionError("The Energy coverage badge is not exposed")
        return listOf(
            lines[labelIndex],
            lines[labelIndex + 1],
            lines[labelIndex + 2],
            coverage,
        )
    }

    private fun scrollForward() {
        scrollContent(forward = true)
    }

    private fun scrollBackward() {
        scrollContent(forward = false)
    }

    private fun scrollContent(forward: Boolean) {
        val scrollable = find(instrumentation.uiAutomation.rootInActiveWindow) { it.isScrollable }
        if (scrollable == null) {
            instrumentation.waitForIdleSync()
            return
        }
        val bounds = Rect()
        scrollable.getBoundsInScreen(bounds)
        if (bounds.width() <= 0 || bounds.height() <= 0) return
        val x = (bounds.left + bounds.right) / 2
        val density = instrumentation.targetContext.resources.displayMetrics.density
        // Use overlapping, density-aware swipes within the content viewport.
        // This avoids jumping over a disclosure between page-sized scroll steps
        // and stays clear of the shell's fixed bottom navigation.
        val top = bounds.top + (36 * density).toInt()
        val bottom = bounds.bottom - (100 * density).toInt()
        val delta = maxOf((bounds.height() * .35f).toInt(), (150 * density).toInt())
        val startY = if (forward) bottom else top
        val endY = if (forward) maxOf(top, bottom - delta) else minOf(bottom, top + delta)
        assertTrue("Native emulator swipe succeeds", uiDevice.swipe(x, startY, x, endY, 30))
        instrumentation.waitForIdleSync()
    }

    private fun scrollToTop() {
        repeat(2) {
            scrollContent(forward = false)
        }
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

    private fun awaitVisibleNode(predicate: (AccessibilityNodeInfo) -> Boolean): AccessibilityNodeInfo {
        // First cover the case where the requested control is above the current
        // viewport (for example the disclosure headers above a virtualized list).
        repeat(2) { scrollBackward() }
        repeat(12) {
            instrumentation.waitForIdleSync()
            find(instrumentation.uiAutomation.rootInActiveWindow, predicate)
                ?.takeIf { it.isVisibleToUser }
                ?.let { return it }
            scrollForward()
        }
        // A large, dynamically expanded card can move a target past a viewport
        // boundary in one accessibility scroll step. Sweep back once so those
        // intermediate items are still discoverable.
        repeat(12) {
            instrumentation.waitForIdleSync()
            find(instrumentation.uiAutomation.rootInActiveWindow, predicate)
                ?.takeIf { it.isVisibleToUser }
                ?.let { return it }
            scrollBackward()
        }
        return awaitNode(predicate)
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

    private fun awaitLoadButtonEnabled() {
        awaitTrue("the explicit Energy load action finishes") {
            find(instrumentation.uiAutomation.rootInActiveWindow) {
                it.text?.toString() == "Load sampled intervals"
            }?.isEnabled == true
        }
    }

    private fun currentDeviceReadAt(fixture: SharedInstrumentShellFixture): Long {
        val current = fixture.thinQContext.liveStateController.state.value as?
            cloud.airchive.shared.data.thinq.ThinQLiveRefreshState.Current
            ?: throw AssertionError("the synthetic device-read state is not current")
        return current.state.observedAtEpochMillis
    }

    private fun awaitShellSettled(scene: ActivityScenario<SharedInstrumentPreviewActivity>) {
        var previousTelemetryReads = -1
        var stableSamples = 0
        awaitTrue("the synthetic shell settles after its owner/history reads") {
            var accessCardVisible = false
            var historyRefreshActive = true
            var telemetryReads = 0
            scene.onActivity { activity ->
                accessCardVisible = find(instrumentation.uiAutomation.rootInActiveWindow) {
                    it.text?.toString() == "Airchive account & access"
                } != null
                historyRefreshActive = activity.shellFixture?.coordinator?.isRefreshing?.value ?: true
                telemetryReads = activity.shellFixture?.counters?.telemetryReads?.get() ?: 0
            }
            if (!accessCardVisible && !historyRefreshActive && telemetryReads == previousTelemetryReads) {
                stableSamples += 1
            } else {
                stableSamples = 0
            }
            previousTelemetryReads = telemetryReads
            stableSamples >= 6
        }
    }

    private fun selectShellDetail(
        scene: ActivityScenario<SharedInstrumentPreviewActivity>,
        detail: cloud.airchive.shared.InterfaceDetail,
    ) {
        scene.onActivity { activity -> activity.shellFixture!!.interfacePreferences.select(detail) }
        instrumentation.waitForIdleSync()
        awaitTrue("the shell preference adopts $detail") {
            var selected = false
            scene.onActivity { activity ->
                selected = activity.shellFixture!!.interfacePreferences.detail.value == detail
            }
            selected
        }
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

    private fun countNodes(node: AccessibilityNodeInfo?, predicate: (AccessibilityNodeInfo) -> Boolean): Int {
        if (node == null) return 0
        return (if (predicate(node)) 1 else 0) +
            (0 until node.childCount).sumOf { countNodes(node.getChild(it), predicate) }
    }

    private fun allNodes(node: AccessibilityNodeInfo?): List<AccessibilityNodeInfo> {
        if (node == null) return emptyList()
        return listOf(node) + (0 until node.childCount).flatMap { allNodes(node.getChild(it)) }
    }

    private companion object {
        val NAVIGATION_DESTINATIONS = setOf("Home", "Device", "Energy", "History", "Settings")
    }
}
