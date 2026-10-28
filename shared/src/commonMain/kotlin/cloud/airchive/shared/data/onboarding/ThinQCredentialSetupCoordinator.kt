package cloud.airchive.shared.data.onboarding

import cloud.airchive.shared.contract.credentials.SaveThinQResult
import cloud.airchive.shared.contract.credentials.ThinQCredentialSaveFailure
import cloud.airchive.shared.contract.credentials.ThinQCredentialState
import cloud.airchive.shared.contract.credentials.ThinQCredentialStore
import cloud.airchive.shared.contract.thinq.ThinQCredentialVerificationRequest
import cloud.airchive.shared.contract.thinq.ThinQCredentialVerifier
import cloud.airchive.shared.contract.thinq.ThinQDevice
import cloud.airchive.shared.contract.thinq.ThinQDeviceProfile
import cloud.airchive.shared.contract.thinq.ThinQVerificationResult
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.flow.first

/**
 * Narrow conditional-commit seam (OpenSpec `deliver-shared-instrument-ui` task
 * 2.4, design D2): decides, inside one lifecycle-boundary transition, whether
 * a **verified** setup candidate may run its protected-store save right now.
 *
 * [commitVerifiedCandidate] receives the store-save + result-mapping closure
 * and must either run it (the attempt is still the current one: its owner/
 * project/device/credential context is unchanged and no newer attempt or
 * explicit cancellation superseded it) or refuse it without invoking it,
 * returning [ThinQCredentialSetupResult.Superseded]. Implementations must not
 * run LG verification or any network I/O here — only the short store commit,
 * the credential-generation advance and local publication/reset state changes.
 */
fun interface ThinQSetupCommitGate {
    suspend fun commitVerifiedCandidate(
        commit: suspend () -> ThinQCredentialSetupResult,
    ): ThinQCredentialSetupResult
}

/**
 * Turns a ThinQ PAT + account country into a securely stored, verified
 * credential (OpenSpec task 3.4). This is the verification/save core: it
 * validates input, asks [ThinQCredentialVerifier] to read the configured AC,
 * and persists via [ThinQCredentialStore] only after the device is found
 * **exactly** and its profile reads successfully.
 *
 * ## Order of operations
 *
 *  1. Input is validated up front: a non-blank token and a country that is
 *     exactly two alphabetic letters, normalized to uppercase (`ph` → `PH`).
 *  2. [ThinQCredentialVerifier] reads LG's device list and the target profile
 *     using the stable [ThinQCredentialStore.clientId].
 *  3. Only when exactly the configured [configuredDeviceId] is present and its
 *     profile read succeeds is anything persisted. The device **and** profile
 *     IDs are cross-checked here again, so even a verifier that reports a
 *     mismatched identity maps to [ThinQCredentialSetupResult.Failure.WrongAccount]
 *     instead of saving.
 *  4. [ThinQCredentialStore.save] replaces the token only after step 3 — and,
 *     when a [ThinQSetupCommitGate] is supplied (task 2.4), only inside the
 *     gate's single lifecycle-boundary transition, so a superseded attempt
 *     never reaches the store at all.
 *
 * ## Preservation semantics
 *
 * Every failure path — invalid token, wrong account, unavailable/missing
 * device or profile, network failure, a verifier exception, store key loss, and
 * a generic store failure — leaves the previous stored token and its observable
 * masked credential untouched. The raw token and exception messages never
 * appear in a result, its `toString()`, metadata, or a log line. Coroutine
 * [CancellationException] is re-thrown and never recorded as a failure. A
 * [ThinQCredentialSetupResult.Superseded] outcome carries nothing at all: its
 * gate refused the commit, so no store write and no lifecycle transition
 * happened.
 *
 * @param credentialStore protected PAT storage (its `clientId` is the stable
 *   per-install LG client ID passed to the verifier).
 * @param verifier the task-4.1 adapter boundary that reads LG.
 * @param configuredDeviceId the nonsecret, configured AC device ID.
 */
class ThinQCredentialSetupCoordinator(
    private val credentialStore: ThinQCredentialStore,
    private val verifier: ThinQCredentialVerifier,
    private val configuredDeviceId: String,
) {

    /**
     * Validates, verifies, and optionally persists one ThinQ credential.
     *
     * @param commitGate optional conditional-commit seam (task 2.4). When
     *   supplied, the verified candidate's store save runs **inside** the
     *   gate's lifecycle-boundary transition; a refused gate returns
     *   [ThinQCredentialSetupResult.Superseded] and nothing is saved. When
     *   `null` (direct onboarding/settings callers and their tests), the save
     *   is unconditional and the original behavior is preserved exactly.
     * @return a safe [ThinQCredentialSetupResult.Success] (verified device +
     *   profile + observable masked credential) or a distinct typed
     *   [ThinQCredentialSetupResult.Failure] whose
     *   [ThinQCredentialSetupResult.Failure.credential] is the preserved,
     *   observable (masked) credential from before this attempt.
     * @throws CancellationException when the caller's coroutine is cancelled;
     *   nothing is persisted and no failure is recorded.
     */
    suspend fun setup(
        rawToken: String,
        country: String,
        commitGate: ThinQSetupCommitGate? = null,
    ): ThinQCredentialSetupResult {
        val normalizedCountry = country.trim().uppercase()
        if (rawToken.isBlank() || !COUNTRY_PATTERN.matches(normalizedCountry)) {
            // Input never reached the verifier or the store; the observable
            // credential is exactly what it was.
            return ThinQCredentialSetupResult.Failure.MalformedInput(currentCredential())
        }
        val request = ThinQCredentialVerificationRequest(
            rawToken = rawToken,
            accountCountry = normalizedCountry,
            clientId = credentialStore.clientId,
            expectedDeviceId = configuredDeviceId,
        )
        val priorCredential = currentCredential()
        val verification = try {
            verifier.verify(request)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Throwable) {
            // A verifier crash is neither token evidence nor a secret worth
            // surfacing; report the stable remote-failure reason with no
            // exception message and keep the previous credential.
            return ThinQCredentialSetupResult.Failure.RemoteFailure(priorCredential)
        }
        return when (verification) {
            is ThinQVerificationResult.Verified -> {
                val foundExactly = verification.device.deviceId == configuredDeviceId &&
                    verification.profile.deviceId == configuredDeviceId
                if (foundExactly) {
                    persist(request, verification.device, verification.profile, priorCredential, commitGate)
                } else {
                    ThinQCredentialSetupResult.Failure.WrongAccount(priorCredential)
                }
            }

            ThinQVerificationResult.InvalidToken ->
                ThinQCredentialSetupResult.Failure.InvalidToken(priorCredential)

            ThinQVerificationResult.WrongAccount ->
                ThinQCredentialSetupResult.Failure.WrongAccount(priorCredential)

            ThinQVerificationResult.DeviceUnavailable ->
                ThinQCredentialSetupResult.Failure.DeviceUnavailable(priorCredential)

            ThinQVerificationResult.Network ->
                ThinQCredentialSetupResult.Failure.NetworkFailure(priorCredential)

            ThinQVerificationResult.RemoteFailure ->
                ThinQCredentialSetupResult.Failure.RemoteFailure(priorCredential)
        }
    }

    /**
     * Persists a fully verified credential. The store contract guarantees a
     * failed replacement leaves the last valid token untouched; key loss and
     * generic replacement failures stay distinct typed reasons.
     *
     * With [commitGate] the whole save + result mapping is one call into the
     * gate (task 2.4): the lifecycle boundary owns whether this attempt may
     * commit at all, and a refused gate never invokes the store save.
     */
    private suspend fun persist(
        request: ThinQCredentialVerificationRequest,
        device: ThinQDevice,
        profile: ThinQDeviceProfile,
        priorCredential: ThinQCredentialState,
        commitGate: ThinQSetupCommitGate?,
    ): ThinQCredentialSetupResult {
        val commit: suspend () -> ThinQCredentialSetupResult = {
            persistVerified(request, device, profile, priorCredential)
        }
        return if (commitGate == null) commit() else commitGate.commitVerifiedCandidate(commit)
    }

    /** Runs the protected-store replacement for an already-verified candidate. */
    private suspend fun persistVerified(
        request: ThinQCredentialVerificationRequest,
        device: ThinQDevice,
        profile: ThinQDeviceProfile,
        priorCredential: ThinQCredentialState,
    ): ThinQCredentialSetupResult {
        val saved = try {
            credentialStore.save(request.rawToken, request.accountCountry)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Throwable) {
            return ThinQCredentialSetupResult.Failure.StoreSaveFailure(priorCredential)
        }
        return when (saved) {
            is SaveThinQResult.Success -> ThinQCredentialSetupResult.Success(
                device = device,
                profile = profile,
                credential = currentCredentialOr(priorCredential),
            )

            is SaveThinQResult.Failure -> when (saved.reason) {
                // KEY_UNAVAILABLE is its own reason; the caller routes the owner
                // back through setup rather than treating it as a generic save
                // error. INVALID_INPUT cannot occur after step-1 validation;
                // UNKNOWN and any other reason stay the generic replacement
                // failure, with the previous credential preserved.
                ThinQCredentialSaveFailure.KEY_UNAVAILABLE ->
                    ThinQCredentialSetupResult.Failure.StoreKeyUnavailable(priorCredential)

                else -> ThinQCredentialSetupResult.Failure.StoreSaveFailure(priorCredential)
            }
        }
    }

    /** The store's current observable masked credential; never the plaintext. */
    private suspend fun currentCredential(): ThinQCredentialState = try {
        credentialStore.observe().first()
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: Throwable) {
        ThinQCredentialState(present = false, clientId = credentialStore.clientId)
    }

    /**
     * Reads the post-save observable credential, falling back to [fallback]
     * only if the store itself cannot be observed (the save still succeeded).
     */
    private suspend fun currentCredentialOr(fallback: ThinQCredentialState): ThinQCredentialState = try {
        credentialStore.observe().first()
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: Throwable) {
        fallback
    }

    private companion object {
        /** Exactly two alphabetic letters, e.g. `PH`. */
        private val COUNTRY_PATTERN = Regex("^[A-Za-z]{2}$")
    }
}

/**
 * Outcome of one ThinQ setup attempt.
 *
 * [Success] carries only safe data on every path: the verified device, its
 * profile, and the observable **masked** credential. [Failure] carries the
 * preserved observable credential from before the attempt plus a distinct
 * typed reason. No variant contains the raw token or an exception message, so
 * `toString()` can never leak a secret.
 */
sealed interface ThinQCredentialSetupResult {

    /** The configured AC was verified and the token was securely replaced. */
    data class Success(
        val device: ThinQDevice,
        val profile: ThinQDeviceProfile,
        /** Observable masked credential after the successful replacement. */
        val credential: ThinQCredentialState,
    ) : ThinQCredentialSetupResult

    /**
     * The attempt was superseded before its protected-store commit (task 2.4):
     * the owner changed or signed out, the credential was removed or replaced,
     * the protected key was lost, a newer attempt began, or the attempt was
     * explicitly cancelled (form/route cancellation or coroutine
     * cancellation). Nothing was saved, no credential generation was advanced,
     * and no verified profile or success feedback belongs to it — the UI must
     * suppress setup feedback for this outcome rather than present it as an
     * error or a success for the now-active context. A prior working
     * credential (if any) is untouched.
     */
    data object Superseded : ThinQCredentialSetupResult

    /** A setup attempt that did not replace the stored credential. */
    sealed interface Failure : ThinQCredentialSetupResult {

        /** Observable (masked) credential preserved from before this attempt. */
        val credential: ThinQCredentialState

        /** Blank token, or a country that is not exactly two alphabetic letters. */
        data class MalformedInput(override val credential: ThinQCredentialState) : Failure

        /** LG rejected the presented token. */
        data class InvalidToken(override val credential: ThinQCredentialState) : Failure

        /** The token's account cannot reach the configured AC. */
        data class WrongAccount(override val credential: ThinQCredentialState) : Failure

        /** The configured AC is missing/unavailable in this account. */
        data class DeviceUnavailable(override val credential: ThinQCredentialState) : Failure

        /** LG unreachable during verification; the token was not judged. */
        data class NetworkFailure(override val credential: ThinQCredentialState) : Failure

        /** A verifier exception or unexpected remote failure; no message leaks. */
        data class RemoteFailure(override val credential: ThinQCredentialState) : Failure

        /** The platform-protected key is unavailable; nothing was replaced. */
        data class StoreKeyUnavailable(override val credential: ThinQCredentialState) : Failure

        /** A generic protected-store replacement failure; the prior token survived. */
        data class StoreSaveFailure(override val credential: ThinQCredentialState) : Failure
    }
}