package cloud.airchive.shared.data.thinq

import cloud.airchive.shared.contract.thinq.ThinQDevice
import cloud.airchive.shared.contract.thinq.ThinQDeviceProfile
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

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
 * One credential-setup attempt (OpenSpec task 2.4, design D2): a
 * **monotonically revised** attempt id plus the owner/project/device +
 * credential-generation context captured when the attempt began.
 *
 * The identity (id + captured key) is the only thing the commit gate compares;
 * the type deliberately carries no token, mask, country or timestamp, so an
 * attempt can never be correlated with credential metadata. A newer attempt
 * replaces the active one, and every invalidation clears it, so an attempt id
 * can never become current again — even for the unchanged owner/context.
 */
data class ThinQSetupAttempt(
    val id: Long,
    val contextKey: ThinQContextKey,
)

/** Outcome of one [ThinQLifecycleBoundary.commitSetupAttemptIfCurrent] call. */
sealed interface ThinQSetupCommit<out T> {

    /**
     * The attempt was current: [commit] ran (its protected-store save and
     * result mapping) and, for a successful save, the credential generation
     * advanced to [resultingKey] in the same boundary section.
     */
    data class Committed<T>(val result: T, val resultingKey: ThinQContextKey?) : ThinQSetupCommit<T>

    /**
     * The gate refused: the attempt was cancelled, superseded by a newer
     * attempt, or its captured context ended (owner transition, removal,
     * verified replacement, protected-key loss). [ThinQLifecycleBoundary]'s
     * store-commit closure was **never invoked**, so nothing was saved and no
     * generation/profile transition happened.
     */
    data object Superseded : ThinQSetupCommit<Nothing>
}

/**
 * The explicit lifecycle boundary (OpenSpec task 2.1/2.2, design D2).
 *
 * Owns the current [ThinQContextKey] and the boundary [Mutex]. All publication
 * of context-scoped state and every invalidation happen under this boundary so
 * that "check the key, then write" is atomic against "advance the key, then
 * clear".
 *
 * It also owns the **setup-attempt fence** (task 2.4): one active
 * [ThinQSetupAttempt] whose current-attempt/context gate and the caller's
 * protected-store commit run in the same boundary section
 * ([commitSetupAttemptIfCurrent]), serializing commit versus invalidation.
 *
 * ## Lock order
 *
 * **Lifecycle boundary → profile mutex**, never the reverse. The boundary is
 * never held across LG I/O: network steps run with no locks held, so
 * [invalidateCredential]/[invalidateOwner] complete even while a list, profile
 * or live request is suspended (task 2.2). A setup attempt's LG verification
 * runs with no lock held; only its short protected-store commit re-enters the
 * boundary (and only after the current-attempt gate).
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

    /**
     * Setup-attempt fence (task 2.4): the monotonically revised attempt
     * counter and the single active attempt allowed to reach its
     * protected-store commit. Only ever touched while [lock] is held.
     */
    private var attemptCounter = 0L
    private var activeSetupAttempt: ThinQSetupAttempt? = null

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
     * Any in-flight setup attempt is superseded here: a still-verifying
     * candidate can never pass its final commit gate after this transition.
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
            activeSetupAttempt = null
            cleanup(next)
            next
        }

    /**
     * Owner-scoped invalidation: moves the partition to [ownerUid]/[projectId]
     * and advances the generation, so a key from a previous owner session can
     * never become current again even if the same owner signs back in.
     * Supersedes any in-flight setup attempt (see [invalidateCredential]).
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
        activeSetupAttempt = null
        cleanup(next)
        next
    }

    // -------------------------------------------------- setup-attempt fence

    /**
     * Begins a new setup attempt (task 2.4): takes the next monotonically
     * revised attempt id, captures the current owner/project/device +
     * credential-generation context, and makes this the single active attempt.
     * Starting an attempt supersedes any earlier in-flight attempt for the
     * unchanged owner; a later [commitSetupAttemptIfCurrent] for a superseded
     * attempt is refused before its store commit.
     */
    suspend fun beginSetupAttempt(): ThinQSetupAttempt = withBoundary {
        attemptCounter += 1
        ThinQSetupAttempt(id = attemptCounter, contextKey = mutableKey.value).also { activeSetupAttempt = it }
    }

    /**
     * Explicit cancel-attempt hook (task 2.4) for form/route cancellation.
     * Supersedes **only** the active attempt ([attempt] when given, otherwise
     * whichever attempt is active): it never advances the generation, never
     * touches the store, and never affects other or later attempts.
     *
     * Returns whether an active attempt was actually cancelled.
     */
    suspend fun cancelSetupAttempt(attempt: ThinQSetupAttempt? = null): Boolean = withBoundary {
        val active = activeSetupAttempt
        if (active == null || (attempt != null && active != attempt)) {
            false
        } else {
            activeSetupAttempt = null
            true
        }
    }

    /**
     * Boundary-serialized currency check for [attempt] (task 2.4): true only
     * while it is still the single active attempt **and** its captured context
     * is unchanged. Used to fence the exposure of non-committed outcomes
     * (delayed verification failures), so a stale result is never exposed for
     * the now-active context. An attempt consumed by a successful gate passage
     * is no longer current here — commit-path results are already final.
     */
    suspend fun isSetupAttemptCurrent(attempt: ThinQSetupAttempt): Boolean = withBoundary {
        activeSetupAttempt == attempt && mutableKey.value == attempt.contextKey
    }

    /**
     * The protected-store commit fence (task 2.4): the **final** attempt +
     * context gate and the caller's [commit] run in one boundary transition,
     * so an invalidation can never slip between "this attempt is current" and
     * the store save + credential-generation advance.
     *
     * Order of operations:
     *
     *  1. The gate runs in the caller's cancellable context: a stale
     *     (cancelled/superseded/context-ended) attempt is refused here and
     *     [commit] is never invoked — no save, no generation/profile
     *     transition, no success feedback.
     *  2. The attempt is consumed, so it can commit exactly once.
     *  3. [commit] runs in a short **non-cancellable** section that also covers
     *     the generation advance: cancellation after the gate passed can never
     *     leave a saved PAT without its matching generation/profile
     *     transition. [commit] receives [advanceCredentialGeneration], which it
     *     must call exactly when its save succeeded; the returned resulting key
     *     is the one the verified profile and success feedback belong to.
     *
     * Never invoke LG verification or any network I/O through [commit]; the
     * boundary section is for the short store commit and local state changes
     * only.
     */
    suspend fun <T> commitSetupAttemptIfCurrent(
        attempt: ThinQSetupAttempt,
        commit: suspend (advanceCredentialGeneration: () -> ThinQContextKey) -> T,
    ): ThinQSetupCommit<T> {
        // A caller cancelled while LG verification was in flight must not pass
        // a gate merely because its verifier ignored cancellation and returned.
        currentCoroutineContext().ensureActive()
        return withBoundary {
            if (activeSetupAttempt != attempt ||
                mutableKey.value != attempt.contextKey ||
                attempt.contextKey.ownerUid == null
            ) {
                return@withBoundary ThinQSetupCommit.Superseded
            }
            activeSetupAttempt = null
            withContext(NonCancellable) {
                var resultingKey: ThinQContextKey? = null
                val result = commit {
                    val next = mutableKey.value.copy(
                        credentialGeneration = mutableKey.value.credentialGeneration + 1L,
                    )
                    mutableKey.value = next
                    resultingKey = next
                    next
                }
                ThinQSetupCommit.Committed(result, resultingKey)
            }
        }
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
    this == null || (captured != null && captured.ownerUid != null && isCurrent(captured))
