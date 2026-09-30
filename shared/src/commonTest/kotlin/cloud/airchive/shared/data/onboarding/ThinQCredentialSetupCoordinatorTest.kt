package cloud.airchive.shared.data.onboarding

import cloud.airchive.shared.contract.credentials.SaveThinQResult
import cloud.airchive.shared.contract.credentials.ThinQCredentialSaveFailure
import cloud.airchive.shared.contract.credentials.ThinQCredentialStore
import cloud.airchive.shared.contract.thinq.AcControls
import cloud.airchive.shared.contract.thinq.ThinQCredentialVerificationRequest
import cloud.airchive.shared.contract.thinq.ThinQCredentialVerifier
import cloud.airchive.shared.contract.thinq.ThinQDevice
import cloud.airchive.shared.contract.thinq.ThinQDeviceProfile
import cloud.airchive.shared.contract.thinq.ThinQVerificationResult
import cloud.airchive.shared.fakes.FakeThinQCredentialStore
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Deterministic tests for the ThinQ verification/save core (OpenSpec task 3.4),
 * driven by a scripted [ThinQCredentialVerifier] and the in-memory
 * [FakeThinQCredentialStore]. No live LG HTTP, no task-4.1 Ktor adapter, and no
 * real credential anywhere: every token is a synthetic fixture branded with the
 * reserved `.example.invalid` domain.
 *
 * Proves the correct device+profile path passes the stable per-install client
 * ID and the PH-normalized country; that wrong account, invalid token, network
 * failure, unavailable device/profile, malformed input, a rejected store
 * replacement, and key loss all leave the prior token and its observable mask
 * untouched; that a verified success replaces the token and exposes only the
 * mask; distinct typed failures (KEY_UNAVAILABLE vs generic store failure); and
 * that a read verification carries no command/control-authority claim.
 */
class ThinQCredentialSetupCoordinatorTest {

    private val configuredDeviceId = "demo-ac-id"

    private val device = ThinQDevice(
        deviceId = configuredDeviceId,
        alias = "Fixture Room AC",
        modelName = "Fixture AC-01",
        online = true,
    )

    private val profile = ThinQDeviceProfile(
        deviceId = configuredDeviceId,
        modelName = "Fixture AC-01",
        controls = AcControls(powerWritable = true),
    )

    /** Synthetic fixture PAT, `.example.invalid`-branded (never a real credential). */
    private val goodToken = "thinq-fixture.example.invalid/good-9f3a"

    /** Synthetic fixture PAT stored as the "prior" working credential. */
    private val priorToken = "thinq-fixture.example.invalid/prior-a1b2"

    private fun coordinator(
        store: ThinQCredentialStore,
        verifier: ThinQCredentialVerifier,
    ) = ThinQCredentialSetupCoordinator(store, verifier, configuredDeviceId)

    // ------------------------------------ correct device + profile happy path

    @Test
    fun verifiedDeviceAndProfilePassStableClientIdAndNormalizedCountry() = runBlocking {
        val store = FakeThinQCredentialStore()
        val verifier = ScriptedVerifier(device = device, profile = profile)
        val coordinator = coordinator(store, verifier)

        // A lowercase country is accepted and normalized to "PH".
        val success = assertIs<ThinQCredentialSetupResult.Success>(
            coordinator.setup(goodToken, "ph"),
        )

        // The verifier saw the stable per-install client ID, the expected AC,
        // the normalized country (never "ph"), and the plaintext token once.
        val request = assertNotNull(verifier.lastRequest)
        assertEquals(store.clientId, request.clientId, "the verifier must receive the stable per-install client ID")
        assertEquals(configuredDeviceId, request.expectedDeviceId)
        assertEquals("PH", request.accountCountry)
        assertEquals(goodToken, request.rawToken)
        assertFalse(request.toString().contains(goodToken), "even the request toString masks the raw PAT")

        // Success carries the verified device + profile and a masked credential.
        assertEquals(configuredDeviceId, success.device.deviceId)
        assertEquals(configuredDeviceId, success.profile.deviceId)
        val masked = assertNotNull(success.credential.credential)
        assertEquals("PH", masked.accountCountry)
        assertFalse(masked.maskedToken.contains(goodToken), "the mask must never embed the raw PAT")
        assertTrue(masked.maskedToken.endsWith(goodToken.takeLast(4)))
        assertFalse(success.toString().contains(goodToken), "no serialized success state may leak the raw PAT")

        // The store committed exactly the normalized credential.
        assertEquals(goodToken, store.readToken())
        assertEquals("PH", store.observe().first().credential!!.accountCountry)
    }

    // ------------------------------ verifier outcomes preserve prior credential

    @Test
    fun wrongAccountPreservesPriorTokenAndObservableMask(): Unit = runBlocking {
        expectPreservingFailure(
            store = FakeThinQCredentialStore(),
            verifier = ScriptedVerifier(device, profile).apply {
                result = ThinQVerificationResult.WrongAccount
            },
        ) { result ->
            assertIs<ThinQCredentialSetupResult.Failure.WrongAccount>(result)
        }
    }

    @Test
    fun invalidTokenPreservesPriorTokenAndObservableMask(): Unit = runBlocking {
        expectPreservingFailure(
            store = FakeThinQCredentialStore(),
            verifier = ScriptedVerifier(device, profile).apply {
                result = ThinQVerificationResult.InvalidToken
            },
        ) { result ->
            assertIs<ThinQCredentialSetupResult.Failure.InvalidToken>(result)
        }
    }

    @Test
    fun networkFailurePreservesPriorTokenAndObservableMask(): Unit = runBlocking {
        expectPreservingFailure(
            store = FakeThinQCredentialStore(),
            verifier = ScriptedVerifier(device, profile).apply {
                result = ThinQVerificationResult.Network
            },
        ) { result ->
            assertIs<ThinQCredentialSetupResult.Failure.NetworkFailure>(result)
        }
    }

    @Test
    fun unavailableDeviceOrProfilePreservesPriorCredential(): Unit = runBlocking {
        expectPreservingFailure(
            store = FakeThinQCredentialStore(),
            verifier = ScriptedVerifier(device, profile).apply {
                result = ThinQVerificationResult.DeviceUnavailable
            },
        ) { result ->
            assertIs<ThinQCredentialSetupResult.Failure.DeviceUnavailable>(result)
        }
    }

    @Test
    fun verifierCrashBecomesRemoteFailureWithoutLeakingTheMessage(): Unit = runBlocking {
        expectPreservingFailure(
            store = FakeThinQCredentialStore(),
            verifier = ScriptedVerifier(device, profile).apply {
                failure = IllegalStateException("boom secret-context-secret")
            },
        ) { result ->
            val remote = assertIs<ThinQCredentialSetupResult.Failure.RemoteFailure>(result)
            assertFalse(
                remote.toString().contains("boom"),
                "a verifier exception message must never appear in the result",
            )
        }
    }

    // -------------------------- identity/profile mismatch maps to WRONG_ACCOUNT

    @Test
    fun verifierReportedProfileMismatchMapsToWrongAccountAndNeverSaves(): Unit = runBlocking {
        // The verifier "verified" a device whose *profile* belongs to a
        // different AC: the configured device was not found exactly.
        val mismatchedProfile = profile.copy(deviceId = "other-ac-id")
        expectPreservingFailure(
            store = FakeThinQCredentialStore(),
            verifier = ScriptedVerifier(device, mismatchedProfile),
        ) { result ->
            assertIs<ThinQCredentialSetupResult.Failure.WrongAccount>(result)
        }
    }

    @Test
    fun verifierReportedDeviceMismatchMapsToWrongAccountAndNeverSaves(): Unit = runBlocking {
        val otherDevice = device.copy(deviceId = "other-ac-id")
        expectPreservingFailure(
            store = FakeThinQCredentialStore(),
            verifier = ScriptedVerifier(otherDevice, profile),
        ) { result ->
            val wrong = assertIs<ThinQCredentialSetupResult.Failure.WrongAccount>(result)
            assertFalse(wrong.toString().contains(goodToken))
        }
    }

    // ------------------------------------------ malformed input never verifies

    @Test
    fun malformedTokenOrCountryFailsBeforeTheVerifierIsCalled() = runBlocking {
        val store = FakeThinQCredentialStore()
        store.save(priorToken, "PH")
        val verifier = ScriptedVerifier(device, profile)
        val coordinator = coordinator(store, verifier)

        val malformedInputs = listOf(
            "" to "PH",
            "   " to "PH",
            goodToken to "",
            goodToken to "P",
            goodToken to "P1",
            goodToken to "PHI",
            goodToken to "philippines",
        )
        for ((token, country) in malformedInputs) {
            val malformed = assertIs<ThinQCredentialSetupResult.Failure.MalformedInput>(
                coordinator.setup(token, country),
            )
            assertFalse(malformed.toString().contains(goodToken))
        }
        assertEquals(0, verifier.callCount, "input validation must run before any LG verification")
        assertEquals(priorToken, store.readToken())
        assertEquals(priorToken.takeLast(4), store.observe().first().credential!!.maskedToken.takeLast(4))
    }

    // ---------------------------- failed store replacement keeps the prior token

    @Test
    fun rejectedStoreReplacementKeepsPriorTokenAsGenericFailure() = runBlocking {
        val delegate = FakeThinQCredentialStore()
        delegate.save(priorToken, "PH")
        val store = FailOnSaveCredentialStore(delegate, failSaveWith = ThinQCredentialSaveFailure.UNKNOWN)
        val verifier = ScriptedVerifier(device, profile) // verification itself succeeds

        val result = coordinator(store, verifier).setup(goodToken, "PH")

        val failure = assertIs<ThinQCredentialSetupResult.Failure.StoreSaveFailure>(result)
        assertFalse(failure.toString().contains(goodToken))
        assertEquals(priorToken, delegate.readToken(), "a rejected replacement must keep the previous token")
        assertEquals(priorToken.takeLast(4), delegate.observe().first().credential!!.maskedToken.takeLast(4))
    }

    @Test
    fun keyUnavailableIsItsOwnTypedFailureAndKeepsThePriorMask() = runBlocking {
        val store = FakeThinQCredentialStore()
        store.save(priorToken, "PH")
        store.keyUnavailable = true
        val verifier = ScriptedVerifier(device, profile)

        val result = coordinator(store, verifier).setup(goodToken, "PH")

        // KEY_UNAVAILABLE maps to its own typed reason, distinct from the
        // generic store failure exercised above.
        val failure = assertIs<ThinQCredentialSetupResult.Failure.StoreKeyUnavailable>(result)
        assertFalse(failure.toString().contains(goodToken))

        // The observable state still carries the prior masked credential...
        val priorMask = store.observe().first().credential!!
        assertEquals(priorToken.takeLast(4), priorMask.maskedToken.takeLast(4))
        assertFalse(priorMask.maskedToken.contains(goodToken))

        // ...and with the key recovered, the plaintext is still the prior token
        // (it was never replaced; the key loss only made it temporarily
        // undecryptable).
        store.keyUnavailable = false
        assertEquals(priorToken, store.readToken())
    }

    // ------------------------------------ successful replacement swaps the token

    @Test
    fun successfulReplacementSwapsTheTokenAndExposesOnlyTheMask() = runBlocking {
        val store = FakeThinQCredentialStore()
        store.save(priorToken, "PH")
        val verifier = ScriptedVerifier(device, profile)

        val result = coordinator(store, verifier).setup(goodToken, "ph")

        val success = assertIs<ThinQCredentialSetupResult.Success>(result)
        assertEquals(configuredDeviceId, success.device.deviceId)
        assertEquals(configuredDeviceId, success.profile.deviceId)
        val masked = assertNotNull(success.credential.credential)
        assertEquals("PH", masked.accountCountry)
        assertEquals(goodToken.takeLast(4), masked.maskedToken.takeLast(4))
        assertFalse(masked.maskedToken.contains(goodToken), "the mask must never embed the raw PAT")
        assertFalse(
            masked.maskedToken.contains(priorToken),
            "the prior mask must be gone after a verified replacement",
        )

        // The store now holds the new token; only the masked form is exposed.
        assertEquals(goodToken, store.readToken())
        assertEquals(goodToken.takeLast(4), store.observe().first().credential!!.maskedToken.takeLast(4))
        assertFalse(success.toString().contains(goodToken))
    }

    // ----------------------- cancellation is re-thrown and never persists

    @Test
    fun cancellationDuringVerificationRethrowsAndKeepsPriorToken() = runBlocking {
        val store = FakeThinQCredentialStore()
        store.save(priorToken, "PH")
        val verifier = ScriptedVerifier(device, profile).apply {
            failure = CancellationException("cancelled mid-verification")
        }

        assertFailsWith<CancellationException> {
            coordinator(store, verifier).setup(goodToken, "PH")
        }
        assertEquals(priorToken, store.readToken(), "a cancelled attempt never touches the stored token")
    }

    @Test
    fun cancellationDuringStoreSaveRethrowsAndKeepsPriorToken() = runBlocking {
        val delegate = FakeThinQCredentialStore()
        delegate.save(priorToken, "PH")
        val store = FailOnSaveCredentialStore(delegate, throwOnSave = CancellationException("cancelled during save"))
        val verifier = ScriptedVerifier(device, profile)

        assertFailsWith<CancellationException> {
            coordinator(store, verifier).setup(goodToken, "PH")
        }
        assertEquals(priorToken, delegate.readToken(), "a cancelled save never replaces the previous token")
    }

    // --------------------- read verification is not a command-authority proof

    @Test
    fun verifiedReadCarriesNoCommandAuthorityClaim() = runBlocking {
        val store = FakeThinQCredentialStore()
        val verifier = ScriptedVerifier(device, profile)

        val success = assertIs<ThinQCredentialSetupResult.Success>(
            coordinator(store, verifier).setup(goodToken, "PH"),
        )

        // The entire verification surface is exactly three data slots — the
        // device, its profile, and the observable masked credential.
        // Destructuring exposes precisely that shape: there is no field or flag
        // anywhere that claims command/control permission. The
        // ThinQCredentialVerifier contract forbids presenting a read
        // verification as proof of control authority, and LG classifies a
        // missing control authority on the first actual command.
        val (verifiedDevice, verifiedProfile, verifiedCredential) = success
        assertEquals(configuredDeviceId, verifiedDevice.deviceId)
        assertEquals(configuredDeviceId, verifiedProfile.deviceId)
        assertNotNull(verifiedCredential.credential?.maskedToken)
        assertFalse(success.toString().contains(goodToken))
    }

    // ------------------------------------------------------------------ helpers

    /**
     * Stores [priorToken] first, runs one setup attempt for [goodToken], then
     * asserts the typed result via [after] AND that the attempt left the prior
     * token and its observable mask untouched and leaked nothing.
     */
    private suspend fun expectPreservingFailure(
        store: FakeThinQCredentialStore,
        verifier: ScriptedVerifier,
        after: (ThinQCredentialSetupResult) -> Unit,
    ): ThinQCredentialSetupResult {
        store.save(priorToken, "PH")
        val result = coordinator(store, verifier).setup(goodToken, "PH")
        after(result)
        assertFalse(result.toString().contains(goodToken), "no failure result may leak the plaintext PAT")
        assertEquals(priorToken, store.readToken(), "a failed attempt must never replace the previous token")
        val priorMask = store.observe().first().credential!!
        assertEquals(priorToken.takeLast(4), priorMask.maskedToken.takeLast(4))
        assertFalse(priorMask.maskedToken.contains(goodToken), "the previous mask must not advance on failure")
        return result
    }
}

/**
 * Scripted [ThinQCredentialVerifier]: deterministic, in-memory, no network. It
 * records the request it saw and returns (or throws) whatever the test scripts.
 */
private class ScriptedVerifier(
    private val device: ThinQDevice,
    private val profile: ThinQDeviceProfile,
) : ThinQCredentialVerifier {

    /** The single scripted outcome; defaults to the configured device verified. */
    var result: ThinQVerificationResult = ThinQVerificationResult.Verified(device, profile)

    /** When set, [verify] throws it instead of returning [result]. */
    var failure: Throwable? = null

    var callCount: Int = 0
        private set

    var lastRequest: ThinQCredentialVerificationRequest? = null
        private set

    override suspend fun verify(request: ThinQCredentialVerificationRequest): ThinQVerificationResult {
        callCount += 1
        lastRequest = request
        failure?.let { throw it }
        return result
    }
}

/**
 * Decorating store that injects either a typed [SaveThinQResult.Failure] or a
 * thrown exception into [save] while delegating everything else to the in-memory
 * fake — used to exercise generic store-failure and save-cancellation paths
 * without a real platform store.
 */
private class FailOnSaveCredentialStore(
    private val delegate: FakeThinQCredentialStore,
    var failSaveWith: ThinQCredentialSaveFailure? = null,
    var throwOnSave: Throwable? = null,
) : ThinQCredentialStore by delegate {

    override suspend fun save(rawToken: String, accountCountry: String): SaveThinQResult {
        throwOnSave?.let { throw it }
        return failSaveWith?.let { SaveThinQResult.Failure(it) } ?: delegate.save(rawToken, accountCountry)
    }
}