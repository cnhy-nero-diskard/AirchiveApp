package cloud.airchive.personal.data.firebase

import cloud.airchive.personal.BuildConfig
import cloud.airchive.personal.data.tasks.awaitCancellable
import cloud.airchive.shared.contract.telemetry.AirchiveTelemetryPort
import cloud.airchive.shared.contract.telemetry.CollectorHealth
import cloud.airchive.shared.contract.telemetry.CollectorStatus
import cloud.airchive.shared.contract.telemetry.DailyTotal
import cloud.airchive.shared.contract.telemetry.DeviceIdentity
import cloud.airchive.shared.contract.telemetry.Observation
import cloud.airchive.shared.contract.telemetry.TelemetryPage
import cloud.airchive.shared.contract.telemetry.TelemetryReadFailure
import cloud.airchive.shared.data.telemetry.AirchiveReadPaths
import cloud.airchive.shared.data.telemetry.DEFAULT_DAY_TIME_ZONE
import cloud.airchive.shared.data.telemetry.TelemetryReadCursor
import cloud.airchive.shared.data.telemetry.TelemetryReadKind
import cloud.airchive.shared.data.telemetry.anchorField
import cloud.airchive.shared.data.telemetry.parseCollectorHealth
import cloud.airchive.shared.data.telemetry.readsAscending
import cloud.airchive.shared.data.telemetry.parseDailyTotal
import cloud.airchive.shared.data.telemetry.parseDeviceIdentity
import cloud.airchive.shared.data.telemetry.parseObservation
import com.google.android.gms.tasks.Task
import com.google.firebase.Timestamp
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.FirebaseUser
import com.google.firebase.firestore.DocumentSnapshot
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.FirebaseFirestoreException
import com.google.firebase.firestore.Query
import java.util.Date
import java.util.concurrent.CompletionException
import java.util.concurrent.ExecutionException
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Read-only [AirchiveTelemetryPort] backed by the injected [FirebaseAuth] and
 * [FirebaseFirestore] instances and the fixed, non-secret BuildConfig
 * project/device values. The only alternate project factory is an internal,
 * emulator-only `demo-*` project for local instrumentation tests.
 *
 * ## Read boundary
 *
 * - Only the four approved Firestore path shapes are ever touched, all derived
 *   from the single configured login-protected device id — no caller-supplied
 *   path or device input, no write method, no snapshot listener.
 * - Every read first requires a non-null [FirebaseAuth] current user. The UID
 *   is never accepted or injected: [FirebaseAuth] and [FirebaseFirestore] must
 *   belong to the **same** [com.google.firebase.FirebaseApp], so Firestore
 *   attaches that signed-in user's ID token to every read automatically.
 * - Observation pages are bounded `1..MAX_PAGE_SIZE` (250) and daily-total
 *   lists are bounded `1..MAX_TOTALS_LIMIT` (250); cursors resume stably. The
 *   two incremental mutation streams ([changedSincePersistedAt],
 *   [changedSinceReconciledAt]) follow the same rules: they filter/order by the
 *   respective server `Timestamp` field (`persistedAt` / `reconciledAt`) and
 *   resume exactly where the durable cursor anchored.
 *
 * ## Query / storage assumptions (fail-closed, documented)
 *
 * - The sibling collector (Python `firebase_admin`) stores its clocks as
 *   Firestore `Timestamp` values, so range/order queries compare against
 *   `Timestamp` bounds on `observedAt`. Documents that store epoch-millis
 *   numbers instead would not match these queries (local parse still handles
 *   both representations for docs that do arrive).
 * - `latestObservations` reads `observedAt >= since` newest-first and
  *   `observations` reads `(from, to)` oldest-first, each ordered by
  *   `observedAt`. The incremental streams read `persistedAt >= since` and
  *   `reconciledAt >= since` respectively, each ordered oldest-first by its own
  *   `Timestamp` field. On resume, the opaque timestamp+document-id cursor is
  *   rehydrated with one permitted telemetry-document GET and passed to
  *   `startAfter(DocumentSnapshot)`, which includes Firestore's implicit
  *   document-id tiebreaker without requiring a composite key index. This adds
  *   one bounded single-document read per resumed page; the anchor field is
  *   implied by the cursor kind (see [anchorField]), so a persisted-at cursor
  *   can never be resumed against an observed-at query.
  * - `dailyTotals` is ordered by the `localDate` ISO date field newest first;
  *   the sibling schema's default single-field index is sufficient.
 * - `persistedAt`/`reconciledAt` survive normalization and are the sort fields
 *   of the incremental streams (task 2.6); this adapter adds no writes or
 *   watchers.
 * - Firestore read failures are classified into the shared safe
 *   [TelemetryReadFailure] categories: `PERMISSION_DENIED`/`UNAUTHENTICATED`
 *   become [TelemetryReadFailure.AccessDenied], `UNAVAILABLE`/
 *   `DEADLINE_EXCEEDED` become [TelemetryReadFailure.Offline], and any other
 *   Firestore failure becomes [TelemetryReadFailure.RemoteFailure]. The
 *   original exception is kept only as the cause for in-process handling; the
 *   sync layer persists solely the stable code (task 2.9).
 *
 * Whole Firestore documents may carry `raw`/`state` payload blocks; this
 * adapter recursively normalizes only `Timestamp`/`Date` values to epoch
 * millis and then delegates to the shared parser whitelist, which never reads
 * `raw`/`state` — so no raw or state map ever reaches the caller.
 */
class FirebaseAirchiveTelemetry private constructor(
    private val auth: FirebaseAuth,
    private val firestore: FirebaseFirestore,
    config: ReadConfig,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) : AirchiveTelemetryPort {

    private val configuredProjectId: String = config.projectId.trim().also {
        require(it.isNotEmpty()) {
            "Configured Firebase project id must not be blank."
        }
    }

    private val configuredDeviceId: String = AirchiveReadPaths.validateDeviceSegment(config.deviceId.trim())
    private val dayTimeZone: String = config.dayTimeZone
    private val maxPageSize: Int = config.maxPageSize

    init {
        require(maxPageSize in 1..AirchiveTelemetryPort.MAX_PAGE_SIZE) {
            "maxPageSize must be in 1..${AirchiveTelemetryPort.MAX_PAGE_SIZE}, got $maxPageSize"
        }
        require(auth.app.name == firestore.app.name) {
            "FirebaseAuth (app '${auth.app.name}') and FirebaseFirestore (app '${firestore.app.name}') " +
                "must come from the same FirebaseApp so Firestore reads use the signed-in user's token."
        }
        val reportedProjectId = firestore.app.options.projectId
        require(reportedProjectId == configuredProjectId) {
            "Firestore instance reports project id '${reportedProjectId ?: "<unset>"}' but the configured " +
                "Airchive project id is '$configuredProjectId'. Register the app in the expected Firebase " +
                "project before reading."
        }
    }

    override suspend fun deviceIdentity(): DeviceIdentity = withContext(ioDispatcher) {
        requireSignedInUser()
        val path = AirchiveReadPaths.identityDocument(configuredDeviceId)
        val snapshot = firestore.document(path).get().awaitOrThrow()
        if (!snapshot.exists()) {
            throw IllegalStateException(
                "Device identity document not found at '$path'. The collector has not written it yet.",
            )
        }
        parseDeviceIdentity(snapshot.id, normalizeDocument(snapshot.data ?: emptyMap()))
    }

    override suspend fun latestObservations(
        sinceEpochMillis: Long,
        pageSize: Int,
        cursor: String?,
    ): TelemetryPage<Observation> = withContext(ioDispatcher) {
        requireSignedInUser()
        requirePageSize(pageSize)
        var query = observationQuery(TelemetryReadKind.NEWEST)
            .whereGreaterThanOrEqualTo(
                AirchiveReadPaths.FIELD_OBSERVED_AT,
                millisToTimestamp(sinceEpochMillis),
            )
            .limit(pageSize.toLong())
        query = applyCursor(query, cursor, TelemetryReadKind.NEWEST)
        readTelemetryPage(query, TelemetryReadKind.NEWEST, pageSize)
    }

    override suspend fun observations(
        fromEpochMillis: Long,
        toEpochMillis: Long,
        pageSize: Int,
        cursor: String?,
    ): TelemetryPage<Observation> = withContext(ioDispatcher) {
        requireSignedInUser()
        requirePageSize(pageSize)
        var query = observationQuery(TelemetryReadKind.OLDEST)
            .whereGreaterThan(
                AirchiveReadPaths.FIELD_OBSERVED_AT,
                millisToTimestamp(fromEpochMillis),
            )
            .whereLessThanOrEqualTo(
                AirchiveReadPaths.FIELD_OBSERVED_AT,
                millisToTimestamp(toEpochMillis),
            )
            .limit(pageSize.toLong())
        query = applyCursor(query, cursor, TelemetryReadKind.OLDEST)
        readTelemetryPage(query, TelemetryReadKind.OLDEST, pageSize)
    }

    override suspend fun changedSincePersistedAt(
        sinceEpochMillis: Long,
        pageSize: Int,
        cursor: String?,
    ): TelemetryPage<Observation> = withContext(ioDispatcher) {
        requireSignedInUser()
        requirePageSize(pageSize)
        var query = firestore
            .collection(AirchiveReadPaths.telemetryCollection(configuredDeviceId))
            .whereGreaterThanOrEqualTo(
                AirchiveReadPaths.FIELD_PERSISTED_AT,
                millisToTimestamp(sinceEpochMillis),
            )
            .orderBy(AirchiveReadPaths.FIELD_PERSISTED_AT, Query.Direction.ASCENDING)
            .limit(pageSize.toLong())
        query = applyCursor(query, cursor, TelemetryReadKind.PERSISTED_AT)
        readTelemetryPage(query, TelemetryReadKind.PERSISTED_AT, pageSize)
    }

    override suspend fun changedSinceReconciledAt(
        sinceEpochMillis: Long,
        pageSize: Int,
        cursor: String?,
    ): TelemetryPage<Observation> = withContext(ioDispatcher) {
        requireSignedInUser()
        requirePageSize(pageSize)
        var query = firestore
            .collection(AirchiveReadPaths.telemetryCollection(configuredDeviceId))
            .whereGreaterThanOrEqualTo(
                AirchiveReadPaths.FIELD_RECONCILED_AT,
                millisToTimestamp(sinceEpochMillis),
            )
            .orderBy(AirchiveReadPaths.FIELD_RECONCILED_AT, Query.Direction.ASCENDING)
            .limit(pageSize.toLong())
        query = applyCursor(query, cursor, TelemetryReadKind.RECONCILED_AT)
        readTelemetryPage(query, TelemetryReadKind.RECONCILED_AT, pageSize)
    }

    override suspend fun latestFinalizedTotals(limit: Int): List<DailyTotal> = withContext(ioDispatcher) {
        requireSignedInUser()
        AirchiveTelemetryPort.requireValidTotalsLimit(limit)
        require(limit <= maxPageSize) {
            "Daily-total list limit must not exceed configured page size $maxPageSize."
        }
        val snapshot = firestore
            .collection(AirchiveReadPaths.dailyTotalsCollection(configuredDeviceId))
            .orderBy(FIELD_LOCAL_DATE, Query.Direction.DESCENDING)
            .limit(limit.toLong())
            .get()
            .awaitOrThrow()
        snapshot.documents.map { document ->
            parseDailyTotal(document.id, normalizeDocument(document.data ?: emptyMap()), dayTimeZone)
        }
    }

    override suspend fun collectorHealth(): CollectorHealth = withContext(ioDispatcher) {
        requireSignedInUser()
        val snapshot = firestore
            .document(AirchiveReadPaths.collectorHealthDocument(configuredDeviceId))
            .get()
            .awaitOrThrow()
        if (!snapshot.exists()) {
            // No runtime doc yet: an honest "unknown" heartbeat, not a fabricated one.
            return@withContext CollectorHealth(status = CollectorStatus.UNKNOWN)
        }
        parseCollectorHealth(normalizeDocument(snapshot.data ?: emptyMap()))
    }

    // --- private helpers ---------------------------------------------------

    private fun observationQuery(kind: TelemetryReadKind): Query {
        val direction = if (readsAscending(kind)) {
            Query.Direction.ASCENDING
        } else {
            Query.Direction.DESCENDING
        }
        return firestore
            .collection(AirchiveReadPaths.telemetryCollection(configuredDeviceId))
            .orderBy(AirchiveReadPaths.FIELD_OBSERVED_AT, direction)
    }

    /** Runs one bounded query and builds its [TelemetryPage], encoding the durable cursor. */
    private suspend fun readTelemetryPage(
        query: Query,
        kind: TelemetryReadKind,
        pageSize: Int,
    ): TelemetryPage<Observation> {
        val snapshot = query.get().awaitOrThrow()
        val items = snapshot.documents.map { document ->
            parseObservation(document.id, normalizeDocument(document.data ?: emptyMap()), dayTimeZone)
        }
        return TelemetryPage(
            items = items,
            nextCursor = if (items.size == pageSize) {
                snapshot.documents.lastOrNull()?.let { encodeCursor(kind, it) }
            } else {
                null
            },
        )
    }

    private suspend fun applyCursor(query: Query, cursor: String?, expectedKind: TelemetryReadKind): Query {
        if (cursor == null) return query
        val decoded = TelemetryReadCursor.decode(cursor)
        require(decoded.kind == expectedKind) {
            "Cursor '$cursor' is for the ${decoded.kind} read, not the requested $expectedKind read."
        }
        val anchorFieldName = anchorField(decoded.kind)
        val cursorPath = AirchiveReadPaths.telemetryDocument(configuredDeviceId, decoded.documentId)
        val anchor = firestore.document(cursorPath).get().awaitOrThrow()
        require(anchor.exists()) {
            "Telemetry cursor anchor '${decoded.documentId}' no longer exists; restart the bounded read."
        }
        val actualTimestamp = requireNotNull(anchor.getTimestamp(anchorFieldName)) {
            "Telemetry cursor anchor '${decoded.documentId}' has no '$anchorFieldName' timestamp."
        }
        require(actualTimestamp.seconds == decoded.sortSeconds && actualTimestamp.nanoseconds == decoded.sortNanos) {
            "Telemetry cursor anchor '${decoded.documentId}' changed; restart the bounded read."
        }
        return query.startAfter(anchor)
    }

    private fun encodeCursor(kind: TelemetryReadKind, last: DocumentSnapshot): String {
        val anchorFieldName = anchorField(kind)
        val timestamp = requireNotNull(last.getTimestamp(anchorFieldName)) {
            "Observation document '${last.id}' was returned by an '$anchorFieldName' ordered query " +
                "but has no '$anchorFieldName' timestamp."
        }
        return TelemetryReadCursor(
            kind = kind,
            sortSeconds = timestamp.seconds,
            sortNanos = timestamp.nanoseconds,
            documentId = last.id,
        ).encode()
    }

    private fun requireSignedInUser(): FirebaseUser =
        auth.currentUser
            ?: throw IllegalStateException(
                "No signed-in Firebase user: Airchive reads require the owner's Google sign-in " +
                    "(task 3.1 owns interactive sign-in).",
            )

    private fun requirePageSize(pageSize: Int) {
        AirchiveTelemetryPort.requireValidPageSize(pageSize)
        require(pageSize <= maxPageSize) {
            "Firestore page size must not exceed configured maximum $maxPageSize."
        }
    }

    /** Recursively converts Firestore `Timestamp`/`Date` values to epoch millis. */
    private fun normalizeDocument(data: Map<String, Any?>): Map<String, Any?> =
        data.mapValues { (_, value) -> normalizeValue(value) }

    private fun normalizeValue(value: Any?): Any? = when (value) {
        is Timestamp -> value.seconds * MILLIS_PER_SECOND + value.nanoseconds / NANOS_PER_MILLI
        is Date -> value.time
        is Map<*, *> -> value.entries.associate { (key, entry) -> key.toString() to normalizeValue(entry) }
        is List<*> -> value.map { normalizeValue(it) }
        else -> value
    }

    private fun millisToTimestamp(millis: Long): Timestamp {
        val seconds = Math.floorDiv(millis, MILLIS_PER_SECOND)
        val nanosInMillis = Math.floorMod(millis, MILLIS_PER_SECOND)
        return Timestamp(seconds, nanosInMillis.toInt() * NANOS_PER_MILLI)
    }

    /** Cancellable, nonblocking wait that preserves safe Firestore failure classification. */
    private suspend fun <T> Task<T>.awaitOrThrow(): T =
        try {
            awaitCancellable()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            throw classifyFirestoreFailure(failure)
        }

    /**
     * Classifies a failed Firestore read into the shared, safe
     * [TelemetryReadFailure] category used for sync-error persistence:
     *
     *  - `PERMISSION_DENIED` / `UNAUTHENTICATED` -> [TelemetryReadFailure.AccessDenied]
     *  - `UNAVAILABLE` / `DEADLINE_EXCEEDED` -> [TelemetryReadFailure.Offline]
     *  - any other Firestore failure -> [TelemetryReadFailure.RemoteFailure]
     *
     * The original adapter exception is preserved only as the typed failure's
     * in-process cause so live handlers can still recover it; the sync layer
     * persists only the stable code, never a raw message. Nested task-wrapping
     * exceptions are unwrapped first. Non-Firestore failures (adapter logic
     * errors, cancellations, parser failures) pass through untouched.
     */
    private fun classifyFirestoreFailure(error: Throwable): Throwable {
        var current = error
        while (
            (current is ExecutionException || current is CompletionException) &&
            current.cause != null
        ) {
            current = current.cause!!
        }
        return when (current) {
            is TelemetryReadFailure -> current
            is FirebaseFirestoreException -> when (current.code) {
                FirebaseFirestoreException.Code.PERMISSION_DENIED,
                FirebaseFirestoreException.Code.UNAUTHENTICATED,
                -> TelemetryReadFailure.AccessDenied(current)
                FirebaseFirestoreException.Code.UNAVAILABLE,
                FirebaseFirestoreException.Code.DEADLINE_EXCEEDED,
                -> TelemetryReadFailure.Offline(current)
                else -> TelemetryReadFailure.RemoteFailure(current)
            }
            else -> current
        }
    }

    companion object {
        /** Demo project used only by the local Firebase Emulator Suite. */
        internal const val EMULATOR_TEST_PROJECT_ID: String = "demo-airchive-telemetry-poller"

        private const val FIELD_LOCAL_DATE = "localDate"
        private const val MILLIS_PER_SECOND = 1_000L
        private const val NANOS_PER_MILLI = 1_000_000

        /**
         * Builds the adapter from the non-secret BuildConfig project/device
         * values only — no caller-supplied path or device input.
         */
        fun fromBuildConfig(
            auth: FirebaseAuth,
            firestore: FirebaseFirestore,
            ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
        ): FirebaseAirchiveTelemetry = FirebaseAirchiveTelemetry(
            auth = auth,
            firestore = firestore,
            config = ReadConfig(
                projectId = BuildConfig.AIRCHIVE_FIREBASE_PROJECT_ID,
                deviceId = BuildConfig.AIRCHIVE_DEVICE_ID,
                dayTimeZone = BuildConfig.AIRCHIVE_DAY_TIME_ZONE,
                maxPageSize = BuildConfig.AIRCHIVE_FIRESTORE_MAX_PAGE_SIZE,
            ),
            ioDispatcher = ioDispatcher,
        )

        /**
         * Emulator-only fixture configuration. The project id is always
         * `demo-*`, while the path remains pinned to the one configured device.
         */
        internal fun forEmulatorTest(
            auth: FirebaseAuth,
            firestore: FirebaseFirestore,
            ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
        ): FirebaseAirchiveTelemetry = FirebaseAirchiveTelemetry(
            auth = auth,
            firestore = firestore,
            config = ReadConfig(
                projectId = EMULATOR_TEST_PROJECT_ID,
                deviceId = BuildConfig.AIRCHIVE_DEVICE_ID,
                dayTimeZone = BuildConfig.AIRCHIVE_DAY_TIME_ZONE,
                maxPageSize = BuildConfig.AIRCHIVE_FIRESTORE_MAX_PAGE_SIZE,
            ),
            ioDispatcher = ioDispatcher,
        )
    }
}

private data class ReadConfig(
    val projectId: String,
    val deviceId: String,
    val dayTimeZone: String,
    val maxPageSize: Int,
)
