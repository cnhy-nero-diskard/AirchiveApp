package cloud.airchive.shared

import cloud.airchive.shared.contract.credentials.ThinQCredentialState
import cloud.airchive.shared.contract.owner.OwnerAccessState

/**
 * Settings row health. Each mapping is a pure projection of state the screen already holds, so the
 * mark never implies a check the app has not made: a saved token is OK, not "command authority proven".
 */
internal fun googleAccountStatus(signedIn: Boolean): InstrumentStatus =
    if (signedIn) InstrumentStatus.OK else InstrumentStatus.NEUTRAL

internal fun acConnectionStatus(
    ownerUid: String?,
    ownerContextReady: Boolean,
    credentialState: ThinQCredentialState,
): InstrumentStatus = when {
    ownerUid == null || !ownerContextReady -> InstrumentStatus.NEUTRAL
    credentialState.unlockFailed -> InstrumentStatus.ATTENTION
    credentialState.present -> InstrumentStatus.OK
    else -> InstrumentStatus.ATTENTION
}

internal fun historyAccessStatus(access: OwnerAccessState): InstrumentStatus = when (access) {
    is OwnerAccessState.Authorized -> InstrumentStatus.OK
    OwnerAccessState.SignedOut -> InstrumentStatus.NEUTRAL
    is OwnerAccessState.SignInFailed,
    is OwnerAccessState.AccessPending,
    is OwnerAccessState.AccessCheckFailed -> InstrumentStatus.ATTENTION
}

internal fun historyStorageStatus(hasCachedHistory: Boolean, hasSyncError: Boolean): InstrumentStatus = when {
    hasSyncError -> InstrumentStatus.ATTENTION
    hasCachedHistory -> InstrumentStatus.OK
    else -> InstrumentStatus.NEUTRAL
}

/** Collapsed History row text: what is stored and how recently it was confirmed, in one line. */
internal fun historyStorageSummary(
    hasCachedHistory: Boolean,
    observationCount: Int,
    lastSuccessfulAgeMillis: Long?,
): String {
    if (!hasCachedHistory) return "No history cached yet"
    val stored = if (observationCount == 1) "1 cached observation" else "$observationCount cached observations"
    return lastSuccessfulAgeMillis?.let { "$stored · synced ${productionAgeLabel(it)} ago" } ?: stored
}
