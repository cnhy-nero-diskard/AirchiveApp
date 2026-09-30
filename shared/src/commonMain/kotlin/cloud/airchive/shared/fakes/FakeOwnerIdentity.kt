package cloud.airchive.shared.fakes

import cloud.airchive.shared.contract.owner.OwnerIdentityPort
import cloud.airchive.shared.contract.owner.OwnerSession
import cloud.airchive.shared.contract.owner.SignInFailure
import cloud.airchive.shared.contract.owner.SignInResult
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Deterministic in-memory owner identity: sign-in always returns [fixedSession]
 * and sign-out clears it. Requires no Google account, network, or Firebase.
 * No real credentials anywhere.
 */
class FakeOwnerIdentity(
    private val fixedSession: OwnerSession = DEFAULT_SESSION,
    private val failWith: SignInFailure? = null,
) : OwnerIdentityPort {

    private val session = MutableStateFlow<OwnerSession?>(null)

    override val currentSession: OwnerSession? get() = session.value

    override fun observeSession(): Flow<OwnerSession?> = session.asStateFlow()

    override suspend fun signIn(): SignInResult {
        if (failWith != null) return SignInResult.Failure(failWith)
        session.value = fixedSession
        return SignInResult.Success(fixedSession)
    }

    override suspend fun signOut() {
        session.value = null
    }

    companion object {
        val DEFAULT_SESSION = OwnerSession(
            uid = "fake-owner-uid-000",
            displayName = "Fake Owner",
            email = "owner@example.invalid",
            signedInAtEpochMillis = FAKE_EPOCH_MILLIS,
        )
    }
}

/** Shared deterministic clock baseline for fakes that need an "as of now". */
internal const val FAKE_EPOCH_MILLIS = 1_750_000_000_000L