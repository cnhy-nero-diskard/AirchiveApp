package cloud.airchive.shared.fakes

import cloud.airchive.shared.contract.credentials.SaveThinQResult
import cloud.airchive.shared.contract.credentials.ThinQCredential
import cloud.airchive.shared.contract.credentials.ThinQCredentialSaveFailure
import cloud.airchive.shared.contract.credentials.ThinQCredentialState
import cloud.airchive.shared.contract.credentials.ThinQCredentialStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map

/**
 * Deterministic, in-memory ThinQ credential store. The token lives only in
 * process, is never a real credential, and never appears in plaintext in the
 * observable state (only a masked form). Set [keyUnavailable] to simulate a
 * lost platform-protected key, which routes the owner back through setup
 * without exposing ciphertext.
 */
class FakeThinQCredentialStore(
    private val initialClientId: String = "fake-lg-client-id",
) : ThinQCredentialStore {

    private var rawToken: String? = null
    private var rawOwnerUid: String? = null

    /** Simulates the encrypted store being unable to decrypt the saved token. */
    var keyUnavailable: Boolean = false

    private val state = MutableStateFlow(initialState())

    override val clientId: String get() = initialClientId

    override fun observe(ownerUid: String?): Flow<ThinQCredentialState> =
        state.asStateFlow().map { current ->
            if (ownerUid == null || rawToken != null && rawOwnerUid == ownerUid) current else initialState()
        }

    override suspend fun save(rawToken: String, accountCountry: String, ownerUid: String?): SaveThinQResult {
        if (keyUnavailable) return SaveThinQResult.Failure(ThinQCredentialSaveFailure.KEY_UNAVAILABLE)
        if (rawToken.isBlank() || accountCountry.isBlank()) {
            return SaveThinQResult.Failure(ThinQCredentialSaveFailure.INVALID_INPUT)
        }
        this.rawToken = rawToken
        this.rawOwnerUid = ownerUid
        state.value = ThinQCredentialState(
            present = true,
            clientId = clientId,
            credential = ThinQCredential(
                maskedToken = mask(rawToken),
                accountCountry = accountCountry,
                createdAtEpochMillis = FAKE_EPOCH_MILLIS,
            ),
            unlockFailed = false,
        )
        return SaveThinQResult.Success
    }

    override suspend fun readToken(ownerUid: String?): String? {
        if (keyUnavailable) return null
        // An omitted owner is an in-memory test inspection, not an app access
        // path. Production callers always supply the active Firebase UID.
        if (ownerUid == null) return rawToken
        if (rawToken != null && rawOwnerUid != ownerUid) {
            rawToken = null
            rawOwnerUid = null
            state.value = initialState()
            return null
        }
        return rawToken
    }

    override suspend fun migrateLegacyCredential(ownerUid: String): Boolean {
        if (keyUnavailable || ownerUid.isBlank() || rawToken == null) return false
        if (rawOwnerUid != null && rawOwnerUid != ownerUid) {
            rawToken = null
            rawOwnerUid = null
            state.value = initialState()
            return false
        }
        rawOwnerUid = ownerUid
        state.value = state.value.copy(present = true, credential = state.value.credential)
        return true
    }

    override suspend fun clear() {
        rawToken = null
        rawOwnerUid = null
        state.value = initialState()
    }

    private fun initialState() = ThinQCredentialState(
        present = false,
        clientId = clientId,
        credential = null,
        unlockFailed = false,
    ).let { if (keyUnavailable) it.copy(unlockFailed = true) else it }

    private fun mask(token: String): String = "\u2022\u2022\u2022\u2022 " + token.takeLast(4)
}
