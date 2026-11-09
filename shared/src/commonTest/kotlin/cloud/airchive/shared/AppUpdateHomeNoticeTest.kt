package cloud.airchive.shared

import cloud.airchive.shared.data.update.AppUpdateState
import cloud.airchive.shared.data.update.UpdateApk
import cloud.airchive.shared.data.update.UpdateManifest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class AppUpdateHomeNoticeTest {
    private val manifest = UpdateManifest(
        schemaVersion = 1,
        channel = "stable",
        packageName = "cloud.airchive.personal",
        versionCode = 42L,
        versionName = "1.4.2",
        commit = "a".repeat(40),
        publishedAt = "2026-11-09T00:00:00Z",
        releaseNotes = "A fix",
        apk = UpdateApk(
            url = "https://github.com/example/app.apk",
            sha256 = "b".repeat(64),
            size = 1_000L,
            signingCertSha256 = "c".repeat(64),
        ),
    )
    private val available = AppUpdateState.Available(manifest)

    @Test
    fun availableVersionShowsOnlyWhenNoWorkIsPending() {
        assertEquals(
            manifest,
            appUpdateNoticeManifest(
                state = available,
                dismissedVersionCode = null,
                hasTemperatureDraft = false,
                hasPendingConfirmation = false,
                hasPendingCommand = false,
            ),
        )
    }

    @Test
    fun temperatureDraftConfirmationAndCommandEachDeferTheNotice() {
        val pendingConditions = listOf(
            Triple(true, false, false),
            Triple(false, true, false),
            Triple(false, false, true),
        )

        pendingConditions.forEach { (temperatureDraft, confirmation, command) ->
            assertNull(
                appUpdateNoticeManifest(
                    state = available,
                    dismissedVersionCode = null,
                    hasTemperatureDraft = temperatureDraft,
                    hasPendingConfirmation = confirmation,
                    hasPendingCommand = command,
                ),
            )
        }
    }

    @Test
    fun dismissingAnOfferSuppressesOnlyThatVersion() {
        assertNull(appUpdateNoticeManifest(available, 42L, false, false, false))
        assertEquals(manifest, appUpdateNoticeManifest(available, 41L, false, false, false))
        assertEquals(manifest, appUpdateNoticeManifest(available, 43L, false, false, false))
    }

    @Test
    fun noticeIsNotShownForAnyOtherState() {
        assertNull(appUpdateNoticeManifest(AppUpdateState.Idle, null, false, false, false))
        assertNull(appUpdateNoticeManifest(AppUpdateState.Checking, null, false, false, false))
        assertNull(appUpdateNoticeManifest(AppUpdateState.UpToDate, null, false, false, false))
    }
}
