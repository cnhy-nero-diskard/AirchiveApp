package cloud.airchive.personal.preview

import android.content.Intent
import android.app.UiModeManager
import android.os.ParcelFileDescriptor
import android.graphics.Bitmap
import android.os.Bundle
import android.os.SystemClock
import android.graphics.Rect
import android.view.Choreographer
import android.view.accessibility.AccessibilityNodeInfo
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.StaleObjectException
import androidx.test.uiautomator.UiDevice
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.flow.first
import cloud.airchive.shared.InterfaceDetail
import cloud.airchive.shared.data.update.AppUpdateState
import cloud.airchive.shared.data.update.PendingInstallRecord
import cloud.airchive.shared.data.update.UpdateApk
import cloud.airchive.shared.data.update.UpdateFailureReason
import cloud.airchive.shared.data.update.UpdateManifest
import cloud.airchive.shared.data.update.UpdateUnavailableCause

/** The debug-only preview never constructs a production graph or contacts an AC. */
@RunWith(AndroidJUnit4::class)
class SharedInstrumentUiTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val uiDevice by lazy { UiDevice.getInstance(instrumentation) }

    private fun launch(
        dark: Boolean = false,
        stale: Boolean = false,
        route: Int = 0,
        detail: String? = "simple",
        unknownFan: Boolean = false,
    ) = ActivityScenario.launch<SharedInstrumentPreviewActivity>(
        Intent(ApplicationProvider.getApplicationContext(), SharedInstrumentPreviewActivity::class.java)
            .putExtra("dark", dark).putExtra("stale", stale).putExtra("route", route)
            .putExtra("unknown_fan", unknownFan)
            .apply { if (detail != null) putExtra("detail", detail) },
    )

    @Test fun homePullRefreshFetchesAgainAndDetailsRemainReadable() {
        clearFixturePreferences()
        launchShell().use { scene ->
            clickText("Continue with Google")
            awaitShellSettled(scene)
            val fixture = AtomicReference<SharedInstrumentShellFixture>()
            scene.onActivity { fixture.set(it.shellFixture!!) }
            val before = fixture.get().counters.telemetryReads.get()
            assertTrue(uiDevice.swipe(uiDevice.displayWidth / 2, uiDevice.displayHeight / 6,
                uiDevice.displayWidth / 2, uiDevice.displayHeight * 2 / 3, 30))
            awaitTrue("Pull-to-refresh fetches history again") { fixture.get().counters.telemetryReads.get() > before }
            awaitShellSettled(scene)
            assertTextAbsent("Refresh history")
            scene.onActivity { it.shellFixture!!.interfacePreferences.select(cloud.airchive.shared.InterfaceDetail.DETAILED) }
            clickTab("Device")
            awaitVisibleNode { it.text?.toString() == "Device ID" }
            scrollForward()
            saveCapture("feedback-device-detail-fields")
            clickTab("History")
            awaitVisibleNode { it.text?.toString() == "Collector health" }
            scrollForward()
            saveCapture("feedback-history-health-fields")
            awaitVisibleNode { it.text?.toString() == "No anomaly flagged" }
            saveCapture("feedback-history-status-guide")
            clickTab("Energy")
            awaitVisibleNode { it.text?.toString() == "Measured coverage" }
            saveCapture("feedback-energy-coverage-fields")
            awaitVisibleNode { it.text?.toString() == "Measured intervals" }
            repeat(2) { scrollForward() }
            saveCapture("feedback-energy-daily-fields")
            awaitVisibleNode { it.text?.toString() == "1 kWh = 1,000 Wh" }
            saveCapture("feedback-energy-unit-fields")
        }
    }

    @Test fun energyGranularitySwitchesBetweenHourlyBarsAndDenseLine() {
        for (dark in listOf(false, true)) {
            launch(route = 2, dark = dark).use {
                val slider = awaitVisibleNode { it.contentDescription?.toString() == "Energy chart time bucket size" }
                val range = allNodes(slider).firstOrNull { it.rangeInfo != null }
                    ?: awaitNode { it.rangeInfo?.max == 3f }
                assertTrue(range.performAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_SET_PROGRESS.id, Bundle().apply {
                    putFloat(AccessibilityNodeInfo.ACTION_ARGUMENT_PROGRESS_VALUE, 0f)
                }))
                awaitNode { it.text?.toString() == "Granularity · 5 minutes" }
                scrollForward()
                val graph = awaitNode { it.contentDescription?.toString()?.startsWith("Energy chart, 288 time buckets") == true }
                assertTrue(graph.performAction(graph.actionList.first { it.label?.toString() == "Next time bucket" }.id))
                awaitNode { it.stateDescription?.toString()?.contains("selected bucket 0") == true }
                saveCapture("feedback-granularity-line-$dark")
                val reset = awaitVisibleNode { it.contentDescription?.toString() == "Energy chart time bucket size" }
                val resetRange = allNodes(reset).firstOrNull { it.rangeInfo != null }
                    ?: awaitNode { it.rangeInfo?.max == 3f }
                assertTrue(resetRange.performAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_SET_PROGRESS.id, Bundle().apply {
                    putFloat(AccessibilityNodeInfo.ACTION_ARGUMENT_PROGRESS_VALUE, 3f)
                }))
                awaitVisibleNode { it.text?.toString() == "Granularity · 1 hour" }
                awaitVisibleNode { it.text?.toString() == "Energy (kWh)" }
                saveCapture("feedback-granularity-hourly-$dark")
            }
        }
    }

    @Test fun fineEnergyIntervalsSupportManualAndAutoZoom() {
        for ((progress, minutes, total) in listOf(Triple(0f, 5, 288), Triple(1f, 15, 96), Triple(2f, 30, 48))) {
            launch(route = 2).use {
                val slider = awaitVisibleNode { it.contentDescription?.toString() == "Energy chart time bucket size" }
                val range = allNodes(slider).firstOrNull { it.rangeInfo != null } ?: awaitNode { it.rangeInfo?.max == 3f }
                assertTrue(range.performAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_SET_PROGRESS.id, Bundle().apply {
                    putFloat(AccessibilityNodeInfo.ACTION_ARGUMENT_PROGRESS_VALUE, progress)
                }))
                awaitNode { it.text?.toString() == "Granularity · $minutes minutes" }
                val defaultZoom = awaitNode { it.contentDescription?.toString()?.startsWith("Auto zoom. Off") == true }
                assertTrue("Auto zoom is enabled by default", allNodes(defaultZoom).any { it.isChecked })
                clickNode(defaultZoom)
                awaitNode { it.contentDescription?.toString()?.startsWith("Auto zoom. Off") == true && allNodes(it).none { node -> node.isChecked } }
                scrollForward()
                fun graph() = awaitNode { it.contentDescription?.toString()?.startsWith("Energy chart, $total time buckets") == true }
                fun showing(count: Int) = awaitNode { it.stateDescription?.toString()?.startsWith("Showing $count of $total") == true }
                val plot = uiDevice.findObject(By.descStartsWith("Energy chart, $total time buckets"))
                assertNotNull(plot)
                plot.pinchOpen(.8f)
                val zoomed = awaitNode {
                    it.contentDescription?.toString()?.startsWith("Energy chart, $total time buckets") == true &&
                        it.stateDescription?.toString()?.startsWith("Showing $total of $total") == false
                }
                val beforePan = zoomed.stateDescription.toString()
                val bounds = Rect().also { zoomed.getBoundsInScreen(it) }
                assertTrue(uiDevice.swipe(bounds.centerX(), bounds.centerY(), bounds.centerX() - bounds.width() / 4, bounds.centerY(), 20))
                awaitNode {
                    it.contentDescription?.toString()?.startsWith("Energy chart, $total time buckets") == true &&
                        it.stateDescription.toString() != beforePan
                }
                tapNodeCenter(graph())
                awaitNode { it.stateDescription?.toString()?.contains("selected bucket") == true }
                clickNode(awaitNode { it.text?.toString() == "Reset zoom" })
                showing(total)
                clickNode(awaitVisibleNode { it.contentDescription?.toString()?.startsWith("Auto zoom. Off") == true })
                awaitNode { it.contentDescription?.toString()?.startsWith("Auto zoom. Off") == true && allNodes(it).any { node -> node.isChecked } }
                scrollForward()
                tapNodeCenter(graph())
                showing(9)
                awaitNode { it.text?.toString()?.contains("elapsed intervals measured") == true }
                saveCapture("energy-auto-zoom-$minutes")
                tapNodeCenter(graph())
                showing(total)
                tapNodeCenter(graph())
                showing(9)
                clickNode(awaitNode { it.text?.toString() == "Reset zoom" })
                showing(total)
            }
        }
    }

    @Test fun sensorTemperatureIsSeparateFromTargetOnHomeAndDevice() {
        listOf(0, 1).forEach { route ->
            launch(route = route).use {
                awaitAccessibilityNode("sensor and target readings") { it.text?.toString() == "Room temperature" }
                awaitAccessibilityNode("sensor and target readings") { it.text?.toString() == "28.5 °C" }
                assertTextAbsent("Target read from AC")
                awaitAccessibilityNode("sensor and target readings") { it.text?.toString() == "25" }
                saveCapture("sensor-temperature-route-$route")
                if (route == 0) {
                    val increase = awaitAccessibilityNode("temperature increase control") {
                        it.contentDescription?.toString() == "Increase target temperature"
                    }
                    assertTrue(increase.performAction(AccessibilityNodeInfo.ACTION_CLICK))
                    awaitAccessibilityNode("sensor and target readings") { it.text?.toString() == "28.5 °C" }
                    awaitAccessibilityNode("sensor and target readings") { it.text?.toString() == "26" }
                }
            }
        }
        launch(stale = true).use {
            awaitAccessibilityNode("sensor and target readings") { it.text?.toString() == "Last room temperature" }
            awaitAccessibilityNode("sensor and target readings") { it.text?.toString() == "28.5 °C" }
        }
    }

    @Test fun thermostatAndConsumptionFeedbackLayouts() {
        val scale = instrumentation.targetContext.resources.configuration.fontScale
        for (dark in listOf(false, true)) {
            launch(dark = dark).use {
                awaitVisibleNode { it.text?.toString() == "28.5 °C" }
                saveCapture("feedback-thermostat-${if (dark) "dark" else "light"}-scale-$scale")
                awaitVisibleNode { it.text?.toString()?.startsWith("Current power consumption as of ") == true }
                awaitVisibleNode { it.text?.toString() == "Today's net consumption" }
                val unit = awaitVisibleNode { it.text?.toString() == "kWh" }
                val unitBounds = Rect().also(unit::getBoundsInScreen)
                assertTrue("the inline unit remains within the viewport", unitBounds.right <= uiDevice.displayWidth)
                val tree = accessibilitySnapshot(instrumentation.uiAutomation.rootInActiveWindow)
                assertFalse(tree.contains("Hour to date"))
                assertFalse(tree.contains("Partial hour"))
                assertFalse(tree.contains("Today so far:"))
                assertFalse(tree.contains("Incomplete coverage"))
                assertFalse(tree.contains("Target read from AC"))
                saveCapture("feedback-consumption-${if (dark) "dark" else "light"}-scale-$scale")
                awaitVisibleNode { it.text?.toString()?.startsWith("Outlined bars: Data might be inaccurate or incomplete during this hour.") == true }
                saveCapture("feedback-chart-${if (dark) "dark" else "light"}-scale-$scale")
            }
        }
    }

    @Test fun hourlyChartsKeepAllHoursOnScreenAndShowSelectedRangeBelowPlot() {
        listOf(false, true).forEach { dark ->
        listOf(0, 2).forEach { route ->
            launch(route = route, dark = dark).use {
                if (route == 0) {
                    awaitVisibleNode { it.text?.toString() == "Set temperature" }
                    saveCapture("usage-home-temperature-$dark")
                }
                awaitVisibleNode { it.text?.toString() == "Energy (kWh)" }
                File(instrumentation.targetContext.getExternalFilesDir(null), "usage-tree-$route-$dark.txt")
                    .writeText(accessibilitySnapshot(instrumentation.uiAutomation.rootInActiveWindow))
                saveCapture("usage-before-selection-$route-$dark")
                val bars = allNodes(instrumentation.uiAutomation.rootInActiveWindow).filter {
                    it.contentDescription?.toString()?.contains("intervals measured") == true
                }
                assertEquals("Every hour remains available on route $route", 24, bars.size)
                bars.forEach { bar ->
                    val bounds = Rect()
                    bar.getBoundsInScreen(bounds)
                    assertTrue("Hour fits the screen width: $bounds", bounds.left >= 0 && bounds.right <= uiDevice.displayWidth)
                }
                val last = bars.single { it.contentDescription.toString().startsWith("11 PM – 12 AM,") }
                tapNodeCenter(last)
                awaitVisibleNode { it.text?.toString() == "11 PM – 12 AM" }
                val selected = awaitNode { it.contentDescription?.toString()?.startsWith("11 PM – 12 AM,") == true }
                assertTrue("The chosen hour has native selected semantics", selected.stateDescription?.toString() == "Selected")
                val plot = Rect()
                selected.getBoundsInScreen(plot)
                val detail = Rect()
                awaitNode { it.text?.toString() == "11 PM – 12 AM" }.getBoundsInScreen(detail)
                assertTrue("Details sit below the graph", detail.top >= plot.bottom)
                saveCapture("usage-chart-$route-$dark")
            }
        }
        }
    }

    private fun launchShell(
        initiallySignedIn: Boolean = false,
        suspendInitialAccessCheck: Boolean = false,
        initialOfflineAccessCheck: Boolean = false,
        retainFixtureForRecreation: Boolean = false,
        appUpdateEnabled: Boolean = false,
    ) = ActivityScenario.launch<SharedInstrumentPreviewActivity>(
        Intent(ApplicationProvider.getApplicationContext(), SharedInstrumentPreviewActivity::class.java)
            .putExtra("shell", true)
            .putExtra("fixture_signed_in", initiallySignedIn)
            .putExtra("fixture_suspend_access", suspendInitialAccessCheck)
            .putExtra("fixture_offline_access", initialOfflineAccessCheck)
            .putExtra("retain_fixture_for_recreation", retainFixtureForRecreation)
            .putExtra("fixture_app_update_enabled", appUpdateEnabled),
    )

    private fun assertAccessibleUpdateAction(description: String): AccessibilityNodeInfo {
        val node = awaitVisibleNode { it.contentDescription?.toString() == description }
        val ancestors = generateSequence(node) { it.parent }
            .take(5)
            .joinToString(" -> ") {
                "${it.className}[text=${it.text},description=${it.contentDescription},clickable=${it.isClickable},actions=${it.actionList.map { action -> action.id }}]"
            }
        assertTrue(
            "TalkBack action '$description' exposes a click action; semantics path: $ancestors",
            node.isClickable || node.actionList.any { it.id == AccessibilityNodeInfo.ACTION_CLICK },
        )
        return node
    }

    private fun assertUpdateActionFitsScreen(node: AccessibilityNodeInfo) {
        val bounds = Rect().also(node::getBoundsInScreen)
        val metrics = instrumentation.targetContext.resources.displayMetrics
        assertTrue("update action has a non-empty touch target: $bounds", bounds.width() > 0 && bounds.height() > 0)
        assertTrue(
            "update action meets the 48dp touch target: ${bounds.height() / metrics.density}dp, $bounds",
            bounds.height() / metrics.density >= 48f,
        )
        assertTrue("update action stays within the screen: $bounds", bounds.left >= 0 && bounds.right <= metrics.widthPixels)
    }

    private fun assertUpdateTextFitsScreen(text: String) {
        val node = awaitVisibleNode { it.text?.toString() == text }
        val bounds = Rect().also(node::getBoundsInScreen)
        val metrics = instrumentation.targetContext.resources.displayMetrics
        assertTrue("update text stays within the screen: '$text' $bounds",
            bounds.left >= 0 && bounds.right <= metrics.widthPixels && bounds.height() > 0)
    }

    private fun updateNoticeManifest(
        versionCode: Long = 1_001L,
        versionName: String = "10.0.1",
    ) = UpdateManifest(
        schemaVersion = 1,
        channel = "stable",
        packageName = "cloud.airchive.personal",
        versionCode = versionCode,
        versionName = versionName,
        commit = "e".repeat(40),
        publishedAt = "2026-11-09T00:00:00Z",
        releaseNotes = "Update notice fixture.",
        apk = UpdateApk(
            url = "https://github.com/example/AirchiveApp/releases/download/$versionName/app.apk",
            sha256 = "f".repeat(64),
            size = 1_000_000L,
            signingCertSha256 = "d".repeat(64),
        ),
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
            awaitVisibleNode { it.text?.toString() == "History synced 0 sec ago" }
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

    @Test fun appUpdateSettingsRendersEveryStateInBothDetailsWithoutCheckingOnOpen() {
        clearFixturePreferences()
        launchShell(appUpdateEnabled = true).use { scene ->
            val fixture = AtomicReference<SharedInstrumentShellFixture>()
            scene.onActivity { fixture.set(it.shellFixture!!) }
            clickTab("Settings")
            assertTextPresent("App version & updates")
            assertTextPresent("Current version 9.9.9-fixture")
            assertTextPresent("Last checked: Not yet")
            assertEquals("Composing and opening Settings does not check the feed", 0,
                fixture.get().appUpdateCheckCountForTest())

            val manifest = UpdateManifest(
                schemaVersion = 1,
                channel = "stable",
                packageName = "cloud.airchive.personal",
                versionCode = 1_001L,
                versionName = "10.0.1",
                commit = "a1b2c3d4e5f6",
                publishedAt = "2026-11-09T00:00:00Z",
                releaseNotes = "These <b>markers</b> stay plain text.",
                apk = UpdateApk(
                    url = "https://github.com/example/AirchiveApp/releases/download/v10.0.1/app.apk",
                    sha256 = "ab".repeat(32),
                    size = 1_024_000L,
                    signingCertSha256 = "cd".repeat(32),
                ),
            )
            val stateCases = listOf(
                AppUpdateState.Idle to "Check when you’re ready",
                AppUpdateState.Checking to "Checking for updates",
                AppUpdateState.UpToDate to "You’re up to date",
                AppUpdateState.Unavailable(UpdateUnavailableCause.OFFLINE) to "You’re offline",
                AppUpdateState.Available(manifest) to "Version 10.0.1 is available",
                AppUpdateState.Downloading(manifest, bytesReceived = 512_000L, totalBytes = 1_024_000L) to "Downloading update · 50%",
                AppUpdateState.Verifying(manifest) to "Verifying downloaded update",
                AppUpdateState.Failed(UpdateFailureReason.HASH_MISMATCH) to "checksum",
                AppUpdateState.ReadyToInstall(manifest) to "ready to install",
                AppUpdateState.NeedsInstallPermission(manifest) to "Allow Airchive to install this update",
                AppUpdateState.Installing(manifest) to "Waiting for Android to finish installing",
                AppUpdateState.Updated("10.0.1") to "Updated to 10.0.1",
                AppUpdateState.NotCompleted("10.0.1") to "Update didn’t finish · 10.0.1",
            )

            for (detail in listOf(InterfaceDetail.SIMPLE, InterfaceDetail.DETAILED)) {
                selectShellDetail(scene, detail)
                for ((state, expectedCopy) in stateCases) {
                    scene.onActivity { it.shellFixture!!.setAppUpdateStateForTest(state) }
                    awaitVisibleNode { it.text?.toString()?.contains(expectedCopy) == true }
                    assertEquals("Rendering $state in $detail never checks the feed", 0,
                        fixture.get().appUpdateCheckCountForTest())
                    when (state) {
                        AppUpdateState.Idle -> assertAccessibleUpdateAction("Check for app updates")
                        is AppUpdateState.Unavailable,
                        is AppUpdateState.Failed,
                        is AppUpdateState.NotCompleted -> {
                            assertAccessibleUpdateAction("Retry checking for app updates")
                        }
                        AppUpdateState.UpToDate -> {
                            assertAccessibleUpdateAction("Check for app updates again")
                        }
                        is AppUpdateState.Available -> {
                            assertAccessibleUpdateAction("Download app update version ${state.manifest.versionName}")
                        }
                        is AppUpdateState.Downloading -> {
                            assertAccessibleUpdateAction("Cancel app update download")
                        }
                        is AppUpdateState.ReadyToInstall -> {
                            assertAccessibleUpdateAction("Install app update version ${state.manifest.versionName}")
                        }
                        is AppUpdateState.NeedsInstallPermission -> {
                            assertTextPresent(
                                "Allow Airchive to install this update in Android settings, then return here.",
                            )
                            assertAccessibleUpdateAction("Open Android install permission settings")
                        }
                        else -> Unit
                    }
                    if (state is AppUpdateState.Available) {
                        assertTextPresent("These <b>markers</b> stay plain text.")
                        assertTextPresent("Download size · 1000 KB")
                        if (detail == InterfaceDetail.SIMPLE) {
                            assertTextAbsent("Commit: ${manifest.commit}")
                            assertTextAbsent("SHA-256: ${manifest.apk.sha256}")
                            saveCapture("app-update-simple-available")
                        } else {
                            assertTextPresent("Commit: ${manifest.commit}")
                            assertTextPresent("SHA-256: ${manifest.apk.sha256}")
                            assertTextPresent("Feed URL: https://updates.example.invalid/update.json")
                            assertTextPresent("Version code: 999")
                            saveCapture("app-update-detailed-available")
                        }
                    }
                }
            }

            scene.onActivity { it.shellFixture!!.setAppUpdateStateForTest(AppUpdateState.Idle) }
            clickNode(assertAccessibleUpdateAction("Check for app updates"))
            awaitTrue("only the explicit Settings action performs a check") {
                fixture.get().appUpdateCheckCountForTest() == 1
            }
            awaitVisibleNode { it.text?.toString() == "You’re up to date" }
        }
    }

    @Test fun nullAppUpdateBindingExplainsReleaseBuildAvailability() {
        clearFixturePreferences()
        launchShell().use { scene ->
            val fixture = AtomicReference<SharedInstrumentShellFixture>()
            scene.onActivity { fixture.set(it.shellFixture!!) }
            clickTab("Settings")
            assertTextPresent("In-app updates apply to release builds")
            assertNull("The debug shell has no app-update binding", fixture.get().appUpdateBindings)
            assertEquals("The null-binding state has no update check", 0, fixture.get().appUpdateCheckCountForTest())
        }
    }

    @Test fun recoveredUpdateOutcomeCanBeDismissedAfterItIsShown() {
        clearFixturePreferences()
        launchShell(appUpdateEnabled = true).use { scene ->
            val fixture = AtomicReference<SharedInstrumentShellFixture>()
            scene.onActivity { fixture.set(it.shellFixture!!) }
            clickTab("Settings")
            scene.onActivity {
                it.shellFixture!!.setPendingInstallForTest(
                    PendingInstallRecord(targetVersionCode = 999L, startedAtEpochMillis = 100L),
                )
                it.shellFixture!!.setAppUpdateStateForTest(AppUpdateState.Updated("10.0.0"))
            }

            assertTextPresent("Updated to 10.0.0")
            clickNode(assertAccessibleUpdateAction("Dismiss app update result"))
            awaitVisibleNode { it.text?.toString()?.contains("Check when you’re ready") == true }
            assertNull(fixture.get().appUpdateBindings!!.preferences.appUpdateState.value.pendingInstall)
            assertEquals(AppUpdateState.Idle, fixture.get().appUpdateBindings!!.state.value)
        }
    }

    @Test fun homeUpdateNoticeDismissalIsVersionScopedAndReviewOpensSettings() {
        clearFixturePreferences()
        launchShell(appUpdateEnabled = true).use { scene ->
            val fixture = AtomicReference<SharedInstrumentShellFixture>()
            scene.onActivity { fixture.set(it.shellFixture!!) }
            val firstOffer = updateNoticeManifest()
            scene.onActivity { it.shellFixture!!.setAppUpdateStateForTest(AppUpdateState.Available(firstOffer)) }
            awaitVisibleNode { it.text?.toString() == "Update available · 10.0.1" }
            assertAccessibleUpdateAction("Review update in Settings")
            assertAccessibleUpdateAction("Dismiss update for this version")
            assertEquals("Home notices never check the feed", 0, fixture.get().appUpdateCheckCountForTest())

            clickNode(assertAccessibleUpdateAction("Dismiss update for this version"))
            assertTextAbsent("Update available · 10.0.1")
            assertEquals(firstOffer.versionCode,
                fixture.get().appUpdateBindings!!.preferences.appUpdateState.value.dismissedVersionCode)

            scene.recreate()
            scene.onActivity { fixture.set(it.shellFixture!!) }
            scene.onActivity { it.shellFixture!!.setAppUpdateStateForTest(AppUpdateState.Available(firstOffer)) }
            assertTextAbsent("Update available · 10.0.1")

            val nextOffer = firstOffer.copy(versionCode = firstOffer.versionCode + 1, versionName = "10.0.2")
            scene.onActivity { it.shellFixture!!.setAppUpdateStateForTest(AppUpdateState.Available(nextOffer)) }
            awaitVisibleNode { it.text?.toString() == "Update available · 10.0.2" }
            clickNode(assertAccessibleUpdateAction("Review update in Settings"))
            assertTextPresent("App version & updates")
            assertTextPresent("Version 10.0.2 is available")
            assertEquals("Review opens Settings without checking the feed", 0,
                fixture.get().appUpdateCheckCountForTest())
        }
        clearFixturePreferences()
    }

    @Test fun appUpdateSurfacesScaleAtMaximumFontSize() {
        val fontScale = instrumentation.targetContext.resources.configuration.fontScale
        assertTrue("this accessibility check runs at Android's maximum font scale (2.0); was $fontScale",
            fontScale >= 1.9f)
        clearFixturePreferences()
        try {
            launchShell(appUpdateEnabled = true).use { scene ->
                val manifest = updateNoticeManifest()
                scene.onActivity {
                    it.shellFixture!!.interfacePreferences.select(InterfaceDetail.DETAILED)
                    it.shellFixture!!.setAppUpdateStateForTest(AppUpdateState.Available(manifest))
                }
                assertUpdateTextFitsScreen("Update available · 10.0.1")
                assertUpdateActionFitsScreen(assertAccessibleUpdateAction("Review update in Settings"))
                assertUpdateActionFitsScreen(assertAccessibleUpdateAction("Dismiss update for this version"))
                saveCapture("app-update-home-max-text")
                val reviewAction = assertAccessibleUpdateAction("Review update in Settings")
                clickNode(reviewAction)
                assertTextPresent("App version & updates")

                repeat(12) {
                    val offer = find(instrumentation.uiAutomation.rootInActiveWindow) {
                        it.isVisibleToUser && it.text?.toString() == "Version 10.0.1 is available"
                    }
                    if (offer == null) scrollForward()
                }
                assertUpdateTextFitsScreen("Version 10.0.1 is available")
                assertUpdateTextFitsScreen("Update notice fixture.")
                assertUpdateTextFitsScreen("Version code: 1001")
                assertUpdateTextFitsScreen("Commit: ${manifest.commit}")
                assertUpdateTextFitsScreen("SHA-256: ${manifest.apk.sha256}")
                assertUpdateTextFitsScreen("Feed URL: https://updates.example.invalid/update.json")
                assertUpdateActionFitsScreen(
                    assertAccessibleUpdateAction("Download app update version 10.0.1"),
                )
                saveCapture("app-update-settings-max-text")
            }
        } finally {
            clearFixturePreferences()
        }
    }

    @Test fun homeUpdateNoticeWaitsForTemperatureDraftAndPendingCommand() {
        clearFixturePreferences()
        launchShell(appUpdateEnabled = true).use { scene ->
            prepareConfiguredShell(scene)
            val fixture = AtomicReference<SharedInstrumentShellFixture>()
            scene.onActivity {
                fixture.set(it.shellFixture!!)
                fixture.get().thinQGate.hold()
            }

            clickNode(awaitVisibleNode { it.contentDescription?.toString() == "Increase target temperature" })
            awaitVisibleNode { it.text?.toString() == "26" }
            scene.onActivity {
                it.shellFixture!!.setAppUpdateStateForTest(AppUpdateState.Available(updateNoticeManifest()))
            }
            assertTextAbsent("Update available · 10.0.1")
            awaitTrue("the temperature command reaches its deferred fake port") {
                fixture.get().counters.thinQCommandSubmissions.get() == 1 &&
                    fixture.get().thinQContext.commandController.state.value is
                    cloud.airchive.shared.data.thinq.AcCommandUiState.Sending
            }
            assertTextAbsent("Update available · 10.0.1")

            fixture.get().thinQGate.release()
            awaitTrue("the original command settles once") {
                fixture.get().thinQContext.commandController.state.value is
                    cloud.airchive.shared.data.thinq.AcCommandUiState.Confirmed
            }
            awaitVisibleNode { it.text?.toString() == "Update available · 10.0.1" }
            val confirmed = fixture.get().thinQContext.commandController.state.value as
                cloud.airchive.shared.data.thinq.AcCommandUiState.Confirmed
            val command = confirmed.outcome.command as
                cloud.airchive.shared.contract.thinq.AcCommand.SetTargetTemperature
            assertEquals(26.0, command.value, 0.0)
            assertEquals("The notice did not dispatch or repeat the AC command", 1,
                fixture.get().counters.thinQCommandSubmissions.get())
            assertEquals("The Home notice never checks the feed", 0, fixture.get().appUpdateCheckCountForTest())
        }
        clearFixturePreferences()
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
            clickDisclosure("Google account")
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

    @Test fun signedInRestoreShowsCheckingWithoutOfferingAnotherSignIn() {
        launchShell(initiallySignedIn = true, suspendInitialAccessCheck = true).use { scene ->
            val fixture = AtomicReference<SharedInstrumentShellFixture>()
            scene.onActivity { fixture.set(it.shellFixture!!) }

            awaitNode { it.text?.toString() == "Checking history access…" }
            awaitTrue("the initial restore remains suspended in the fake device-identity read") {
                fixture.get().counters.deviceIdentityReads.get() > 0 && !fixture.get().telemetryReadGate.isOpen
            }
            assertTextAbsent("Continue with Google")
            assertEquals("A restored session does not start a second account chooser", 0, fixture.get().counters.ownerSignInAttempts.get())
        }
    }

    @Test fun checkedRefreshClearsOfflineAccessFailureAndReenablesRangeLoading() {
        launchShell(initiallySignedIn = true, initialOfflineAccessCheck = true).use { scene ->
            val fixture = AtomicReference<SharedInstrumentShellFixture>()
            scene.onActivity { fixture.set(it.shellFixture!!) }

            awaitNode { it.text?.toString()?.contains("History access could not be verified yet.") == true }
            val readsBeforeCheck = fixture.get().counters.deviceIdentityReads.get()
            scene.onActivity {
                it.shellFixture!!.setTelemetryFailure(null)
                it.shellFixture!!.runCheckedRefreshForTest()
            }
            awaitTrue("the later checked refresh re-reads device access") {
                fixture.get().counters.deviceIdentityReads.get() > readsBeforeCheck
            }
            awaitNode { it.text?.toString() == "Connect ThinQ in Settings to read and control your AC." }
            assertTextAbsent("History access could not be verified yet.")

            clickTab("Energy")
            awaitVisibleNode { it.text?.toString() == "Offline history" }
            val retryRange = awaitVisibleNode { it.text?.toString() == "Retry history loading" }
            assertTrue("authorized history access enables selected-range loading", retryRange.isEnabled)
        }
    }

    @Test fun selectedHistoryDetailDoesNotReopenForAnotherOwnersMatchingSampleId() {
        clearFixturePreferences()
        launchShell().use { scene ->
            clickText("Continue with Google")
            awaitNode { it.text?.toString() == "Connect ThinQ in Settings to read and control your AC." }
            clickText("History")
            awaitNode { it.text?.toString() == "Sample status guide" }
            expandFirstHistoryHour()
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

    @Test fun delayedCredentialVerificationCannotSaveAfterSyntheticOwnerSwitch() {
        clearFixturePreferences()
        launchShell().use { scene ->
            clickText("Continue with Google")
            awaitNode { it.text?.toString() == "Connect ThinQ in Settings to read and control your AC." }
            awaitShellSettled(scene)
            clickTab("Settings")
            clickText("Set up ThinQ PAT")
            awaitVisibleNode { it.text?.toString() == "Verify and save securely" }
            setTextByHint("ThinQ Personal Access Token", SharedInstrumentShellFixture.SYNTHETIC_PAT_MARKER)

            val fixture = AtomicReference<SharedInstrumentShellFixture>()
            scene.onActivity {
                fixture.set(it.shellFixture!!)
                fixture.get().credentialVerificationGate.hold()
                fixture.get().setNextOwner("delayed-verification-owner-b")
            }
            clickText("Verify and save securely")
            awaitTrue("the fake verifier is suspended before its protected-store commit") {
                fixture.get().counters.credentialVerifications.get() == 1
            }
            assertEquals(0, fixture.get().counters.credentialSaves.get())

            clickDisclosure("Google account")
            clickText("Switch Google account")
            awaitNode { it.text?.toString() == "Switch Google account?" }
            clickText("Confirm")
            awaitTrue("the synthetic owner transition completes while verification is held") {
                fixture.get().coordinator.session.value?.uid == "delayed-verification-owner-b"
            }
            fixture.get().credentialVerificationGate.release()
            awaitTrue("the superseded verifier publishes a redacted completion record") {
                runBlocking {
                    fixture.get().captureSafeDiagnosticOutputs().any { it.startsWith("credential-setup=") }
                }
            }

            assertEquals("the late old-owner result cannot add a credential", 0,
                fixture.get().counters.credentialSaves.get())
            assertFalse("old setup feedback never appears for the replacement owner",
                find(instrumentation.uiAutomation.rootInActiveWindow) {
                    it.text?.toString()?.startsWith("The configured AC and profile were verified;") == true
                } != null)
            assertFalse("the synthetic PAT marker never reappears in the new owner UI",
                accessibilitySnapshot(instrumentation.uiAutomation.rootInActiveWindow)
                    .contains(SharedInstrumentShellFixture.SYNTHETIC_PAT_MARKER))
            assertEquals(0, fixture.get().counters.thinQCommandSubmissions.get())
        }
        clearFixturePreferences()
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

    @Test fun temperatureSendsFinalValueAfterOneSecondAndStaleStateDisablesControls() {
        launch().use { scene ->
            // Resolve accessibility bounds before starting the one-second clock;
            // fresh tree queries may wait for accessibility-idle on animated screens.
            val increase = Rect().also(awaitNode { it.contentDescription?.toString() == "Increase target temperature" }::getBoundsInScreen)
            val decrease = Rect().also(awaitNode { it.contentDescription?.toString() == "Decrease target temperature" }::getBoundsInScreen)
            injectTap(increase)
            SystemClock.sleep(250)
            injectTap(increase)
            SystemClock.sleep(250)
            injectTap(decrease)
            SystemClock.sleep(500)
            scene.onActivity { assertEquals("rapid taps reset the one-second delay", 0, it.submittedCommands) }
            awaitTrue("one command sends the final selection") {
                var count = 0
                scene.onActivity { count = it.submittedCommands }
                count == 1
            }
            SystemClock.sleep(1_200)
            scene.onActivity {
                assertEquals("confirmation never resends a draft", 1, it.submittedCommands)
                assertEquals(26.0, (it.lastSubmittedCommand as cloud.airchive.shared.contract.thinq.AcCommand.SetTargetTemperature).value, 0.0)
            }
            assertTextAbsent("Apply")
            assertTextAbsent("Target read from AC")
            assertTextAbsent("Adjust target temperature")
        }
        launch(stale = true).use { scene ->
            assertFalse(awaitNode { it.contentDescription?.toString() == "Increase target temperature" }.isEnabled)
            SystemClock.sleep(1_100)
            scene.onActivity { assertEquals(0, it.submittedCommands) }
        }
    }

    @Test fun navigationCancelsUnsentTemperatureAndReturningDoesNotRearmIt() {
        clearFixturePreferences()
        launchShell().use { scene ->
            prepareConfiguredShell(scene)
            val increase = awaitVisibleNode { it.contentDescription?.toString() == "Increase target temperature" }
            val settings = awaitNode { it.text?.toString() == "Settings" }
            assertTrue(increase.performAction(AccessibilityNodeInfo.ACTION_CLICK))
            clickNode(settings)
            SystemClock.sleep(1_100)
            scene.onActivity { assertEquals(0, it.shellFixture!!.counters.thinQCommandSubmissions.get()) }
            selectShellDetail(scene, cloud.airchive.shared.InterfaceDetail.DETAILED)
            selectShellDetail(scene, cloud.airchive.shared.InterfaceDetail.SIMPLE)
            clickTab("Home")
            awaitNode { it.text?.toString() == "26" }
            SystemClock.sleep(1_100)
            scene.onActivity { assertEquals("restoring a draft does not send it", 0, it.shellFixture!!.counters.thinQCommandSubmissions.get()) }
            clickTab("Device")
            awaitNode { it.text?.toString() == "26" }
            SystemClock.sleep(1_100)
            scene.onActivity { assertEquals(0, it.shellFixture!!.counters.thinQCommandSubmissions.get()) }
        }
        clearFixturePreferences()
    }

    @Test fun temperatureDraftShowsNotSentWhenAnotherCommandTakesTheSlot() {
        clearFixturePreferences()
        launchShell().use { scene ->
            prepareConfiguredShell(scene)
            val fixture = AtomicReference<SharedInstrumentShellFixture>()
            scene.onActivity { fixture.set(it.shellFixture!!) }
            clickNode(awaitVisibleNode { it.contentDescription?.toString() == "Increase target temperature" })
            awaitNode { it.text?.toString() == "26" }

            val dispatch = AtomicReference<cloud.airchive.shared.data.thinq.AcCommandDispatchResult>()
            scene.onActivity {
                val activeFixture = it.shellFixture!!
                activeFixture.thinQGate.hold()
                dispatch.set(activeFixture.thinQContext.dispatchCommand(
                    cloud.airchive.shared.contract.thinq.AcCommand.SetPower(
                        activeFixture.clock.now(), true,
                    ),
                    activeFixture.thinQContext.contextKey.value,
                ))
            }
            assertEquals(cloud.airchive.shared.data.thinq.AcCommandDispatchResult.Scheduled, dispatch.get())
            awaitVisibleNode { it.text?.toString()?.startsWith("Sending power") == true }
            awaitVisibleNode { it.text?.toString() == "Not sent · choose again to send" }
            assertEquals("the competing command owns the one available slot", 1,
                fixture.get().counters.thinQCommandSubmissions.get())
            fixture.get().thinQGate.release()
            awaitTrue("the original power command completes") {
                fixture.get().thinQContext.commandController.state.value is
                    cloud.airchive.shared.data.thinq.AcCommandUiState.Confirmed
            }
            SystemClock.sleep(1_100)
            assertEquals("the rejected temperature draft is never retried", 1,
                fixture.get().counters.thinQCommandSubmissions.get())
        }
        clearFixturePreferences()
    }

    @Test fun pendingCommandAndGraphSurviveActivityRecreation() {
        clearFixturePreferences()
        launchShell(retainFixtureForRecreation = true).use { scene ->
            prepareConfiguredShell(scene)
            val fixture = AtomicReference<SharedInstrumentShellFixture>()
            scene.onActivity {
                fixture.set(it.shellFixture!!)
                fixture.get().thinQGate.hold()
            }
            clickNode(awaitVisibleNode { it.contentDescription?.toString() == "Increase target temperature" })
            awaitVisibleNode { it.text?.toString()?.startsWith("Sending target temperature ") == true }
            awaitTrue("the graph-owned command is suspended in the fake port") {
                fixture.get().counters.thinQCommandSubmissions.get() == 1 &&
                    fixture.get().thinQContext.commandController.state.value is
                    cloud.airchive.shared.data.thinq.AcCommandUiState.Sending
            }

            scene.recreate()
            awaitVisibleNode { it.text?.toString()?.startsWith("Sending target temperature ") == true }
            val increase = awaitVisibleNode { it.contentDescription?.toString() == "Increase target temperature" }
            assertFalse("a second tap is disabled while the original command is pending", increase.isEnabled)
            val bounds = Rect().also(increase::getBoundsInScreen)
            assertTrue(uiDevice.click(bounds.centerX(), bounds.centerY()))
            SystemClock.sleep(1_100)
            scene.onActivity {
                assertSame("Activity recreation rebinds to the process-owned fixture", fixture.get(), it.shellFixture)
            }
            assertEquals("the second tap did not submit another command", 1,
                fixture.get().counters.thinQCommandSubmissions.get())

            fixture.get().thinQGate.release()
            awaitTrue("the original command completes after its gate is released") {
                fixture.get().thinQContext.commandController.state.value is
                    cloud.airchive.shared.data.thinq.AcCommandUiState.Confirmed
            }
            assertEquals(1, fixture.get().counters.thinQCommandSubmissions.get())
        }
        clearFixturePreferences()
    }

    @Test fun deviceRefreshCancelsUnsentTemperature() {
        clearFixturePreferences()
        launchShell().use { scene ->
            prepareConfiguredShell(scene)
            clickTab("Device")
            val increase = awaitVisibleNode { it.contentDescription?.toString() == "Increase target temperature" }
            val fixture = AtomicReference<SharedInstrumentShellFixture>()
            scene.onActivity { fixture.set(it.shellFixture!!); it.shellFixture!!.thinQGate.hold() }
            injectTap(Rect().also(increase::getBoundsInScreen))
            instrumentation.runOnMainSync { fixture.get().refreshThinQForTest() }
            SystemClock.sleep(1_200)
            assertEquals("refresh cancels the unsent edit", 0, fixture.get().counters.thinQCommandSubmissions.get())
            fixture.get().thinQGate.release()
            awaitTrue("refresh finishes") {
                fixture.get().thinQContext.liveStateController.state.value is cloud.airchive.shared.data.thinq.ThinQLiveRefreshState.Current
            }
            SystemClock.sleep(1_100)
            assertEquals("a refreshed observation never queues an old edit", 0, fixture.get().counters.thinQCommandSubmissions.get())
        }
        clearFixturePreferences()
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

            // Hold the fake before the tap: the target is sent 1 s after the last change, which could
            // otherwise complete before a hold placed after the tap.
            val fixture = AtomicReference<SharedInstrumentShellFixture>()
            scene.onActivity {
                fixture.set(it.shellFixture!!)
                fixture.get().thinQGate.hold()
            }
            clickNode(awaitNode { it.contentDescription?.toString() == "Increase target temperature" })
            awaitNode { it.text?.toString() == "26" }
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

            // Home still shows the held command after the mode changes. Its "… confirmed." message replaces
            // that row for only 3 s, which cannot be observed reliably through accessibility here, so check
            // the lasting outcome instead: the command is confirmed once and the pending row is gone.
            clickTab("Home")
            awaitVisibleNode { it.text?.toString()?.startsWith("Sending target temperature ") == true }
            fixture.get().thinQGate.release()
            awaitTrue("the original command completes exactly once") {
                fixture.get().thinQContext.commandController.state.value is
                    cloud.airchive.shared.data.thinq.AcCommandUiState.Confirmed
            }
            awaitTrue("Home no longer shows the pending command") {
                instrumentation.uiAutomation.clearCache()
                find(instrumentation.uiAutomation.rootInActiveWindow) {
                    it.text?.toString()?.startsWith("Sending target temperature ") == true
                } == null
            }
            assertEquals(1, fixture.get().counters.thinQCommandSubmissions.get())
        }
    }

    @Test fun modeSliderSendsOnceOnRelease() {
        launch().use { scene ->
            val slider = awaitModeSlider()
            val sliderState = slider.contentDescription?.toString().orEmpty()
            assertTrue(
                "the slider exposes its currently confirmed named value; actual='$sliderState'",
                sliderState.contains("Current confirmed value Fan"),
            )
            listOf("Fan", "Dry").forEach { supportedMode ->
                awaitAccessibilityNode("supported mode $supportedMode") { it.text?.toString() == supportedMode }
            }
            val sliderBounds = Rect().also(slider::getBoundsInScreen)
            val y = sliderBounds.centerY()
            assertTrue(uiDevice.swipe(sliderBounds.left + sliderBounds.width() / 8, y,
                sliderBounds.left + sliderBounds.width() * 2 / 3, y, 20))
            SystemClock.sleep(100)
            scene.onActivity { assertEquals(1, it.submittedCommands) }
        }
    }

    @Test fun modeSliderSupportsTappingAStop() {
        launch().use { scene ->
            val slider = awaitModeSlider()
            val sliderBounds = Rect().also(slider::getBoundsInScreen)

            assertTrue(uiDevice.click(sliderBounds.left + sliderBounds.width() * 9 / 10, sliderBounds.centerY()))
            awaitTrue("tapping a supported stop submits exactly once") {
                var count = 0
                scene.onActivity { count = it.submittedCommands }
                count == 1
            }
            awaitComposedFrames()
            scene.onActivity { assertEquals(1, it.submittedCommands) }
        }
    }

    @Test fun unknownSelectorHasNoThumbAndSendsOnlyAfterAChoice() {
        launch(unknownFan = true).use { scene ->
            val slider = awaitVisibleNode {
                it.contentDescription?.toString()?.startsWith("Fan speed selection.") == true
            }
            assertEquals("Current AUTO; not settable here", slider.stateDescription?.toString())
            assertEquals("Current AUTO · not settable here", awaitVisibleNode {
                it.text?.toString() == "Current AUTO · not settable here"
            }.text?.toString())
            assertEquals("no option is selected before the user chooses one", 0.5f, slider.rangeInfo.current, 0.01f)
            scene.onActivity { assertEquals("the unknown value sends no command", 0, it.submittedCommands) }

            assertTrue(
                slider.performAction(
                    AccessibilityNodeInfo.AccessibilityAction.ACTION_SET_PROGRESS.id,
                    Bundle().apply { putFloat(AccessibilityNodeInfo.ACTION_ARGUMENT_PROGRESS_VALUE, 0f) },
                ),
            )
            awaitTrue("choosing LOW deliberately sends exactly one command") {
                var count = 0
                scene.onActivity { count = it.submittedCommands }
                count == 1
            }
            scene.onActivity {
                val command = it.lastSubmittedCommand as cloud.airchive.shared.contract.thinq.AcCommand.SetFanSpeed
                assertEquals(cloud.airchive.shared.contract.thinq.FanSpeed.LOW, command.speed)
            }
        }
    }

    @Test fun acceptedCommandRemainsUnconfirmedWithoutAutomaticRetry() {
        clearFixturePreferences()
        launchShell().use { scene ->
            prepareConfiguredShell(scene)
            val fixture = AtomicReference<SharedInstrumentShellFixture>()
            scene.onActivity { fixture.set(it.shellFixture!!) }
            fixture.get().setThinQAcceptedWithoutConfirmationForTest()
            clickNode(awaitVisibleNode { it.contentDescription?.toString() == "Increase target temperature" })
            awaitTrue("the synthetic port reports accepted without confirming") {
                fixture.get().thinQContext.commandController.state.value is
                    cloud.airchive.shared.data.thinq.AcCommandUiState.Accepted
            }
            awaitVisibleNode { it.text?.toString()?.contains("current state is not confirmed") == true }
            assertEquals(1, fixture.get().counters.thinQCommandSubmissions.get())
            SystemClock.sleep(300)
            assertEquals("accepted status does not trigger an automatic retry", 1,
                fixture.get().counters.thinQCommandSubmissions.get())
        }
        clearFixturePreferences()
    }

    @Test fun failedCommandShowsStableFeedbackWithoutRetry() {
        clearFixturePreferences()
        launchShell().use { scene ->
            prepareConfiguredShell(scene)
            val fixture = AtomicReference<SharedInstrumentShellFixture>()
            scene.onActivity { fixture.set(it.shellFixture!!) }
            fixture.get().setThinQCommandFailureForTest(
                cloud.airchive.shared.contract.thinq.CommandFailure.RATE_LIMITED,
            )
            clickNode(awaitVisibleNode { it.contentDescription?.toString() == "Increase target temperature" })
            awaitTrue("the synthetic port reports a definite command failure") {
                fixture.get().thinQContext.commandController.state.value is
                    cloud.airchive.shared.data.thinq.AcCommandUiState.Failed
            }
            awaitVisibleNode { it.text?.toString()?.contains("LG is rate-limiting commands") == true }
            assertEquals(1, fixture.get().counters.thinQCommandSubmissions.get())
            SystemClock.sleep(300)
            assertEquals("failed command feedback does not trigger a retry", 1,
                fixture.get().counters.thinQCommandSubmissions.get())
        }
        clearFixturePreferences()
    }

    @Test fun unconfirmedCommandOffersRefreshAndNeverResends() {
        clearFixturePreferences()
        launchShell().use { scene ->
            prepareConfiguredShell(scene)
            val fixture = AtomicReference<SharedInstrumentShellFixture>()
            scene.onActivity { fixture.set(it.shellFixture!!) }
            fixture.get().setThinQUnconfirmedForTest()
            clickNode(awaitVisibleNode { it.contentDescription?.toString() == "Increase target temperature" })
            awaitTrue("the synthetic port reports an ambiguous command result") {
                fixture.get().thinQContext.commandController.state.value is
                    cloud.airchive.shared.data.thinq.AcCommandUiState.Unconfirmed
            }
            awaitVisibleNode { it.text?.toString()?.contains("may have been applied") == true }
            assertEquals(1, fixture.get().counters.thinQCommandSubmissions.get())

            clickTab("Device")
            awaitVisibleNode { it.text?.toString() == "Refresh device state" }
            val liveReadsBeforeRefresh = fixture.get().counters.thinQLiveStateReads.get()
            clickText("Refresh device state")
            awaitTrue("the owner can explicitly refresh after an unconfirmed command") {
                fixture.get().counters.thinQLiveStateReads.get() > liveReadsBeforeRefresh
            }
            awaitVisibleNode { it.text?.toString()?.contains("may have been applied") == true }
            assertEquals("the explicit recovery action reads state but never resubmits", 1,
                fixture.get().counters.thinQCommandSubmissions.get())
        }
        clearFixturePreferences()
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
            scene.onActivity { it.shellFixture!!.setNextOwner("temperature-draft-owner-b") }

            clickText("Settings")
            clickDisclosure("Google account")
            assertDisclosureState("Google account", "Expanded")
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
                find(root) { it.text?.toString() == "25 °C" } == null &&
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
            clickDisclosure("Google account")
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
            // The manual history refresh action lives on the History overview card.
            clickTab("History")
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
            // Opening Device is presentation-only once this context has read (ThinQContextCoordinator
            // performDeviceEntry); the explicit refresh control performs the new live read.
            clickText("Refresh device state")
            awaitTrue("the explicit device refresh performs its own live read") {
                fixture.get().counters.thinQLiveStateReads.get() > liveReadsBeforeDeviceEntry.get()
            }
            awaitTrue("the synthetic live read publishes its current state") {
                fixture.get().thinQContext.liveStateController.state.value is
                    cloud.airchive.shared.data.thinq.ThinQLiveRefreshState.Current
            }
            assertEquals("device refreshes use the controlled device-read clock",
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
            awaitNode { it.text?.toString()?.startsWith("Current power consumption as of ") == true }
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
            expandFirstHistoryHour()
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
            clickDisclosure("Google account")
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
            clickDisclosure("History access")
            assertDisclosureState("History access", "Collapsed")
            assertTextAbsent("Status: signed out of Google.")
            selectShellDetail(scene, cloud.airchive.shared.InterfaceDetail.DETAILED)
            assertDisclosureState("History access", "Expanded")
            assertTextPresent("Status: signed out of Google.")
            clickDisclosure("History access")
            assertDisclosureState("History access", "Collapsed")
            selectShellDetail(scene, cloud.airchive.shared.InterfaceDetail.SIMPLE)
            assertDisclosureState("History access", "Expanded")
            assertTextPresent("Status: signed out of Google.")

            clickTab("Home")
            clickText("Continue with Google")
            awaitVisibleNode { it.text?.toString() == "Connect ThinQ in Settings to read and control your AC." }
            awaitTrue("the fake history access check completes") {
                var reads = 0
                scene.onActivity { reads = it.shellFixture!!.counters.deviceIdentityReads.get() }
                reads > 0
            }
            awaitVisibleNode { it.text?.toString() == "History synced 0 sec ago" }
            awaitShellSettled(scene)

            // Simple keeps all optional information and action sections closed.
            clickTab("Device")
            assertDisclosureState("Device details", "Collapsed")
            assertTextAbsent("Device ID")

            clickTab("Energy")
            assertDisclosureState("Coverage and measurement details", "Collapsed")
            assertDisclosureState("Daily record", "Collapsed")
            assertDisclosureState("Units and conversions", "Collapsed")
            assertTextAbsent("Period summary")
            assertTextAbsent("Elapsed five-minute slots:")
            // Unit explanation copy was split into detail rows (2ff256f); a closed section hides them all.
            assertTextAbsent("Recorded units are preserved; recognized values are normalized to Wh for comparison.")
            // A manual override is permitted, and exposes the daily children.
            clickDisclosure("Daily record")
            assertDisclosureState("Daily record", "Expanded")
            awaitVisibleNode { it.text?.toString()?.startsWith("Elapsed five-minute slots:") == true }

            clickTab("History")
            awaitNode { it.text?.toString() == "Recorded hours (newest first)" }
            assertDisclosureState("Sync and collector health", "Collapsed")
            assertDisclosureState("Sample status guide", "Collapsed")
            assertTextAbsent("What each sample status means")

            clickTab("Settings")
            scrollToTop()
            awaitVisibleNode { it.text?.toString() == "Interface detail" }
            selectShellDetail(scene, cloud.airchive.shared.InterfaceDetail.DETAILED)
            assertDisclosureState("History and storage", "Expanded")
            awaitVisibleNode { it.text?.toString() == "Local diagnostics · nonsecret" }
            scrollBackward()
            assertDisclosureState("History access", "Expanded")
            assertDisclosureState("Google account", "Collapsed")
            selectShellDetail(scene, cloud.airchive.shared.InterfaceDetail.SIMPLE)
            scene.onActivity {
                assertEquals(cloud.airchive.shared.InterfaceDetail.SIMPLE,
                    it.shellFixture!!.interfacePreferences.detail.value)
            }
            assertDisclosureState("History and storage", "Collapsed")
            assertTextAbsent("Local diagnostics · nonsecret")
            assertDisclosureState("AC connection", "Collapsed")
            assertDisclosureState("Google account", "Collapsed")
            assertTextAbsent("LG client ID")
            assertTextAbsent("Switch Google account")

            // Manual expansion is visible until a mode change; changing modes
            // resets informational sections to their defaults.
            clickDisclosure("AC connection")
            assertDisclosureState("AC connection", "Expanded")
            awaitVisibleNode { it.text?.toString()?.contains("LG client ID") == true }
            scrollToTop()
            selectShellDetail(scene, cloud.airchive.shared.InterfaceDetail.DETAILED)
            assertDisclosureState("AC connection", "Expanded")
            assertDisclosureState("Google account", "Collapsed")
            awaitVisibleNode { it.text?.toString()?.contains("LG client ID") == true }
            assertTextAbsent("Switch Google account")

            clickDisclosure("AC connection")
            assertDisclosureState("AC connection", "Collapsed")
            scrollToTop()
            selectShellDetail(scene, cloud.airchive.shared.InterfaceDetail.SIMPLE)
            assertDisclosureState("AC connection", "Collapsed")
            selectShellDetail(scene, cloud.airchive.shared.InterfaceDetail.DETAILED)
            assertDisclosureState("AC connection", "Expanded")
            assertDisclosureState("Google account", "Collapsed")

            // The action section never follows Detailed's informational-open rule.
            clickDisclosure("Google account")
            assertDisclosureState("Google account", "Expanded")
            assertTextPresent("Switch Google account")
            scrollToTop()
            selectShellDetail(scene, cloud.airchive.shared.InterfaceDetail.SIMPLE)
            assertDisclosureState("Google account", "Collapsed")
            assertTextAbsent("Switch Google account")
            scrollToTop()
            selectShellDetail(scene, cloud.airchive.shared.InterfaceDetail.DETAILED)
            assertDisclosureState("Google account", "Collapsed")

            // Settings/cache confirmation is outside the resetting disclosure.
            clickDisclosure("Google account")
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
            assertTextPresent("Device ID")

            clickTab("Energy")
            awaitVisibleNode { it.text?.toString() == "Sampled five-minute interval energy" }
            awaitVisibleNode { it.text?.toString()?.startsWith("Elapsed five-minute slots:") == true }
            awaitVisibleNode { it.text?.toString() == "Recorded units are preserved; recognized values are normalized to Wh for comparison." }
            awaitVisibleNode { it.text?.toString() == "No conversion is guessed. These values are excluded from usable energy sums." }
            awaitVisibleNode { it.text?.toString() == "An average over the measured interval, never instantaneous wattage." }

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

    @Test fun energySummaryIsIdenticalInSimpleAndDetailed() {
        clearFixturePreferences()
        launchShell().use { scene ->
            clickText("Continue with Google")
            awaitNode { it.text?.toString() == "Connect ThinQ in Settings to read and control your AC." }
            awaitVisibleNode { it.text?.toString() == "History synced 0 sec ago" }
            awaitShellSettled(scene)

            clickTab("Settings")
            selectShellDetail(scene, cloud.airchive.shared.InterfaceDetail.DETAILED)
            clickTab("Energy")
            awaitNode { it.text?.toString() == "Net daily power consumed" }
            val detailedTruth = energySummarySnapshot()

            clickTab("Settings")
            selectShellDetail(scene, cloud.airchive.shared.InterfaceDetail.SIMPLE)
            clickTab("Energy")
            awaitNode { it.text?.toString() == "Net daily power consumed" }
            assertEquals(detailedTruth, energySummarySnapshot())
        }
        clearFixturePreferences()
    }

    @Test fun energyPeriodAutomaticallyLoadsMissingRangesAndCoveredRetriesAreLocal() {
        clearFixturePreferences()
        launchShell().use { scene ->
            clickText("Continue with Google")
            awaitNode { it.text?.toString() == "Connect ThinQ in Settings to read and control your AC." }
            awaitNode { it.text?.toString() == "History synced 0 sec ago" }
            awaitShellSettled(scene)
            clickText("Energy")
            awaitVisibleNode { it.text?.toString() == "Retry history loading" }
            val fixture = AtomicReference<SharedInstrumentShellFixture>()
            scene.onActivity { fixture.set(it.shellFixture!!) }
            clickText("Month")
            awaitNode { it.text?.toString()?.contains("remote page(s) fetched") == true }
            awaitShellSettled(scene)
            val owner = requireNotNull(fixture.get().coordinator.session.value)
            val partition = fixture.get().coordinator.partitionFor(owner)
            val now = fixture.get().clock.now()
            val date = kotlinx.datetime.LocalDate.parse(java.time.Instant.ofEpochMilli(now).atZone(java.time.ZoneId.of("Asia/Manila")).toLocalDate().toString())
            val month = cloud.airchive.shared.data.energy.energyPeriodRange(cloud.airchive.shared.data.energy.EnergyPeriodType.MONTH, date)
            val snapshot = runBlocking { fixture.get().coordinator.local.observe(partition).first() }
            val elapsed = (minOf(month.endExclusiveEpochMillis, now) - month.startEpochMillis).coerceAtLeast(0L)
            val completedEnd = month.startEpochMillis + elapsed / 300_000L * 300_000L
            assertTrue(cloud.airchive.shared.data.sync.HistoricalRangeLoader.uncoveredRanges(month.startEpochMillis, completedEnd, snapshot.coveredRanges).isEmpty())
            val reads = fixture.get().counters.telemetryReads.get()
            clickText("Retry history loading")
            awaitLoadButtonEnabled()
            awaitShellSettled(scene)
            assertEquals("a covered retry performs no telemetry reads", reads, fixture.get().counters.telemetryReads.get())
            clickText("Next")
            awaitNode { it.text?.toString()?.contains("nothing to fetch") == true }
            assertEquals("future periods do not fetch", reads, fixture.get().counters.telemetryReads.get())
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
            assertTrue(accessibilitySnapshot(instrumentation.uiAutomation.rootInActiveWindow).contains("Current power consumption as of "))
            assertTextAbsent("Adjust target temperature")
            assertTextAbsent("Apply")
            assertTextPresent("Mode")
            assertTextPresent("Fan speed")

            val fixture = AtomicReference<SharedInstrumentShellFixture>()
            scene.onActivity { fixture.set(it.shellFixture!!) }
            fixture.get().setThinQReadFailure(cloud.airchive.shared.data.thinq.ThinQClientException.Network)
            clickText("Device")
            // Opening Device is presentation-only once the context has read (ThinQContextCoordinator
            // performDeviceEntry); only the explicit refresh control performs a new read.
            clickText("Refresh device state")
            awaitVisibleNode { it.text?.toString() == "Could not reach ThinQ. Check your connection, then refresh device state." }
            assertFalse(accessibilitySnapshot(instrumentation.uiAutomation.rootInActiveWindow).contains("Current power consumption as of "))
            assertFalse(awaitNode { it.contentDescription?.toString() == "Increase target temperature" }.isEnabled)

            fixture.get().setThinQReadFailure(null)
            clickText("Refresh device state")
            awaitTrue("a successful synthetic re-read restores supported controls") {
                find(instrumentation.uiAutomation.rootInActiveWindow) {
                    it.contentDescription?.toString() == "Increase target temperature"
                }?.isEnabled == true
            }
            assertTextAbsent("Could not reach ThinQ. Check your connection, then refresh device state.")
            assertTextPresent("Set temperature")
            assertTextAbsent("Apply")
            assertTextPresent("Mode")
            assertTextPresent("Fan speed")

            clickText("Energy")
            awaitNode { it.text?.toString() == "Net daily power consumed" }
            awaitVisibleNode { it.text?.toString() == "Retry history loading" }
            assertTextPresent("Coverage and measurement details")
            // The summary must precede the offline-loading section. Only part of this long page is in the
            // accessibility tree at once, so compare on-screen positions rather than snapshot text order:
            // the summary is found first, and once Offline history is reached it is above that section or
            // already scrolled out of the tree.
            awaitVisibleNode { it.text?.toString() == "Net daily power consumed" }
            val offlineHistory = awaitVisibleNode { it.text?.toString() == "Offline history" }
            instrumentation.uiAutomation.clearCache()
            val summaryNow = find(instrumentation.uiAutomation.rootInActiveWindow) { it.text?.toString() == "Net daily power consumed" }
            val offlineTop = Rect().also(offlineHistory::getBoundsInScreen).top
            assertTrue("Net daily power consumed stays above Offline history",
                summaryNow == null || Rect().also(summaryNow::getBoundsInScreen).bottom <= offlineTop)

            clickText("History")
            awaitVisibleNode { it.text?.toString() == "Recorded hours (newest first)" }
            expandFirstHistoryHour()
            val firstSample = awaitNode { it.contentDescription?.toString()?.startsWith("Sample at ") == true }
            clickNode(firstSample)
            awaitNode { it.text?.toString() == "Sample detail" }
            clickText("Close")

            clickText("Settings")
            awaitNode { it.text?.toString() == "Interface detail" }
            assertTextPresent("Connections")
            assertTextPresent("AC connection")
            assertTextPresent("History access")
            assertTextPresent("History and storage")
            assertTextPresent("Google account")
            assertEquals(0, fixture.get().counters.thinQCommandSubmissions.get())
        }
        clearFixturePreferences()
    }

    @Test fun typedDeviceReadFailuresShowCauseAndRecoveryInBothInterfaceModes() {
        clearFixturePreferences()
        launchShell().use { scene ->
            prepareConfiguredShell(scene)
            val fixture = AtomicReference<SharedInstrumentShellFixture>()
            scene.onActivity { fixture.set(it.shellFixture!!) }

            val cases: List<Triple<(SharedInstrumentShellFixture) -> Unit, String, String>> = listOf(
                Triple(
                    { it.setThinQReadFailureKind(cloud.airchive.shared.data.thinq.ThinQReadFailure.DEVICE_UNAVAILABLE) },
                    "The AC is offline or unavailable. Check its power and network, then refresh device state.",
                    "Refresh device state",
                ),
                Triple(
                    { it.setThinQReadFailureKind(cloud.airchive.shared.data.thinq.ThinQReadFailure.RATE_LIMITED) },
                    "ThinQ is rate-limiting device reads. Wait before refreshing.",
                    "Refresh device state",
                ),
                Triple(
                    { it.setThinQReadFailure(cloud.airchive.shared.data.thinq.ThinQClientException.Network) },
                    "Could not reach ThinQ. Check your connection, then refresh device state.",
                    "Refresh device state",
                ),
                Triple(
                    { it.setThinQReadFailureKind(cloud.airchive.shared.data.thinq.ThinQReadFailure.INVALID_TOKEN) },
                    "ThinQ rejected this token. Replace the ThinQ PAT to restore device access.",
                    "Replace token",
                ),
            )

            for (detail in listOf(cloud.airchive.shared.InterfaceDetail.SIMPLE, cloud.airchive.shared.InterfaceDetail.DETAILED)) {
                scene.onActivity { fixture.get().interfacePreferences.select(detail) }
                for ((inject, message, action) in cases.dropLast(1)) {
                    inject(fixture.get())
                    clickTab("Device")
                    clickText("Refresh device state")
                    awaitVisibleNode { it.text?.toString() == message }
                    awaitVisibleNode { it.text?.toString() == action }
                    assertFalse(
                        accessibilitySnapshot(instrumentation.uiAutomation.rootInActiveWindow)
                            .contains("synthetic fake read failure"),
                    )

                    fixture.get().setThinQReadFailureKind(null)
                    fixture.get().setThinQReadFailure(null)
                    clickText("Refresh device state")
                    awaitTrue("clearing the injected failure restores the verified controls") {
                        find(instrumentation.uiAutomation.rootInActiveWindow) {
                            it.contentDescription?.toString() == "Increase target temperature"
                        }?.isEnabled == true
                    }
                }
            }

            val tokenFailure = cases.last()
            scene.onActivity { fixture.get().interfacePreferences.select(cloud.airchive.shared.InterfaceDetail.SIMPLE) }
            tokenFailure.first(fixture.get())
            clickTab("Device")
            clickText("Refresh device state")
            awaitVisibleNode { it.text?.toString() == tokenFailure.second }
            awaitVisibleNode { it.text?.toString() == tokenFailure.third }
            scene.onActivity { fixture.get().interfacePreferences.select(cloud.airchive.shared.InterfaceDetail.DETAILED) }
            awaitVisibleNode { it.text?.toString() == tokenFailure.second }
            awaitVisibleNode { it.text?.toString() == tokenFailure.third }
        }
        clearFixturePreferences()
    }

    @Test fun historyEmptyNoUsableOfflineDeniedLoadingAndErrorStatesStayTruthful() {
        clearFixturePreferences()
        launchShell().use { scene ->
            clickText("Continue with Google")
            awaitNode { it.text?.toString() == "Connect ThinQ in Settings to read and control your AC." }
            awaitVisibleNode { it.text?.toString() == "History synced 0 sec ago" }
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
            // The refresh action now lives on the History overview card; Home only reports the status.
            clickTab("History")
            clickText("Refresh history")
            awaitNode { it.text?.toString()?.contains("could not be reached. Showing cached history") == true }
            awaitShellSettled(scene)
            // The retained reading now sits inside its hour row; open the hour to confirm the cached
            // 21 Wh sample itself is still listed.
            if (find(instrumentation.uiAutomation.rootInActiveWindow) {
                    it.contentDescription?.toString()?.startsWith("Sample at ") == true
                } == null) expandFirstHistoryHour()
            awaitTrue("the retained 21 Wh sample stays listed") {
                instrumentation.uiAutomation.clearCache()
                find(instrumentation.uiAutomation.rootInActiveWindow) {
                    it.contentDescription?.toString()?.contains("21 Wh") == true
                } != null
            }
            clickTab("Home")
            awaitVisibleNode { it.text?.toString()?.contains("could not be reached. Showing cached history") == true }

            // Already on Home; the status lookup scrolled it, so return to the heading.
            awaitVisibleNode { it.text?.toString() == "Airchive" }
            awaitShellSettled(scene)
            fixture.get().setTelemetryFailure(cloud.airchive.shared.contract.telemetry.TelemetryReadFailure.AccessDenied())
            val readsBeforeDenied = AtomicInteger()
            scene.onActivity { readsBeforeDenied.set(fixture.get().counters.telemetryReads.get()) }
            // After the offline failure the action reads "Retry history sync" until a sync succeeds.
            clickTab("History")
            clickText("Retry history sync")
            awaitTrue("the denied-history retry reaches the synthetic telemetry fake") {
                fixture.get().counters.telemetryReads.get() > readsBeforeDenied.get()
            }
            try {
                awaitNode { it.text?.toString()?.contains("access is pending or denied. Showing cached history") == true }
                clickTab("Home")
                awaitVisibleNode { it.text?.toString()?.contains("access is pending or denied. Showing cached history") == true }
            } catch (failure: AssertionError) {
                throw AssertionError("history status=${visibleHistorySyncLabels()}", failure)
            }

            awaitShellSettled(scene)
            fixture.get().setTelemetryFailure(cloud.airchive.shared.contract.telemetry.TelemetryReadFailure.RemoteFailure())
            clickTab("History")
            clickText("Retry history sync")
            awaitNode { it.text?.toString()?.contains("last Airchive refresh failed. Showing cached history") == true }
            awaitShellSettled(scene)
            clickTab("Home")
            awaitVisibleNode { it.text?.toString()?.contains("last Airchive refresh failed. Showing cached history") == true }

            fixture.get().setTelemetryFailure(null)
            fixture.get().telemetryReadGate.hold()
            val readsBeforeLoading = AtomicInteger()
            scene.onActivity { readsBeforeLoading.set(fixture.get().counters.telemetryReads.get()) }
            clickTab("History")
            clickText("Retry history sync")
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
            clickTab("Home")
            awaitVisibleNode { it.text?.toString() == "History synced 0 sec ago" }
            assertEquals(0, fixture.get().counters.thinQCommandSubmissions.get())
        }
        clearFixturePreferences()
    }

    @Test fun historyThousandsRowLedgerScrollsAndSelectsNewestRowsLazily() {
        clearFixturePreferences()
        launchShell().use { scene ->
            clickText("Continue with Google")
            awaitNode { it.text?.toString() == "Connect ThinQ in Settings to read and control your AC." }
            awaitVisibleNode { it.text?.toString() == "History synced 0 sec ago" }
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
            // Samples now live inside collapsed hour rows; open the newest hour to reach its rows.
            expandFirstHistoryHour()
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
            // The newest (in-progress) hour holds a single reading, so open an older hour to select a
            // row that was reached by scrolling rather than the first viewport.
            expandFirstHistoryHour()
            awaitTrue("an older hour exposes a reading other than the newest row") {
                instrumentation.uiAutomation.clearCache()
                find(instrumentation.uiAutomation.rootInActiveWindow) {
                    it.contentDescription?.toString()?.startsWith("Sample at ") == true &&
                        it.contentDescription?.toString()?.contains("102999") != true
                } != null
            }
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
                        // Device details sits below the controls, outside the viewport and so outside the
                        // accessibility tree, until the page scrolls. Scroll to find it, then return to the
                        // top so the capture keeps framing the start of each route.
                        awaitVisibleNode { it.text?.toString() == when(route) { 1 -> "Device details"; 2 -> "Net daily power consumed"; 3 -> "Sample status guide"; else -> "Google account" } }
                        repeat(4) { scrollBackward() }
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
                        awaitNode { it.text?.toString() == "Google account" }
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

    @Test fun narrowPhoneWindowKeepsReadingsImeAndDialogReachable() {
        val metrics = instrumentation.targetContext.resources.displayMetrics
        val widthDp = metrics.widthPixels / metrics.density
        assertTrue("the emulator window is measured below 300dp (was ${widthDp}dp)", widthDp < 300f)
        assertEquals("the narrow-width run uses the default scale", 1.0f,
            instrumentation.targetContext.resources.configuration.fontScale, 0.01f)
        clearFixturePreferences()

        launchShell().use { scene ->
            signInAndConnectSyntheticThinQ(scene)
            clickTab("Home")
            awaitNode { it.text?.toString() == "Airchive" }
            // The sampled reading sits below the thermostat dial, outside the first narrow viewport and
            // so outside the accessibility tree; scroll just far enough to bring it into view.
            awaitVisibleNode { it.text?.toString()?.contains("Current power consumption as of ") == true }
            val homeTree = accessibilitySnapshot(instrumentation.uiAutomation.rootInActiveWindow)
            assertTrue("Home exposes the temperature reading: $homeTree", homeTree.contains("Set temperature"))
            assertTrue("Home exposes the sampled-energy reading: $homeTree", homeTree.contains("Current power consumption as of "))

            val root = instrumentation.uiAutomation.rootInActiveWindow
            val temperature = find(root) { it.text?.toString()?.contains("Set temperature") == true }
                ?: throw AssertionError("Temperature reading semantics were unavailable: $homeTree")
            val sampled = find(root) { it.text?.toString()?.contains("Current power consumption as of ") == true }
                ?: throw AssertionError("Sampled reading semantics were unavailable: $homeTree")
            val temperatureBounds = Rect().also(temperature::getBoundsInScreen)
            val sampledBounds = Rect().also(sampled::getBoundsInScreen)
            assertTrue("the reading fields measure below 300dp",
                temperatureBounds.width() / metrics.density < 300f && sampledBounds.width() / metrics.density < 300f)
            assertTrue("the two readings stack before the values shrink",
                sampledBounds.top > temperatureBounds.top)
            // The long value is "Today's net consumption", further down the page than the two readings
            // compared above; scroll to it so its full text is in the tree.
            awaitVisibleNode { it.text?.toString()?.contains("15.11") == true }
            val valueTree = accessibilitySnapshot(instrumentation.uiAutomation.rootInActiveWindow)
            assertTrue("the long sampled value stays complete: $valueTree", valueTree.contains("15.11"))
            assertTrue("the current route exposes selected semantics", selectedDestination("Home"))
            val heading = awaitVisibleNode { it.text?.toString() == "Airchive" }
            val headingBounds = Rect().also(heading::getBoundsInScreen)
            assertTrue("the route heading begins below the status inset",
                headingBounds.top >= systemBarDimensionPx("status_bar_height"))
            val homeTab = awaitVisibleNode { it.text?.toString() == "Home" }
            val tabBounds = Rect().also(homeTab::getBoundsInScreen)
            assertTrue("the route tab stays above the system navigation inset",
                tabBounds.bottom <= metrics.heightPixels - systemBarDimensionPx("navigation_bar_height"))
            saveCapture("synthetic-home-narrow-under-300dp-scale-1.0")

            scrollForward()
            saveCapture("synthetic-home-narrow-controls-reachable-scale-1.0")

            clickTab("Settings")
            clickText("Replace ThinQ PAT")
            val fields = awaitEditableFields()
            assertEquals("the Settings form exposes token and country inputs", 2, fields.size)
            clickNode(fields[0])
            awaitTrue("the Settings input opens the native keyboard", ::inputMethodShown)
            assertTrue("the token field retains its password semantics", awaitEditableFields()[0].isPassword)
            assertTrue("native Tab advances focus to the country field", uiDevice.pressKeyCode(61))
            awaitTrue("the country field receives focus") { awaitEditableFields().getOrNull(1)?.isFocused == true }
            assertTrue("native Back first dismisses the keyboard", uiDevice.pressBack())
            awaitTrue("the IME is dismissed") { !inputMethodShown() }
            assertEquals("Back leaves the Settings form in place", 2, awaitEditableFields().size)
            clickText("Cancel")

            clickDisclosure("Google account")
            var disclosure = find(instrumentation.uiAutomation.rootInActiveWindow) {
                it.text?.toString() == "Google account"
            } ?: throw AssertionError("The expanded Account actions disclosure is unavailable")
            while ((disclosure.stateDescription == null || (!disclosure.isClickable && disclosure.actionList.none {
                    it.id == AccessibilityNodeInfo.ACTION_CLICK
                })) && disclosure.parent != null
            ) disclosure = disclosure.parent
            val disclosureBounds = Rect().also(disclosure::getBoundsInScreen)
            assertTrue("Settings recovery disclosure meets the 56dp target; height=${disclosureBounds.height() / metrics.density}dp, bounds=$disclosureBounds, clickable=${disclosure.isClickable}, class=${disclosure.className}, text=${disclosure.text}, state=${disclosure.stateDescription}, density=${metrics.density}",
                disclosureBounds.height() / metrics.density >= 56f)
            clickText("Sign out of Google")
            awaitVisibleNode { it.text?.toString() == "Sign out of Google?" }
            assertTrue("native Back cancels the confirmation", uiDevice.pressBack())
            awaitTrue("the confirmation closes without signing out") {
                find(instrumentation.uiAutomation.rootInActiveWindow) {
                    it.text?.toString() == "Sign out of Google?"
                } == null
            }
            assertEquals("the synthetic owner session remains signed in",
                "Fake Owner", awaitVisibleNode { it.text?.toString() == "Fake Owner" }.text?.toString())
        }
    }

    @Test fun productionShellPhoneLandscapeKeepsRoutesReachable() {
        clearFixturePreferences()

        launchShell().use { scene ->
            scene.onActivity { it.requestedOrientation = android.content.pm.ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE }
            awaitTrue("the emulator is in phone landscape") {
                instrumentation.targetContext.resources.configuration.orientation == android.content.res.Configuration.ORIENTATION_LANDSCAPE
            }
            assertEquals("the landscape run uses the default scale", 1.0f,
                instrumentation.targetContext.resources.configuration.fontScale, 0.01f)
            clickText("Continue with Google")
            awaitNode { it.text?.toString() == "Connect ThinQ in Settings to read and control your AC." }
            awaitShellSettled(scene)
            clickTab("Home")
            awaitVisibleNode { it.text?.toString() == "Airchive" }
            assertTrue("Home remains the selected landscape route", selectedDestination("Home"))
            awaitVisibleNode { it.text?.toString()?.startsWith("Current power consumption as of ") == true }
            saveCapture("synthetic-home-phone-landscape-scale-1.0")

            val navigationTop = if (usesLandscapeRail()) instrumentation.targetContext.resources.displayMetrics.heightPixels
                else bottomNavigationTopPx("Home")
            if (usesLandscapeRail()) {
                val homeBounds = Rect().also(awaitVisibleNode { it.text?.toString() == "Home" }::getBoundsInScreen)
                val headingBounds = Rect().also(awaitVisibleNode { it.text?.toString() == "Airchive" }::getBoundsInScreen)
                assertTrue("landscape navigation sits beside the content", homeBounds.right < headingBounds.left)
            }
            val temperatureUnit = awaitVisibleNode { it.text?.toString() == "°C" }
            val temperatureUnitBounds = Rect().also(temperatureUnit::getBoundsInScreen)
            assertTrue("landscape scrolling exposes the thermostat unit", temperatureUnitBounds.bottom <= navigationTop)
            saveCapture("synthetic-thermostat-phone-landscape-scale-1.0")
            val energyUnit = awaitVisibleNode { it.text?.toString() == "kWh" }
            val energyUnitBounds = Rect().also(energyUnit::getBoundsInScreen)
            assertTrue("landscape scrolling exposes the consumption unit", energyUnitBounds.bottom <= navigationTop)
            saveCapture("synthetic-home-readings-phone-landscape-scale-1.0")

            val reload = awaitNode { node -> node.actionList.any { it.label?.toString() == "Reload history" } }
            val beforeReads = AtomicInteger()
            scene.onActivity { beforeReads.set(it.shellFixture!!.counters.telemetryReads.get()) }
            assertTrue(reload.performAction(reload.actionList.first { it.label?.toString() == "Reload history" }.id))
            awaitTrue("accessible history reload remains usable in landscape") {
                var reads = 0
                scene.onActivity { reads = it.shellFixture!!.counters.telemetryReads.get() }
                reads > beforeReads.get()
            }
            val setup = awaitVisibleNode { it.text?.toString() == "Open Settings" }
            val setupBounds = Rect().also(setup::getBoundsInScreen)
            assertTrue("the actual Home setup action remains reachable above navigation", setupBounds.bottom <= navigationTop)
            saveCapture("synthetic-home-controls-phone-landscape-scale-1.0")

            for (route in listOf("Device", "Energy", "History", "Settings")) {
                clickTab(route)
                awaitTrue("$route remains selected in phone landscape") { selectedDestination(route) }
                saveCapture("synthetic-$route-phone-landscape-scale-1.0")
            }
        }
    }

    @Test fun productionLandscapeAtLargeTextKeepsDestinationsReachable() {
        clearFixturePreferences()
        launchShell().use { scene ->
            scene.onActivity { it.requestedOrientation = android.content.pm.ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE }
            awaitTrue("landscape is active at large text") {
                val config = instrumentation.targetContext.resources.configuration
                config.orientation == android.content.res.Configuration.ORIENTATION_LANDSCAPE && config.fontScale >= 1.9f
            }
            clickText("Continue with Google")
            awaitShellSettled(scene)
            for (route in listOf("Home", "Device", "Energy", "History", "Settings")) {
                clickTab(route)
                awaitTrue("$route remains reachable at large text") { selectedDestination(route) }
            }
            saveCapture("synthetic-settings-landscape-scale-2.0")
        }
    }

    @Test fun groupedSettingsShowLocalStatusAndKeepAccountActionsCollapsed() {
        val scale = java.lang.String.format(java.util.Locale.ROOT, "%.1f",
            instrumentation.targetContext.resources.configuration.fontScale)
        val uiMode = instrumentation.targetContext.getSystemService(UiModeManager::class.java)
        val previousNightMode = uiMode.nightMode
        fun setTheme(mode: String) {
            ParcelFileDescriptor.AutoCloseInputStream(
                instrumentation.uiAutomation.executeShellCommand("cmd uimode night $mode"),
            ).bufferedReader().use { it.readText() }
        }
        try {
            for (dark in listOf(false, true)) {
                setTheme(if (dark) "yes" else "no")
                clearFixturePreferences()
                launchShell().use { scene ->
                    clickText("Continue with Google")
                    awaitNode { it.text?.toString() == "Connect ThinQ in Settings to read and control your AC." }
                    awaitShellSettled(scene)
                    clickTab("Settings")
                    val before = AtomicReference<List<Int>>()
                    scene.onActivity { before.set(operationSnapshot(it.shellFixture!!)) }
                    for (detail in cloud.airchive.shared.InterfaceDetail.entries) {
                        selectShellDetail(scene, detail)
                        assertDisclosureState("Google account", "Collapsed")
                        assertTextAbsent("Switch Google account")
                        assertTextAbsent("Sign out of Google")
                        awaitNode { it.text?.toString() == "No ThinQ token saved" }
                        awaitNode { it.text?.toString() == "Set up ThinQ PAT" }
                        awaitNode { it.text?.toString() == "Connections" }
                        saveCapture("settings-grouped-${detail.storedValue}-${if (dark) "dark" else "light"}-scale-$scale")
                        scene.onActivity {
                            assertEquals("settings presentation performs no port operations", before.get(), operationSnapshot(it.shellFixture!!))
                        }
                    }
                }
            }
        } finally {
            clearFixturePreferences()
            setTheme(when (previousNightMode) {
                UiModeManager.MODE_NIGHT_YES -> "yes"
                UiModeManager.MODE_NIGHT_NO -> "no"
                else -> "auto"
            })
        }
    }

    @Test fun nestedSettingsDetailStaysReadableWithEveryRowOpen() {
        val scale = java.lang.String.format(java.util.Locale.ROOT, "%.1f",
            instrumentation.targetContext.resources.configuration.fontScale)
        clearFixturePreferences()
        try {
            launchShell().use { scene ->
                clickText("Continue with Google")
                awaitNode { it.text?.toString() == "Connect ThinQ in Settings to read and control your AC." }
                awaitShellSettled(scene)
                clickTab("Settings")
                selectShellDetail(scene, cloud.airchive.shared.InterfaceDetail.DETAILED)
                clickDisclosure("Google account")
                awaitVisibleNode { it.text?.toString() == "Sign out of Google" }
                assertEquals("a row title is not repeated as a heading inside its own detail", 1,
                    allNodes(instrumentation.uiAutomation.rootInActiveWindow).count { it.text?.toString() == "History access" })
                scrollToTop()
                val expected = listOf("LG client ID", "Project ID", "Last successful sync", "Collector health")
                val seen = mutableSetOf<String>()
                // Large text lengthens the page, so scroll until every row has been on screen.
                for (page in 1..10) {
                    saveCapture("settings-nested-detailed-scale-$scale-$page")
                    for (label in expected) {
                        if (find(instrumentation.uiAutomation.rootInActiveWindow) {
                                it.isVisibleToUser && it.text?.toString()?.contains(label) == true
                            } != null) seen += label
                    }
                    if (seen.size == expected.size) break
                    scrollForward()
                }
                assertEquals("every nested detail row is reachable by scrolling", expected.toSet(), seen)

                // The credential form lives beside, not inside, the AC connection row. The page is several
                // screens long, so return to its top by swiping rather than assume two swipes suffice.
                repeat(8) { scrollBackward() }
                clickText("Set up ThinQ PAT")
                fun verifyVisible() = find(instrumentation.uiAutomation.rootInActiveWindow) {
                    it.isVisibleToUser && it.text?.toString() == "Verify and save securely"
                } != null
                repeat(4) { if (!verifyVisible()) scrollForward() }
                awaitVisibleNode { it.text?.toString() == "Verify and save securely" }
                saveCapture("settings-nested-token-form-scale-$scale")
                clickText("Cancel")
                awaitTrue("Cancel closes the credential form") {
                    find(instrumentation.uiAutomation.rootInActiveWindow) {
                        it.text?.toString() == "Verify and save securely"
                    } == null
                }
            }
        } finally {
            clearFixturePreferences()
        }
    }

    @Test fun captureSyntheticProductionShellVisualMatrix() {
        val fontScale = instrumentation.targetContext.resources.configuration.fontScale
        assertTrue("run this matrix at the explicitly configured 1.0, 1.3, or maximum 2.0 text scale",
            listOf(1.0f, 1.3f, 2.0f).any { kotlin.math.abs(fontScale - it) < 0.01f })
        val scaleLabel = java.lang.String.format(java.util.Locale.ROOT, "%.1f", fontScale)
        val uiMode = instrumentation.targetContext.getSystemService(UiModeManager::class.java)
        val previousNightMode = uiMode.nightMode
        fun setSystemTheme(mode: String) {
            ParcelFileDescriptor.AutoCloseInputStream(
                instrumentation.uiAutomation.executeShellCommand("cmd uimode night $mode"),
            ).bufferedReader().use { it.readText() }
        }

        try {
            for (dark in listOf(false, true)) {
                setSystemTheme(if (dark) "yes" else "no")
                clearFixturePreferences()
                launchShell().use { scene ->
                    clickText("Continue with Google")
                    awaitNode { it.text?.toString() == "Connect ThinQ in Settings to read and control your AC." }
                    clickTab("Settings")
                    awaitShellSettled(scene)
                    clickText("Set up ThinQ PAT")
                    awaitVisibleNode { it.text?.toString() == "Verify and save securely" }
                    setTextByHint("ThinQ Personal Access Token", SharedInstrumentShellFixture.SYNTHETIC_PAT_MARKER)
                    clickText("Verify and save securely")
                    awaitNode { it.text?.toString()?.startsWith("The configured AC and profile were verified;") == true }

                    for (detail in listOf("Simple", "Detailed")) {
                        clickTab("Settings")
                        if (detail == "Detailed") {
                            clickText("Set up ThinQ PAT")
                            awaitVisibleNode { it.text?.toString() == "Verify and save securely" }
                            setTextByHint("ThinQ Personal Access Token", SharedInstrumentShellFixture.SYNTHETIC_PAT_MARKER)
                            clickText("Verify and save securely")
                            awaitNode { it.text?.toString()?.startsWith("The configured AC and profile were verified;") == true }
                        }
                        if (detail == "Detailed") clickText("Detailed")
                        val description = if (detail == "Detailed") {
                            "Extra explanations open automatically across the app."
                        } else {
                            "Extra explanations stay collapsed. Tap any section to see more."
                        }
                        awaitVisibleNode { it.text?.toString() == description }
                        awaitVisibleNode { it.text?.toString() == "Interface detail" }
                        instrumentation.uiAutomation.waitForIdle(200, 5_000)

                        for ((route, anchor) in listOf(
                            "Home" to "Airchive",
                            "Device" to "Set temperature",
                            "Energy" to "Net daily power consumed",
                            "History" to "History",
                        )) {
                            clickTab(route)
                            try {
                                awaitNode { it.text?.toString() == anchor }
                            } catch (failure: AssertionError) {
                                throw AssertionError("$route content anchor '$anchor' is unavailable", failure)
                            }
                            try {
                                awaitTrue("$route exposes its selected native destination") {
                                    selectedDestination(route)
                                }
                            } catch (failure: AssertionError) {
                                throw AssertionError(
                                    "$route selection semantics: ${destinationSemantics(route)}", failure,
                                )
                            }
                            instrumentation.uiAutomation.waitForIdle(200, 5_000)
                            saveCapture("synthetic-shell-${detail.lowercase()}-$route-${if (dark) "dark" else "light"}-scale-$scaleLabel")
                            if (route == "Home" && fontScale > 1.2f) {
                                scrollForward()
                                awaitVisibleNode { it.text?.toString() == "Today's net consumption" }
                                saveCapture(
                                    "synthetic-shell-${detail.lowercase()}-Home-energy-labels-" +
                                        "${if (dark) "dark" else "light"}-scale-$scaleLabel",
                                )
                            }
                        }

                        val fixture = AtomicReference<SharedInstrumentShellFixture>()
                        scene.onActivity { fixture.set(it.shellFixture!!) }
                        runBlocking { fixture.get().thinQContext.bindings().removeCredential() }
                        clickTab("Settings")
                        awaitVisibleNode { it.text?.toString() == "Interface detail" }
                        assertFalse("the credential-free Settings capture exposes no raw synthetic PAT",
                            accessibilitySnapshot(instrumentation.uiAutomation.rootInActiveWindow)
                                .contains(SharedInstrumentShellFixture.SYNTHETIC_PAT_MARKER))
                        instrumentation.uiAutomation.waitForIdle(200, 5_000)
                        saveCapture("synthetic-shell-${detail.lowercase()}-Settings-${if (dark) "dark" else "light"}-scale-$scaleLabel")
                    }
                }
                clearFixturePreferences()
            }
        } finally {
            clearFixturePreferences()
            setSystemTheme(when (previousNightMode) {
                UiModeManager.MODE_NIGHT_YES -> "yes"
                UiModeManager.MODE_NIGHT_NO -> "no"
                else -> "auto"
            })
        }
    }

    @Test fun captureSyntheticProductionShellSimpleRoutesAtDefaultTextScale() {
        val scale = instrumentation.targetContext.resources.configuration.fontScale
        assertEquals("this evidence batch is explicitly the default text scale", 1.0f, scale, 0.01f)
        val uiMode = instrumentation.targetContext.getSystemService(UiModeManager::class.java)
        val previousNightMode = uiMode.nightMode
        fun setSystemTheme(mode: String) {
            ParcelFileDescriptor.AutoCloseInputStream(
                instrumentation.uiAutomation.executeShellCommand("cmd uimode night $mode"),
            ).bufferedReader().use { it.readText() }
        }
        clearFixturePreferences()
        setSystemTheme("no")
        try {
            launchShell().use { scene ->
                clickText("Continue with Google")
                awaitNode { it.text?.toString() == "Connect ThinQ in Settings to read and control your AC." }
                awaitShellSettled(scene)
                clickTab("Settings")
                clickText("Set up ThinQ PAT")
                awaitVisibleNode { it.text?.toString() == "Verify and save securely" }
                setTextByHint("ThinQ Personal Access Token", SharedInstrumentShellFixture.SYNTHETIC_PAT_MARKER)
                clickText("Verify and save securely")
                awaitNode { it.text?.toString()?.startsWith("The configured AC and profile were verified;") == true }
                clickTab("Home")
                awaitNode { it.text?.toString() == "Device read 0 sec ago" }

                for ((route, label) in listOf(
                    "Home" to "Airchive",
                    "Device" to "Device details",
                    "Energy" to "Net daily power consumed",
                    "History" to "Recorded hours (newest first)",
                )) {
                    clickTab(route)
                    awaitVisibleNode { it.text?.toString() == label }
                    instrumentation.waitForIdleSync()
                    instrumentation.uiAutomation.waitForIdle(200, 5_000)
                    saveCapture("synthetic-shell-simple-$route-light-scale-1.0")
                }

                // Keep the Settings capture free of even a masked synthetic
                // credential suffix while documenting its disconnected
                // recovery state in the production shell.
                val fixture = AtomicReference<SharedInstrumentShellFixture>()
                scene.onActivity { fixture.set(it.shellFixture!!) }
                runBlocking { fixture.get().thinQContext.bindings().removeCredential() }
                clickTab("Settings")
                awaitVisibleNode { it.text?.toString() == "Interface detail" }
                saveCapture("synthetic-shell-simple-Settings-light-scale-1.0")

            }
        } finally {
            clearFixturePreferences()
            setSystemTheme(when (previousNightMode) {
                UiModeManager.MODE_NIGHT_YES -> "yes"
                UiModeManager.MODE_NIGHT_NO -> "no"
                else -> "auto"
            })
        }
    }

    private fun saveCapture(name: String) {
        val bitmap = instrumentation.uiAutomation.takeScreenshot() ?: throw AssertionError("Native capture failed")
        File(instrumentation.targetContext.getExternalFilesDir(null), "$name.png").outputStream().use {
            assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it))
        }
        bitmap.recycle()
    }

    /** Inject without accessibility-idle waits so one-second interaction windows remain real. */
    private fun injectTap(bounds: Rect) {
        val down = SystemClock.uptimeMillis()
        listOf(android.view.MotionEvent.ACTION_DOWN, android.view.MotionEvent.ACTION_UP).forEach { action ->
            val event = android.view.MotionEvent.obtain(down, SystemClock.uptimeMillis(), action,
                bounds.centerX().toFloat(), bounds.centerY().toFloat(), 0)
            event.source = android.view.InputDevice.SOURCE_TOUCHSCREEN
            assertTrue(instrumentation.uiAutomation.injectInputEvent(event, true))
            event.recycle()
        }
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

    private fun buttonNode(label: String): AccessibilityNodeInfo {
        var node = awaitVisibleNode { it.text?.toString() == label }
        while (!node.isClickable && node.parent != null) node = node.parent
        return node
    }

    /** Navigation labels are visually tappable even when Compose exposes their merged click action inconsistently. */
    private fun clickTab(label: String) {
        val screenHeight = instrumentation.targetContext.resources.displayMetrics.heightPixels
        val metrics = instrumentation.targetContext.resources.displayMetrics
        val rail = usesLandscapeRail()
        val deadline = SystemClock.uptimeMillis() + 10_000
        while (SystemClock.uptimeMillis() < deadline) {
            val candidates = allNodes(instrumentation.uiAutomation.rootInActiveWindow)
                .filter { it.text?.toString() == label && it.isVisibleToUser }
            val tab = candidates.filter { candidate ->
                val bounds = Rect().also(candidate::getBoundsInScreen)
                if (rail) bounds.centerX() < metrics.widthPixels * .22f else bounds.top > screenHeight * .6f
            }.maxByOrNull { candidate ->
                val bounds = Rect()
                candidate.getBoundsInScreen(bounds)
                bounds.top
            }
            if (tab != null) {
                val bounds = Rect()
                tab.getBoundsInScreen(bounds)
                if (rail || bounds.top > screenHeight * .6f) {
                    tapNodeCenter(tab)
                    return
                }
            }
            if (rail) {
                val x = (metrics.density * 65).toInt()
                val up = label != "Home"
                uiDevice.swipe(x, (screenHeight * if (up) .8 else .2).toInt(),
                    x, (screenHeight * if (up) .2 else .8).toInt(), 10)
            }
            instrumentation.waitForIdleSync()
        }
        throw AssertionError("Could not locate the navigation destination '$label'")
    }

    private fun selectedDestination(label: String): Boolean {
        val screenHeight = instrumentation.targetContext.resources.displayMetrics.heightPixels
        val metrics = instrumentation.targetContext.resources.displayMetrics
        val rail = usesLandscapeRail()
        val nodes = allNodes(instrumentation.uiAutomation.rootInActiveWindow)
        val node = nodes
            .filter { candidate ->
                if (candidate.text?.toString() != label || !candidate.isVisibleToUser) return@filter false
                val bounds = Rect()
                candidate.getBoundsInScreen(bounds)
                if (rail) bounds.centerX() < metrics.widthPixels * .22f else bounds.top > screenHeight * .6f
            }
            .maxByOrNull { candidate ->
                val bounds = Rect()
                candidate.getBoundsInScreen(bounds)
                bounds.top
            } ?: return false
        val labelBounds = Rect().also(node::getBoundsInScreen)
        return nodes.any { candidate ->
            val selected = candidate.isSelected ||
                candidate.stateDescription?.toString()?.equals("Selected", ignoreCase = true) == true
            if (!selected) return@any false
            val candidateBounds = Rect().also(candidate::getBoundsInScreen)
            Rect.intersects(labelBounds, candidateBounds)
        }
    }

    private fun usesLandscapeRail(): Boolean {
        val config = instrumentation.targetContext.resources.configuration
        return config.screenWidthDp >= 600 && config.screenWidthDp > config.screenHeightDp
    }

    private fun destinationSemantics(label: String): String {
        val screenHeight = instrumentation.targetContext.resources.displayMetrics.heightPixels
        val selectedNodes = allNodes(instrumentation.uiAutomation.rootInActiveWindow).filter { candidate ->
            if (!candidate.isVisibleToUser || (!candidate.isSelected &&
                candidate.stateDescription?.toString()?.equals("Selected", ignoreCase = true) != true)
            ) return@filter false
            val bounds = Rect()
            candidate.getBoundsInScreen(bounds)
            bounds.top > screenHeight * .6f
        }
        val selected = selectedNodes.joinToString("; ") { candidate ->
            val bounds = Rect().also(candidate::getBoundsInScreen)
            "${candidate.className} text=${candidate.text} state=${candidate.stateDescription} bounds=$bounds"
        }
        return "expected $label; selected nodes: $selected"
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

    private fun signInAndConnectSyntheticThinQ(scene: ActivityScenario<SharedInstrumentPreviewActivity>) {
        clickText("Continue with Google")
        awaitNode { it.text?.toString() == "Connect ThinQ in Settings to read and control your AC." }
        clickTab("Settings")
        awaitShellSettled(scene)
        clickText("Set up ThinQ PAT")
        awaitVisibleNode { it.text?.toString() == "Verify and save securely" }
        setTextByHint("ThinQ Personal Access Token", SharedInstrumentShellFixture.SYNTHETIC_PAT_MARKER)
        clickText("Verify and save securely")
        awaitVisibleNode { it.text?.toString()?.startsWith("The configured AC and profile were verified;") == true }
    }

    private fun inputMethodShown(): Boolean {
        val output = ParcelFileDescriptor.AutoCloseInputStream(
            instrumentation.uiAutomation.executeShellCommand("dumpsys input_method"),
        ).bufferedReader().use { it.readText() }
        return Regex("mInputShown=(true|false)").find(output)?.groupValues?.get(1) == "true"
    }

    private fun systemBarDimensionPx(name: String): Int {
        val context = instrumentation.targetContext
        val resource = context.resources.getIdentifier(name, "dimen", "android")
        return if (resource == 0) 0 else context.resources.getDimensionPixelSize(resource)
    }

    private fun bottomNavigationTopPx(label: String): Int {
        val metrics = instrumentation.targetContext.resources.displayMetrics
        var node = awaitVisibleNode { it.text?.toString() == label }
        while (node.parent != null) {
            node = node.parent
            val bounds = Rect().also(node::getBoundsInScreen)
            if (bounds.width() >= metrics.widthPixels * 0.9f &&
                bounds.top >= metrics.heightPixels * 0.55f &&
                bounds.height() >= metrics.density * 64f &&
                bounds.height() <= metrics.heightPixels * 0.45f
            ) return bounds.top
        }
        throw AssertionError("Could not measure the fixed bottom navigation container for $label")
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
        // A toggle can reflow a long page; without dropping the client cache the pre-toggle state is
        // served for seconds, which made the fallback taps below toggle an already-toggled section back.
        instrumentation.uiAutomation.clearCache()
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
        val previous = disclosureStateNow(title) ?: header.stateDescription?.toString()
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
        if (disclosureStateNow(title) != expected) {
            awaitVisibleNode { it.text?.toString() == title }
        }
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
            "Period summary", "Net daily power consumed", "Sampled five-minute interval energy", "Retry history loading",
            "History access", "History and storage", "AC connection", "Google account",
            "Sync and collector health", "Sample status guide", "Recorded hours (newest first)",
            "No cached observations yet", "Set up ThinQ PAT", "Set temperature", "Room temperature",
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
        // The automation client can serve a pre-collapse node tree for a while, so poll with a cleared
        // cache until the text is gone instead of trusting a single lookup.
        val deadline = SystemClock.uptimeMillis() + 5_000
        var found: AccessibilityNodeInfo?
        do {
            instrumentation.waitForIdleSync()
            instrumentation.uiAutomation.clearCache()
            found = find(instrumentation.uiAutomation.rootInActiveWindow) { it.text?.toString()?.contains(text) == true }
            if (found == null) return
            SystemClock.sleep(50)
        } while (SystemClock.uptimeMillis() < deadline)
        assertNull("$text should not be visible while its disclosure is collapsed", found)
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
        // The form may already be scrolled past its fields (narrow windows are taller), so look where
        // we are, then return to the top before scanning forward, as awaitVisibleNode does.
        instrumentation.waitForIdleSync()
        editableNodes(instrumentation.uiAutomation.rootInActiveWindow).takeIf { it.size >= 2 }?.let { return it }
        repeat(2) { scrollBackward() }
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
        val labelIndex = lines.indexOf("Net daily power consumed")
        assertTrue("the summary label is in the accessibility tree", labelIndex >= 0 && labelIndex + 2 < lines.size)
        return listOf(
            lines[labelIndex],
            lines[labelIndex + 1],
            lines[labelIndex + 2],
        )
    }

    private fun scrollForward() {
        scrollContent(forward = true)
    }

    private fun scrollBackward() {
        scrollContent(forward = false)
    }

    private fun scrollContent(forward: Boolean) {
        val scrollable = allNodes(instrumentation.uiAutomation.rootInActiveWindow)
            .filter { it.isScrollable }
            .maxByOrNull { Rect().also(it::getBoundsInScreen).width() }
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
            instrumentation.uiAutomation.clearCache()
            find(instrumentation.uiAutomation.rootInActiveWindow, predicate)?.let { return it }
            SystemClock.sleep(50)
        }
        throw AssertionError("Expected accessible instrument content was not visible")
    }

    private fun awaitAccessibilityNode(
        description: String,
        predicate: (AccessibilityNodeInfo) -> Boolean,
    ): AccessibilityNodeInfo {
        val deadline = SystemClock.uptimeMillis() + 10_000
        while (SystemClock.uptimeMillis() < deadline) {
            find(instrumentation.uiAutomation.rootInActiveWindow, predicate)
                ?.takeIf { it.isVisibleToUser }
                ?.let { return it }
            SystemClock.sleep(50)
        }
        throw AssertionError("Expected visible accessibility node: $description")
    }

    private fun awaitModeSlider(): AccessibilityNodeInfo {
        val predicate: (AccessibilityNodeInfo) -> Boolean = {
            it.contentDescription?.toString()?.startsWith("Mode selection.") == true
        }
        val deadline = SystemClock.uptimeMillis() + 15_000
        while (SystemClock.uptimeMillis() < deadline) {
            instrumentation.waitForIdleSync()
            instrumentation.uiAutomation.clearCache()
            find(instrumentation.uiAutomation.rootInActiveWindow, predicate)
                ?.takeIf { it.isVisibleToUser }
                ?.let { return it }
            assertTrue(
                "the Home content scrolls toward the mode slider",
                uiDevice.swipe(
                    uiDevice.displayWidth / 2,
                    uiDevice.displayHeight * 2 / 3,
                    uiDevice.displayWidth / 2,
                    uiDevice.displayHeight / 4,
                    20,
                ),
            )
            SystemClock.sleep(100)
        }
        throw AssertionError("Expected visible accessibility node: Mode selection slider")
    }

    private fun awaitVisibleNode(predicate: (AccessibilityNodeInfo) -> Boolean): AccessibilityNodeInfo {
        // First cover the case where the requested control is above the current
        // viewport (for example the disclosure headers above a virtualized list).
        repeat(2) { scrollBackward() }
        repeat(12) {
            instrumentation.waitForIdleSync()
            instrumentation.uiAutomation.clearCache()
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
            instrumentation.uiAutomation.clearCache()
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

    private fun expandFirstHistoryHour() {
        // Hour rows describe themselves ("Hour 17:00 to 18:00. 0.093 kilowatt-hours. ...") and expose
        // Expanded/Collapsed as state on their clickable parent node, as disclosures do. Open the first
        // collapsed one.
        val hour = awaitVisibleNode {
            it.contentDescription?.toString()?.startsWith("Hour ") == true &&
                (it.stateDescription ?: it.parent?.stateDescription)?.toString() == "Collapsed"
        }
        clickNode(hour)
        // The expanded readings join a lazily composed list. The automation client caches node child lists
        // and can serve the pre-expansion tree for several seconds, so clear its cache while waiting.
        awaitTrue("the opened hour exposes its five-minute readings") {
            instrumentation.uiAutomation.clearCache()
            find(instrumentation.uiAutomation.rootInActiveWindow) {
                it.contentDescription?.toString()?.startsWith("Sample at ") == true
            } != null
        }
    }

    private fun awaitLoadButtonEnabled() {
        awaitTrue("the explicit Energy load action finishes") {
            find(instrumentation.uiAutomation.rootInActiveWindow) {
                it.text?.toString() == "Retry history loading"
            }?.isEnabled == true
        }
    }

    private fun currentDeviceReadAt(fixture: SharedInstrumentShellFixture): Long {
        val current = fixture.thinQContext.liveStateController.state.value as?
            cloud.airchive.shared.data.thinq.ThinQLiveRefreshState.Current
            ?: throw AssertionError("the synthetic device-read state is not current")
        return current.state.observedAtEpochMillis
    }

    private fun prepareConfiguredShell(scene: ActivityScenario<SharedInstrumentPreviewActivity>) {
        clickText("Continue with Google")
        awaitNode { it.text?.toString() == "Connect ThinQ in Settings to read and control your AC." }
        awaitShellSettled(scene)
        clickTab("Settings")
        clickText("Set up ThinQ PAT")
        awaitVisibleNode { it.text?.toString() == "Verify and save securely" }
        setTextByHint("ThinQ Personal Access Token", SharedInstrumentShellFixture.SYNTHETIC_PAT_MARKER)
        clickText("Verify and save securely")
        awaitNode { it.text?.toString()?.startsWith("The configured AC and profile were verified;") == true }
        clickTab("Home")
        awaitNode { it.text?.toString() == "25" }
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

    private fun awaitComposedFrames(frames: Int = 2) {
        val rendered = CountDownLatch(1)
        instrumentation.runOnMainSync {
            var remaining = frames
            val callback = object : Choreographer.FrameCallback {
                override fun doFrame(frameTimeNanos: Long) {
                    remaining -= 1
                    if (remaining <= 0) rendered.countDown() else Choreographer.getInstance().postFrameCallback(this)
                }
            }
            Choreographer.getInstance().postFrameCallback(callback)
        }
        assertTrue("the UI renders $frames frames", rendered.await(5, TimeUnit.SECONDS))
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
        // The preference is a StateFlow read through collectAsState, so two selections that land before
        // composition runs are conflated into one change and disclosure defaults never reset. Let
        // composition adopt this selection before the helper returns, on every route.
        awaitComposedFrames()
        // Selector copy exists only on Settings. Prefer its rendered label when
        // it is on the active route; otherwise settle the current route without
        // searching for a Settings-only string.
        val selectorOnCurrentRoute = find(instrumentation.uiAutomation.rootInActiveWindow) {
            it.text?.toString() == "Interface detail" && it.isVisibleToUser
        } != null
        if (selectorOnCurrentRoute) {
            val selectedCopy = if (detail == cloud.airchive.shared.InterfaceDetail.DETAILED) {
                "Extra explanations open automatically across the app."
            } else {
                "Extra explanations stay collapsed. Tap any section to see more."
            }
            awaitVisibleNode { it.text?.toString() == selectedCopy }
        } else {
            instrumentation.waitForIdleSync()
            instrumentation.uiAutomation.waitForIdle(100, 2_000)
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
