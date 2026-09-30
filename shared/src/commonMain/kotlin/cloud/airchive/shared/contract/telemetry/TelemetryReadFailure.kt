package cloud.airchive.shared.contract.telemetry

import kotlin.coroutines.cancellation.CancellationException

/**
 * Typed, **safe** classification of a failed Airchive telemetry read (task
 * 2.9 sync-error persistence).
 *
 * Only the stable [code] is ever durable — a sync layer persists exactly
 * `code` into `SyncMetadata.lastError` and never the raw exception message, so
 * an adapter's verbose or security-relevant message cannot leak into the local
 * store or be rendered as history text. The categories the product surface
 * cares about are distinguished explicitly:
 *
 *  - [AccessDenied] — the owner's Firestore read was denied (rules rejection or
 *    an unauthenticated session). The UI shows the cached history with an
 *    access error and a retry, never a crash.
 *  - [Offline] — the backend was unreachable or the read timed out. The UI
 *    shows the cached history with an offline/stale label.
 *  - [RemoteFailure] — any other backend/transport failure, the safe default
 *    when an unexpected failure has no specific category.
 *
 * The Android adapter preserves the original adapter/platform exception only as
 * the in-process [Throwable.cause] so live exception handling can still surface
 * the real failure; persistence and rendering never read it.
 *
 * A [CancellationException] is deliberately **not** a read failure: coroutine
 * cancellation must propagate untouched and must never be recorded as a sync
 * error. [classify] rethrows it defensively, and the sync coordinators also
 * short-circuit it before persisting anything.
 */
sealed class TelemetryReadFailure(
    /** Stable, safe machine-readable error code persisted in sync metadata. */
    val code: String,
    message: String,
    cause: Throwable? = null,
) : Exception(message, cause) {

    /**
     * The owner's read was rejected. The original denied exception (when the
     * adapter supplied one) is kept only as [Throwable.cause] for in-process
     * handling; the persisted code stays the stable `ACCESS_DENIED`.
     */
    class AccessDenied(cause: Throwable? = null) : TelemetryReadFailure(
        code = "ACCESS_DENIED",
        message = "ACCESS_DENIED: Airchive telemetry read was denied (permission denied).",
        cause = cause,
    )

    /**
     * The backend was unreachable or a read exceeded its deadline. The original
     * unavailable/timed-out exception is kept only as [Throwable.cause] for
     * in-process handling; the persisted code stays the stable `OFFLINE`.
     */
    class Offline(cause: Throwable? = null) : TelemetryReadFailure(
        code = "OFFLINE",
        message = "OFFLINE: Airchive telemetry is unreachable; the device appears offline.",
        cause = cause,
    )

    /**
     * Any other backend/transport read failure. This is the safe default for an
     * unexpected failure; the persisted code stays the stable `REMOTE_FAILURE`.
     */
    class RemoteFailure(cause: Throwable? = null) : TelemetryReadFailure(
        code = "REMOTE_FAILURE",
        message = "REMOTE_FAILURE: the Airchive telemetry read failed.",
        cause = cause,
    )

    companion object {
        /**
         * Classifies a caught failure into the safe [TelemetryReadFailure]
         * category used for sync-error persistence.
         *
         * An already-typed [TelemetryReadFailure] passes through unchanged (the
         * adapter's Firestore-code mapping decides the category); anything else
         * defaults to [RemoteFailure] so an unexpected failure still leaves an
         * actionable, stable error code instead of a raw message. A
         * [CancellationException] is rethrown and never classified, because
         * cancellation is not a sync failure.
         */
        fun classify(cause: Throwable): TelemetryReadFailure = when (cause) {
            is CancellationException -> throw cause
            is TelemetryReadFailure -> cause
            else -> RemoteFailure(cause)
        }
    }
}