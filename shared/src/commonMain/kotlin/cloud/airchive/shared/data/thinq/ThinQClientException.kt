package cloud.airchive.shared.data.thinq

/**
 * Classified exceptions raised by [KtorThinQClient] for failed reads.
 *
 * Every subtype carries only a fixed, code-literal message (see
 * [remoteErrorMessage]) — a `toString()` can never include a raw PAT, a request
 * header, or LG's raw server error text. The transport and parse failures have
 * their own fixed messages too.
 *
 * Command failures never throw these to the caller; [KtorThinQClient.setState]
 * converts them into a typed [cloud.airchive.shared.contract.thinq.CommandOutcome]
 * with a fixed, secret-free message.
 */
sealed class ThinQClientException(message: String) : Exception(message) {

    /** No stored token, or the stored country is unset. */
    data object MissingCredential : ThinQClientException("ThinQ credential is absent.")

    /** A locally invalid token/client header was rejected before any network request. */
    data object InvalidCredential : ThinQClientException("ThinQ credential or client identifier is invalid.")

    /** The stored/candidate country is not in the SDK's routing table. */
    data object UnsupportedCountry : ThinQClientException("ThinQ account country is unsupported for routing.")

    /** The transport itself failed (timeout, DNS, connect, read). */
    data object Network : ThinQClientException("ThinQ could not be reached.")

    /** Ktor's bounded request deadline elapsed. */
    data object Timeout : ThinQClientException("ThinQ request timed out.")

    /** LG answered but the body was not the expected JSON shape. */
    data object MalformedResponse : ThinQClientException("ThinQ returned a response that could not be parsed.")

    /**
     * LG answered with an error status. [error] carries only the classified
     * code semantics; the raw `payload.error.message` text is never retained or
     * stringified here.
     */
    /**
     * A classified LG error with a public, stable category for client-facing
     * handling. The SDK's raw message and response headers are never retained.
     */
    class RemoteFailure internal constructor(
        internal val error: ThinQRemoteError,
    ) : ThinQClientException(remoteErrorMessage(error)) {
        /** Safe read/command category available to callers outside the shared module. */
        val kind: ThinQReadFailure = error.toPublicFailure()
    }
}

/** Stable, sanitized ThinQ failure categories exposed to app/UI callers. */
enum class ThinQReadFailure {
    INVALID_TOKEN,
    WRONG_ACCOUNT,
    DEVICE_UNAVAILABLE,
    RATE_LIMITED,
    NO_CONTROL_AUTHORITY,
    TRANSIENT,
    INVALID_REQUEST,
    UNKNOWN,
}
