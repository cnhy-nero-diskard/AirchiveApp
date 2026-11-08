package cloud.airchive.shared.data.onboarding

import cloud.airchive.shared.contract.credentials.ThinQCredentialStore
import cloud.airchive.shared.contract.history.HistoryPartition
import cloud.airchive.shared.contract.history.LocalHistoryPort
import cloud.airchive.shared.contract.owner.OwnerSession
import cloud.airchive.shared.data.thinq.ThinQContextCoordinator
import kotlin.coroutines.cancellation.CancellationException

/** Platform cache whose documents belong to the Firebase owner session. */
fun interface OwnerDocumentCache {
    suspend fun clear()
}

/**
 * Serializes owner cleanup for session changes that happen outside Settings.
 * The ThinQ context is invalidated before waiting for the history operation
 * lock, then the previous Room partition, protected credential, and platform
 * document cache are cleared under that same lock. A cleanup failure leaves
 * ThinQ unbound and retains the failed owner for the next transition retry.
 */
class OwnerTransition(
    private val local: LocalHistoryPort,
    private val credentialStore: ThinQCredentialStore,
    private val thinQ: ThinQContextCoordinator,
    private val documentCache: OwnerDocumentCache,
    private val firebaseProjectId: String,
    private val configuredDeviceId: String,
    private val currentSession: () -> OwnerSession?,
    private val serializedAction: suspend (suspend () -> OwnerSettingsActionFailure?) -> OwnerSettingsActionFailure? = { action -> action() },
    private val reportOutcome: suspend (OwnerSettingsActionFailure?) -> Unit = {},
) {

    /** Owners remain queued until all cleanup steps succeed. */
    private val pendingOwners = linkedMapOf<String, OwnerSession>()

    /** Handles a live Firebase session change; repeated same-UID emissions are ignored. */
    suspend fun onSessionChanged(
        previous: OwnerSession?,
        next: OwnerSession?,
    ): OwnerSettingsActionFailure? {
        if (previous?.uid == next?.uid) return null

        // Fence device entry and commands immediately, even while an in-flight
        // history operation still owns the shared serialization lock.
        thinQ.onOwnerChanged(ownerUid = null, projectId = firebaseProjectId)

        // The first restored owner has no outgoing partition to remove. Bind
        // only after confirming this emission is still the live session.
        if (previous == null) {
            if (next != null && currentSession()?.uid == next.uid) {
                thinQ.onOwnerChanged(ownerUid = next.uid, projectId = firebaseProjectId)
            }
            return null
        }

        val failure = serializedAction {
            // Reassert the fence under the shared history/account lock after
            // invalidating early above to protect the wait for that lock.
            thinQ.onOwnerChanged(ownerUid = null, projectId = firebaseProjectId)
            pendingOwners[previous.uid] = previous
            var firstFailure: OwnerSettingsActionFailure? = null
            for (owner in pendingOwners.values.toList()) {
                try {
                    local.clear(partitionFor(owner))
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Throwable) {
                    if (firstFailure == null) firstFailure = OwnerSettingsActionFailure.LOCAL_CACHE_CLEAR_FAILED
                }
            }

            try {
                credentialStore.clear()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Throwable) {
                if (firstFailure == null) firstFailure = OwnerSettingsActionFailure.CREDENTIAL_STORE_CLEAR_FAILED
            }

            try {
                documentCache.clear()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Throwable) {
                if (firstFailure == null) firstFailure = OwnerSettingsActionFailure.FIRESTORE_CACHE_CLEAR_FAILED
            }

            if (firstFailure == null) {
                pendingOwners.clear()
                if (currentSession()?.uid == next?.uid) {
                    thinQ.onOwnerChanged(ownerUid = next?.uid, projectId = firebaseProjectId)
                }
            }
            firstFailure
        }

        reportOutcome(failure)
        return failure
    }

    private fun partitionFor(owner: OwnerSession) = HistoryPartition(
        firebaseProjectId = firebaseProjectId,
        ownerUid = owner.uid,
        deviceId = configuredDeviceId,
    )
}
