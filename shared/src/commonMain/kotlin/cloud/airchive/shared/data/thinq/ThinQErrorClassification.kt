package cloud.airchive.shared.data.thinq

import cloud.airchive.shared.contract.thinq.CommandFailure
import cloud.airchive.shared.contract.thinq.ThinQVerificationResult
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * Classified LG ThinQ Connect API error, derived from the numeric `payload.error.code`
 * the SDK surfaces (`thinq_api.py` → `ThinQAPIErrorCodes`). The classification is the
 * single place that turns a raw code into a typed outcome; the raw `code` is public
 * SDK semantics and never contains a secret, but the raw `message` from LG is
 * deliberately discarded here and never surfaces anywhere.
 *
 * The mappings follow the task's verified contract:
 * - [InvalidToken]: 1103, 1218
 * - [WrongAccount]: 1205, 1211, 1212, 1213, 1224 (device not reachable in this account/config)
 * - [DeviceUnavailable]: 1222
 * - [RateLimited]: 1305, 1306, 1309
 * - [NoControlAuthority]: 1308
 * - [Transient]: 2000, 2209, 2210, 2212, 2214
 * - [InvalidRequest]: 2207/23xx only on the command path (state-rejected command)
 * - [MalformedOrUnknown]: everything else, including unparseable error bodies
 */
internal sealed interface ThinQRemoteError {
    data object InvalidToken : ThinQRemoteError
    data object WrongAccount : ThinQRemoteError
    data object DeviceUnavailable : ThinQRemoteError
    data object RateLimited : ThinQRemoteError
    data object NoControlAuthority : ThinQRemoteError
    data object Transient : ThinQRemoteError
    data object InvalidRequest : ThinQRemoteError

    /** Malformed JSON error body, transport-unrelated oddities, or an unmapped code. */
    data object MalformedOrUnknown : ThinQRemoteError
}

/**
 * Classifies one ThinQ error code. `isCommand` widens the classification for the
 * control path where LG reports "command not supported in current state" as a
 * legitimate local [ThinQRemoteError.InvalidRequest] rejection (2207 INVALID_COMMAND_ERROR,
 * 2301..2305 COMMAND_NOT_SUPPORTED_*); on read paths those stay unknown.
 */
internal fun classifyErrorCode(code: String?, isCommand: Boolean): ThinQRemoteError {
    if (code == null) return ThinQRemoteError.MalformedOrUnknown
    return when (code) {
        "1103", "1218" -> ThinQRemoteError.InvalidToken
        "1205", "1211", "1212", "1213", "1224" -> ThinQRemoteError.WrongAccount
        "1222" -> ThinQRemoteError.DeviceUnavailable
        "1305", "1306", "1309" -> ThinQRemoteError.RateLimited
        "1308" -> ThinQRemoteError.NoControlAuthority
        "2000", "2209", "2210", "2212", "2214" -> ThinQRemoteError.Transient
        "2207", "2301", "2302", "2303", "2304", "2305" ->
            if (isCommand) ThinQRemoteError.InvalidRequest else ThinQRemoteError.MalformedOrUnknown

        else -> ThinQRemoteError.MalformedOrUnknown
    }
}

/**
 * Reads `payload.error.code` from an error-status body and classifies it.
 * The `message` field (and any raw server text) is discarded on purpose so it
 * can never leak into an exception or outcome. An unparseable body classifies
 * as [ThinQRemoteError.MalformedOrUnknown].
 */
internal fun parseRemoteError(
    text: String,
    isCommand: Boolean,
    httpStatus: Int? = null,
): ThinQRemoteError {
    val code = try {
        val root = Json.parseToJsonElement(text) as? JsonObject
        val error = root?.get("error") as? JsonObject
        (error?.get("code") as? JsonPrimitive)?.content
    } catch (_: Exception) {
        null
    }
    val trimmed = code?.trim()?.takeIf { it.isNotEmpty() }
    if (trimmed == null && httpStatus == 429) return ThinQRemoteError.RateLimited
    if (trimmed == null && httpStatus in 500..599) return ThinQRemoteError.Transient
    return classifyErrorCode(trimmed, isCommand = isCommand)
}

/**
 * Fixed, secret-free human phrase for one classification. These literals are the
 * only textual content a [ThinQClientException.RemoteFailure] ever carries, so
 * its `toString()` can never contain the raw PAT or LG's raw error text.
 */
internal fun remoteErrorMessage(error: ThinQRemoteError): String = when (error) {
    ThinQRemoteError.InvalidToken, ThinQRemoteError.WrongAccount ->
        "ThinQ rejected the request token or account."

    ThinQRemoteError.DeviceUnavailable ->
        "ThinQ reports the configured device unavailable or offline."

    ThinQRemoteError.RateLimited ->
        "ThinQ is rate-limiting requests."

    ThinQRemoteError.NoControlAuthority ->
        "ThinQ denies control authority for this token."

    ThinQRemoteError.Transient ->
        "ThinQ returned a transient error."

    ThinQRemoteError.InvalidRequest ->
        "ThinQ rejected the requested command for the device's current state."

    ThinQRemoteError.MalformedOrUnknown ->
        "ThinQ returned an unexpected error."
}

/** Command-path projection of a remote classification onto [CommandFailure]. */
internal fun ThinQRemoteError.toCommandFailure(): CommandFailure = when (this) {
    ThinQRemoteError.InvalidToken, ThinQRemoteError.WrongAccount -> CommandFailure.TOKEN_REJECTED
    ThinQRemoteError.DeviceUnavailable -> CommandFailure.DEVICE_UNAVAILABLE
    ThinQRemoteError.RateLimited -> CommandFailure.RATE_LIMITED
    ThinQRemoteError.NoControlAuthority -> CommandFailure.NO_CONTROL_AUTHORITY
    ThinQRemoteError.InvalidRequest -> CommandFailure.INVALID_REQUEST
    ThinQRemoteError.Transient, ThinQRemoteError.MalformedOrUnknown -> CommandFailure.UNKNOWN
}

/** Public, stable projection for app callers handling failed reads. */
internal fun ThinQRemoteError.toPublicFailure(): ThinQReadFailure = when (this) {
    ThinQRemoteError.InvalidToken -> ThinQReadFailure.INVALID_TOKEN
    ThinQRemoteError.WrongAccount -> ThinQReadFailure.WRONG_ACCOUNT
    ThinQRemoteError.DeviceUnavailable -> ThinQReadFailure.DEVICE_UNAVAILABLE
    ThinQRemoteError.RateLimited -> ThinQReadFailure.RATE_LIMITED
    ThinQRemoteError.NoControlAuthority -> ThinQReadFailure.NO_CONTROL_AUTHORITY
    ThinQRemoteError.Transient -> ThinQReadFailure.TRANSIENT
    ThinQRemoteError.InvalidRequest -> ThinQReadFailure.INVALID_REQUEST
    ThinQRemoteError.MalformedOrUnknown -> ThinQReadFailure.UNKNOWN
}

/** Verification-path projection of a remote classification onto [ThinQVerificationResult]. */
internal fun ThinQRemoteError.toVerificationResult(): ThinQVerificationResult = when (this) {
    ThinQRemoteError.InvalidToken -> ThinQVerificationResult.InvalidToken
    ThinQRemoteError.WrongAccount -> ThinQVerificationResult.WrongAccount
    ThinQRemoteError.DeviceUnavailable -> ThinQVerificationResult.DeviceUnavailable
    else -> ThinQVerificationResult.RemoteFailure
}
