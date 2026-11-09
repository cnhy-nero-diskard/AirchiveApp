package cloud.airchive.shared.data.update

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class AppUpdateStateMachineTest {
    private val pin = "c".repeat(64)
    private val manifest = UpdateManifest(
        schemaVersion = 1,
        channel = "stable",
        packageName = "cloud.airchive.personal",
        versionCode = 9,
        versionName = "0.3.0",
        commit = "a".repeat(40),
        publishedAt = "2026-11-07T00:00:00Z",
        releaseNotes = "Fixes and improvements",
        apk = UpdateApk(
            url = "https://github.com/example/app.apk",
            sha256 = "b".repeat(64),
            size = 1_000,
            signingCertSha256 = pin,
        ),
    )

    @Test
    fun newerMatchingReleaseIsOfferedAndSameOrLowerCodeIsUpToDate() {
        val machine = machine()

        assertIs<UpdateOfferDecision.Available>(machine.offerDecision(manifest, installedVersionCode = 8))
        assertEquals(UpdateOfferDecision.UpToDate, machine.offerDecision(manifest, installedVersionCode = 9))
        assertEquals(UpdateOfferDecision.UpToDate, machine.offerDecision(manifest, installedVersionCode = 10))
        assertEquals(AppUpdateState.Available(manifest), machine.dispatch(AppUpdateEvent.OfferResolved(manifest, 8)))
        assertEquals(AppUpdateState.UpToDate, machine.dispatch(AppUpdateEvent.OfferResolved(manifest, 9)))
    }

    @Test
    fun mismatchedPackageOrSignerIsUnavailableAndCannotStartDownload() {
        val machine = machine()
        val wrongPackage = manifest.copy(packageName = "cloud.airchive.other")
        val wrongSigner = manifest.copy(apk = manifest.apk.copy(signingCertSha256 = "d".repeat(64)))

        assertEquals(
            UpdateOfferDecision.Unavailable(UpdateUnavailableCause.PACKAGE_MISMATCH),
            machine.offerDecision(wrongPackage, installedVersionCode = 8),
        )
        assertEquals(
            UpdateOfferDecision.Unavailable(UpdateUnavailableCause.SIGNER_MISMATCH),
            machine.offerDecision(wrongSigner, installedVersionCode = 8),
        )
        assertEquals(
            AppUpdateState.Unavailable(UpdateUnavailableCause.SIGNER_MISMATCH),
            machine.dispatch(AppUpdateEvent.OfferResolved(wrongSigner, 8)),
        )
        assertEquals(AppUpdateState.Unavailable(UpdateUnavailableCause.SIGNER_MISMATCH), machine.dispatch(AppUpdateEvent.DownloadStarted))
    }

    @Test
    fun checkAndDownloadTransitionsIncludeProgressAndCancelReturnsToOffer() {
        val machine = machine()

        assertEquals(AppUpdateState.Checking, machine.dispatch(AppUpdateEvent.CheckStarted))
        assertEquals(AppUpdateState.Available(manifest), machine.dispatch(AppUpdateEvent.OfferResolved(manifest, 8)))
        assertEquals(AppUpdateState.Downloading(manifest), machine.dispatch(AppUpdateEvent.DownloadStarted))
        assertEquals(
            AppUpdateState.Downloading(manifest, bytesReceived = 1_000),
            machine.dispatch(AppUpdateEvent.DownloadProgress(1_200)),
        )
        assertEquals(AppUpdateState.Available(manifest), machine.dispatch(AppUpdateEvent.DownloadCancelled))
        assertEquals(AppUpdateState.Downloading(manifest), machine.dispatch(AppUpdateEvent.DownloadStarted))
        assertEquals(AppUpdateState.Verifying(manifest), machine.dispatch(AppUpdateEvent.DownloadCompleted))
    }

    @Test
    fun verificationFailureKeepsItsReason() {
        val machine = machine(AppUpdateState.Verifying(manifest))

        assertEquals(
            AppUpdateState.Failed(UpdateFailureReason.HASH_MISMATCH),
            machine.dispatch(AppUpdateEvent.VerificationFailed(UpdateFailureReason.HASH_MISMATCH)),
        )
    }

    @Test
    fun installPermissionAndCancelledInstallReturnToReadyState() {
        val machine = machine(AppUpdateState.ReadyToInstall(manifest))

        assertEquals(AppUpdateState.NeedsInstallPermission(manifest), machine.dispatch(AppUpdateEvent.InstallPermissionMissing))
        assertEquals(AppUpdateState.ReadyToInstall(manifest), machine.dispatch(AppUpdateEvent.InstallPermissionGranted))
        assertEquals(AppUpdateState.Installing(manifest), machine.dispatch(AppUpdateEvent.InstallStarted))
        assertEquals(AppUpdateState.ReadyToInstall(manifest), machine.dispatch(AppUpdateEvent.InstallCancelled))
    }

    @Test
    fun checkFailureAndPostRestartOutcomesAreRepresented() {
        val machine = machine()

        assertEquals(
            AppUpdateState.Unavailable(UpdateUnavailableCause.OFFLINE),
            machine.dispatch(AppUpdateEvent.CheckFailed(UpdateUnavailableCause.OFFLINE)),
        )
        assertEquals(
            AppUpdateState.Updated("0.3.0"),
            machine.dispatch(AppUpdateEvent.PendingInstallResolved(9, 9, "0.3.0")),
        )
        assertEquals(
            AppUpdateState.NotCompleted("0.3.0"),
            machine.dispatch(AppUpdateEvent.PendingInstallResolved(8, 9, "0.3.0")),
        )
    }

    private fun machine(initial: AppUpdateState = AppUpdateState.Idle) =
        AppUpdateStateMachine("cloud.airchive.personal", pin, initial)
}
