package cloud.airchive.shared.data.thinq

import cloud.airchive.shared.contract.thinq.ThinQDevice
import cloud.airchive.shared.contract.thinq.ThinQDeviceProfile
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Opaque lifecycle context key for one ThinQ runtime (OpenSpec
 * `deliver-shared-instrument-ui` task 2.1, design D2).
 *
 * The key partitions every runtime state and operation by **owner / project /
 * device** and adds a monotonically increasing **credential generation**. It is
 * deliberately derived from nothing secret:
 *
 *  - it never contains a raw token, a token mask, or a credential timestamp, so
 *    it cannot be compared against credential metadata to detect a replacement;
 *  - a replacement is only ever observed through an explicit generation
 *    advance (see `ThinQContextCoordinator`), so two credentials with identical
 *    masks/country/creation time still produce different keys;
 *  - equality on the whole key is the only operation callers need; the fields
 *    exist for partitioning and diagnostics, never as capability.
 *
 * Every invalidation strictly advances the generation, so a key that was ever
 * current can never become current again (for example an owner switching away
 * and back).
 */
data class ThinQContextKey(
    val ownerUid: String?,
    val projectId: String,
    val deviceId: String,
    val credentialGeneration: Long,
)

/**
 * Device identity plus verified profile, published **atomically** together
 * with the [contextKey] they were read under.
 *
 * Callers fence rendering by comparing [contextKey] with the boundary's
 * current key, so a torn read can only hide the card, never render one
 * context's device/profile under another context.
 */
data class ThinQDeviceProfilePublication(
    val contextKey: ThinQContextKey,
    val device: ThinQDevice,
    /** `null` when the device is offline: identity without a verified profile. */
    val profile: ThinQDeviceProfile?,
)

/** Result of one Device-entry read (`onDeviceOpened`). */
sealed interface ThinQEntryResult {

    /** A device-matching live read and verified profile were published for the captured context. */
    data object Success : ThinQEntryResult

    /** No LG request was possible or the configured device/profile was unavailable. */
    data object Unavailable : ThinQEntryResult

    /** The list/profile/live network work failed inside the still-unchanged context. */
    data object Failed : ThinQEntryResult

    /** The lifecycle context changed while the read ran; nothing was published for it. */
    data object Superseded : ThinQEntryResult
}

/**
 * The explicit lifecycle boundary (OpenSpec task 2.1/2.2, design D2).
 *
 * Owns the current [ThinQContextKey] and the boundary [Mutex]. All publication
 * of context-scoped state and every invalidation happen under this boundary so
 * that "check the key, then write" is atomic against "advance the key, then
 * clear".
 *
 * ## Lock order
 *
 * **Lifecycle boundary → profile mutex**, never the reverse. The boundary is
 * never held across LG I/O: network steps run with no locks held, so
 * [invalidateCredential]/[invalidateOwner] complete even while a list, profile
 * or live request is suspended (task 2.2).
 */
class ThinQLifecycleBoundary(
    initialOwnerUid: String?,
    initialProjectId: String,
    private val deviceId: String,
) {

    private val lock = Mutex()
    private val mutableKey = MutableStateFlow(
        ThinQContextKey(initialOwnerUid, initialProjectId, deviceId, credentialGeneration = 0L),
    )

    /** Current context key; changes synchronously inside every invalidation. */
    val key: StateFlow<ThinQContextKey> = mutableKey.asStateFlow()

    fun current(): ThinQContextKey = mutableKey.value

    /** True only when [candidate] is the currently active context. */
    fun isCurrent(candidate: ThinQContextKey?): Boolean = candidate != null && mutableKey.value == candidate

    /**
     * Runs [block] while holding the lifecycle boundary.
     *
     * Lock order: lifecycle boundary → profile mutex (never the reverse), and
     * the boundary is never held while an LG request is in flight.
     */
    suspend fun <T> withBoundary(block: suspend () -> T): T = lock.withLock { block() }

    /**
     * Credential-scoped invalidation: advances the credential generation for
     * the unchanged owner/project/device partition and runs [cleanup]
     * atomically under the boundary. [cleanup] runs **after** the old key
     * stopped being current, so nothing can publish for it anymore, and it may
     * take the profile mutex (boundary → profile order).
     *
     * Used for credential removal, verified replacement (even when the masked
     * metadata is identical), protected-key loss and any credential-absent
     * presentation clear.
     */
    suspend fun invalidateCredential(cleanup: suspend (newKey: ThinQContextKey) -> Unit): ThinQContextKey =
        withBoundary {
            val current = mutableKey.value
            val next = current.copy(credentialGeneration = current.credentialGeneration + 1L)
            mutableKey.value = next
            cleanup(next)
            next
        }

    /**
     * Owner-scoped invalidation: moves the partition to [ownerUid]/[projectId]
     * and advances the generation, so a key from a previous owner session can
     * never become current again even if the same owner signs back in.
     */
    suspend fun invalidateOwner(
        ownerUid: String?,
        projectId: String,
        cleanup: suspend (newKey: ThinQContextKey) -> Unit = {},
    ): ThinQContextKey = withBoundary {
        val next = ThinQContextKey(
            ownerUid = ownerUid,
            projectId = projectId,
            deviceId = deviceId,
            credentialGeneration = mutableKey.value.credentialGeneration + 1L,
        )
        mutableKey.value = next
        cleanup(next)
        next
    }
}

/**
 * Publishes [publish] only while [captured] is still the current context.
 *
 * The check and the write run in one boundary section, so an invalidation can
 * never slip between them and a late old-context result can never repopulate a
 * cleared presentation. A `null` receiver means "no boundary" (demo/preview
 * graphs): the write happens directly, matching their single-context lifetime.
 *
 * Returns whether the write happened.
 */
internal suspend fun ThinQLifecycleBoundary?.publishIfCurrent(
    captured: ThinQContextKey?,
    publish: suspend () -> Unit,
): Boolean {
    val boundary = this
    if (boundary == null) {
        publish()
        return true
    }
    return boundary.withBoundary {
        if (boundary.isCurrent(captured)) {
            publish()
            true
        } else {
            false
        }
    }
}

/**
 * Dispatch fence: an operation may only reach LG when its captured context is
 * still current. A `null` receiver (demo/preview) or a `null` captured key
 * under a `null` receiver is always current; a non-null boundary with a null
 * key is treated as stale (safe default).
 */
internal fun ThinQLifecycleBoundary?.isCurrentContext(captured: ThinQContextKey?): Boolean =
    this == null || (captured != null && isCurrent(captured))
