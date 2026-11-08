package cloud.airchive.shared.contract.credentials

import kotlinx.coroutines.flow.Flow

/**
 * Protected storage for the LG ThinQ Personal Access Token and the stable
 * per-install LG client ID.
 *
 * The Android adapter encrypts the token at rest with Android Keystore. The iOS
 * target must provide a Keychain-backed adapter before production use. This
 * interface never leaks the plaintext token to the UI: state carries only a
 * masked form.
 * When the protected key can no longer decrypt a saved token ([unlockFailed]),
 * the owner is routed back through ThinQ setup instead of seeing ciphertext.
 *
 * The in-memory [FakeThinQCredentialStore] keeps everything in process and uses
 * no real credentials.
 */
interface ThinQCredentialStore {

    /** Stable per-install LG client ID, generated once and persisted forever. */
    val clientId: String

    /** Emits masked credential status and every later change. */
    fun observe(ownerUid: String? = null): Flow<ThinQCredentialState>

    /**
     * Encrypts and stores a new token, replacing any previous one on success.
     * A failed save leaves the last valid token untouched.
     */
    suspend fun save(
        rawToken: String,
        accountCountry: String,
        ownerUid: String? = null,
    ): SaveThinQResult

    /** Plaintext token for the narrow LG client only; `null` when absent, undecryptable, or owned by someone else. */
    suspend fun readToken(ownerUid: String? = null): String?

    /**
     * Binds a pre-owner-binding credential to [ownerUid] after that owner has
     * passed the app's access check. Returns false when no usable matching
     * credential remains; never returns the token.
     */
    suspend fun migrateLegacyCredential(ownerUid: String): Boolean

    /**
     * Removes the stored token and its platform key (used on confirmed
     * removal/sign-out/account change). Throws if protected material remains so
     * callers can keep the current owner session and ask the owner to retry.
     */
    suspend fun clear()
}

/** Masked view of the stored credential, safe for Settings/UI. */
data class ThinQCredential(
    /** Example: `•••• a3f9`. Never the plaintext. */
    val maskedToken: String,
    /** Account country preset (`PH`), editable at onboarding. */
    val accountCountry: String,
    val createdAtEpochMillis: Long,
)

/**
 * Observable credential status. [unlockFailed] is set when the platform can no
 * longer decrypt the saved token; the app then requests ThinQ setup again
 * without crashing or exposing ciphertext.
 */
data class ThinQCredentialState(
    val present: Boolean,
    val clientId: String,
    val credential: ThinQCredential? = null,
    val unlockFailed: Boolean = false,
)

sealed interface SaveThinQResult {
    data object Success : SaveThinQResult
    data class Failure(val reason: ThinQCredentialSaveFailure) : SaveThinQResult
}

enum class ThinQCredentialSaveFailure {
    /** Platform-protected key unavailable; nothing was replaced. */
    KEY_UNAVAILABLE,

    /** Blank token or country. */
    INVALID_INPUT,

    UNKNOWN,
}
