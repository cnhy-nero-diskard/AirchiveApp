package cloud.airchive.shared.data.update

/** Reasons that make an update feed unavailable before an APK is offered. */
enum class UpdateUnavailableCause {
    OFFLINE,
    TIMEOUT,
    FEED_ERROR,
    UNKNOWN_SCHEMA,
    PACKAGE_MISMATCH,
    SIGNER_MISMATCH,
    RELEASE_BUILDS_ONLY,
}

/** Specific failures after the owner has started an update action. */
enum class UpdateFailureReason {
    STORAGE,
    DOWNLOAD,
    DOWNLOAD_TOO_LARGE,
    APK_SIZE_MISMATCH,
    APK_INVALID,
    HASH_MISMATCH,
    APK_PACKAGE_MISMATCH,
    APK_VERSION_MISMATCH,
    APK_SIGNER_MISMATCH,
    APK_HAS_MULTIPLE_SIGNERS,
    APK_DEBUGGABLE,
    INSTALL_ABORTED,
    INSTALL_CONFLICT,
    INSTALL_INCOMPATIBLE,
    INSTALL_STORAGE,
    INSTALL_BLOCKED,
    INSTALL_ERROR,
}

/** User-visible update progression shared by Android UI and platform adapters. */
sealed interface AppUpdateState {
    data object Idle : AppUpdateState
    data object Checking : AppUpdateState
    data object UpToDate : AppUpdateState
    data class Unavailable(val cause: UpdateUnavailableCause) : AppUpdateState
    data class Available(val manifest: UpdateManifest) : AppUpdateState
    data class Downloading(
        val manifest: UpdateManifest,
        val bytesReceived: Long = 0L,
        val totalBytes: Long = manifest.apk.size,
    ) : AppUpdateState
    data class Verifying(val manifest: UpdateManifest) : AppUpdateState
    data class Failed(val reason: UpdateFailureReason) : AppUpdateState
    data class ReadyToInstall(val manifest: UpdateManifest) : AppUpdateState
    data class NeedsInstallPermission(val manifest: UpdateManifest) : AppUpdateState
    data class Installing(val manifest: UpdateManifest) : AppUpdateState
    data class Updated(val versionName: String) : AppUpdateState
    data class NotCompleted(val versionName: String) : AppUpdateState
}

/** Inputs to the state reducer. Invalid events leave the state unchanged. */
sealed interface AppUpdateEvent {
    data object CheckStarted : AppUpdateEvent
    data class CheckFailed(val cause: UpdateUnavailableCause) : AppUpdateEvent
    data class OfferResolved(val manifest: UpdateManifest, val installedVersionCode: Long) : AppUpdateEvent
    data object DownloadStarted : AppUpdateEvent
    data class DownloadProgress(val bytesReceived: Long) : AppUpdateEvent
    data object DownloadCancelled : AppUpdateEvent
    data object DownloadCompleted : AppUpdateEvent
    data object VerificationSucceeded : AppUpdateEvent
    data class VerificationFailed(val reason: UpdateFailureReason) : AppUpdateEvent
    data class UpdateFailed(val reason: UpdateFailureReason) : AppUpdateEvent
    data object InstallPermissionMissing : AppUpdateEvent
    data object InstallPermissionGranted : AppUpdateEvent
    data object InstallStarted : AppUpdateEvent
    data object InstallCancelled : AppUpdateEvent
    data class InstallFailed(val reason: UpdateFailureReason) : AppUpdateEvent
    data class InstallCompleted(val versionName: String) : AppUpdateEvent
    data class PendingInstallResolved(val installedVersionCode: Long, val targetVersionCode: Long, val versionName: String) : AppUpdateEvent
}

/**
 * Applies the package and signer trust policy before an update becomes
 * downloadable, then reduces lifecycle events through the v1 update states.
 */
class AppUpdateStateMachine(
    private val expectedPackageName: String,
    pinnedSignerSha256: String,
    initialState: AppUpdateState = AppUpdateState.Idle,
) {
    private val pinnedSignerSha256 = pinnedSignerSha256.lowercase()

    var state: AppUpdateState = initialState
        private set

    init {
        require(expectedPackageName.isNotBlank()) { "Expected package name must not be blank." }
        require(SHA256_PATTERN.matches(this.pinnedSignerSha256)) { "Pinned signer must be a SHA-256 hex digest." }
    }

    fun offerDecision(manifest: UpdateManifest, installedVersionCode: Long): UpdateOfferDecision = when {
        manifest.packageName != expectedPackageName -> UpdateOfferDecision.Unavailable(UpdateUnavailableCause.PACKAGE_MISMATCH)
        !manifest.apk.signingCertSha256.equals(pinnedSignerSha256, ignoreCase = true) ->
            UpdateOfferDecision.Unavailable(UpdateUnavailableCause.SIGNER_MISMATCH)
        manifest.versionCode <= installedVersionCode -> UpdateOfferDecision.UpToDate
        else -> UpdateOfferDecision.Available(manifest)
    }

    fun dispatch(event: AppUpdateEvent): AppUpdateState {
        state = reduce(state, event)
        return state
    }

    private fun reduce(current: AppUpdateState, event: AppUpdateEvent): AppUpdateState = when (event) {
        AppUpdateEvent.CheckStarted -> AppUpdateState.Checking
        is AppUpdateEvent.CheckFailed -> AppUpdateState.Unavailable(event.cause)
        is AppUpdateEvent.OfferResolved -> when (val decision = offerDecision(event.manifest, event.installedVersionCode)) {
            is UpdateOfferDecision.Available -> AppUpdateState.Available(decision.manifest)
            UpdateOfferDecision.UpToDate -> AppUpdateState.UpToDate
            is UpdateOfferDecision.Unavailable -> AppUpdateState.Unavailable(decision.cause)
        }
        AppUpdateEvent.DownloadStarted -> when (current) {
            is AppUpdateState.Available -> AppUpdateState.Downloading(current.manifest)
            else -> current
        }
        is AppUpdateEvent.DownloadProgress -> when (current) {
            is AppUpdateState.Downloading -> current.copy(
                bytesReceived = event.bytesReceived.coerceIn(0L, current.totalBytes),
            )
            else -> current
        }
        AppUpdateEvent.DownloadCancelled -> when (current) {
            is AppUpdateState.Downloading -> AppUpdateState.Available(current.manifest)
            is AppUpdateState.Verifying -> AppUpdateState.Available(current.manifest)
            else -> current
        }
        AppUpdateEvent.DownloadCompleted -> when (current) {
            is AppUpdateState.Downloading -> AppUpdateState.Verifying(current.manifest)
            else -> current
        }
        AppUpdateEvent.VerificationSucceeded -> when (current) {
            is AppUpdateState.Verifying -> AppUpdateState.ReadyToInstall(current.manifest)
            else -> current
        }
        is AppUpdateEvent.VerificationFailed -> when (current) {
            is AppUpdateState.Verifying -> AppUpdateState.Failed(event.reason)
            else -> current
        }
        is AppUpdateEvent.UpdateFailed -> when (current) {
            is AppUpdateState.Downloading,
            is AppUpdateState.Verifying,
            is AppUpdateState.Installing,
            -> AppUpdateState.Failed(event.reason)
            else -> current
        }
        AppUpdateEvent.InstallPermissionMissing -> when (current) {
            is AppUpdateState.ReadyToInstall -> AppUpdateState.NeedsInstallPermission(current.manifest)
            is AppUpdateState.NeedsInstallPermission -> current
            else -> current
        }
        AppUpdateEvent.InstallPermissionGranted -> when (current) {
            is AppUpdateState.NeedsInstallPermission -> AppUpdateState.ReadyToInstall(current.manifest)
            is AppUpdateState.ReadyToInstall -> current
            else -> current
        }
        AppUpdateEvent.InstallStarted -> when (current) {
            is AppUpdateState.ReadyToInstall -> AppUpdateState.Installing(current.manifest)
            else -> current
        }
        AppUpdateEvent.InstallCancelled -> when (current) {
            is AppUpdateState.Installing -> AppUpdateState.ReadyToInstall(current.manifest)
            else -> current
        }
        is AppUpdateEvent.InstallFailed -> when (current) {
            is AppUpdateState.Installing -> AppUpdateState.Failed(event.reason)
            else -> current
        }
        is AppUpdateEvent.InstallCompleted -> when (current) {
            is AppUpdateState.Installing -> AppUpdateState.Updated(event.versionName)
            else -> current
        }
        is AppUpdateEvent.PendingInstallResolved -> when {
            event.installedVersionCode >= event.targetVersionCode -> AppUpdateState.Updated(event.versionName)
            else -> AppUpdateState.NotCompleted(event.versionName)
        }
    }
}

sealed interface UpdateOfferDecision {
    data class Available(val manifest: UpdateManifest) : UpdateOfferDecision
    data object UpToDate : UpdateOfferDecision
    data class Unavailable(val cause: UpdateUnavailableCause) : UpdateOfferDecision
}

private val SHA256_PATTERN = Regex("[0-9a-f]{64}")
