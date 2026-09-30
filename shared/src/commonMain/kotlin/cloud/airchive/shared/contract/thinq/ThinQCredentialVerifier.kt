package cloud.airchive.shared.contract.thinq

/**
 * Verifies a LG ThinQ Personal Access Token against the exactly-once configured
 * AC before the token is committed to protected storage.
 *
 * This contract is the adapter boundary the task-4.1 Ktor client will
 * implement: an adapter receives the plaintext token, the normalized 2-letter
 * account country, the stable per-install client ID, and the expected device
 * ID, reads LG's device list and then the target device's profile, and
 * classifies the outcome. Callers persist the token only for [Verified].
 *
 * ## Successful identity/profile reads do NOT prove command/control authority
 *
 * A [Verified] outcome proves only that the token can *read* the account's
 * device identity and profile. It never proves the token holds command/control
 * permission: a read-capable token still needs a separate, deliberately
 * verified control-capable PAT, and LG reports a missing control authority on
 * the first actual command. Callers MUST NOT label this result — or any
 * coordinator success derived from it — as proof that commands will be
 * accepted. A profile's writable capabilities are a model question; whether
 * this token is permitted to write is answered by LG at command time.
 *
 * ## Secrets stay out of results, `toString()`, and logs
 *
 * [ThinQCredentialVerificationRequest.rawToken] is the caller's plaintext PAT.
 * Adapters must never echo it into a result, an exception, a log line, or an
 * exported diagnostic. The request's own `toString()` is deliberately masked so
 * recorded representations never leak the token.
 */
interface ThinQCredentialVerifier {

    /**
     * Attempts to read the configured AC with the presented token. The adapter
     * drives device-list and profile reads through the narrow [ThinQPort] and
     * maps routing/error classification onto exactly one [ThinQVerificationResult].
     */
    suspend fun verify(request: ThinQCredentialVerificationRequest): ThinQVerificationResult
}

/**
 * One verification attempt: the [rawToken] to test, its normalized account
 * [accountCountry] (upper-case two letters), the stable per-install [clientId],
 * and the configured [expectedDeviceId] the adapter must match.
 *
 * The plaintext [rawToken] is the caller's PAT; keep it out of logs and rely on
 * the masked `toString()` for any recorded representation.
 */
data class ThinQCredentialVerificationRequest(
    val rawToken: String,
    val accountCountry: String,
    val clientId: String,
    val expectedDeviceId: String,
) {
    override fun toString(): String =
        "ThinQCredentialVerificationRequest(" +
            "accountCountry=$accountCountry, clientId=$clientId, " +
            "expectedDeviceId=$expectedDeviceId, tokenMask=${maskToken(rawToken)})"

    private companion object {
        private const val MASK = "\u2022\u2022\u2022\u2022"

        private fun maskToken(token: String): String =
            if (token.isBlank()) "[absent]" else "$MASK ${token.takeLast(4)}"
    }
}

/**
 * Typed outcome of one verification attempt. [Verified] carries the matched
 * device and its read profile — and nothing else; see [ThinQCredentialVerifier]
 * for why a verified read is not a command/control-authority proof.
 */
sealed interface ThinQVerificationResult {

    /** The adapter found exactly the configured device and read its profile. */
    data class Verified(
        val device: ThinQDevice,
        val profile: ThinQDeviceProfile,
    ) : ThinQVerificationResult

    /** LG rejected the presented token outright. */
    data object InvalidToken : ThinQVerificationResult

    /** The token is valid but belongs to an account that cannot reach the configured device. */
    data object WrongAccount : ThinQVerificationResult

    /** The configured AC is absent or unreachable in this account. */
    data object DeviceUnavailable : ThinQVerificationResult

    /** LG could not be reached; nothing can be concluded about the token. */
    data object Network : ThinQVerificationResult

    /** An unexpected remote-side failure; nothing can be concluded about the token. */
    data object RemoteFailure : ThinQVerificationResult
}