package cloud.airchive.shared.fakes

import cloud.airchive.shared.contract.telemetry.AirchiveTelemetryPort
import cloud.airchive.shared.contract.telemetry.CollectorHealth
import cloud.airchive.shared.contract.telemetry.CollectorStatus
import cloud.airchive.shared.contract.telemetry.DailyTotal
import cloud.airchive.shared.contract.telemetry.DailyTotalSource
import cloud.airchive.shared.contract.telemetry.DeviceIdentity
import cloud.airchive.shared.contract.telemetry.EnergyUnit
import cloud.airchive.shared.contract.telemetry.Observation
import cloud.airchive.shared.contract.telemetry.ObservationQuality
import cloud.airchive.shared.contract.telemetry.QualityFlag
import cloud.airchive.shared.contract.telemetry.TelemetryPage
import kotlin.math.round

/**
 * Deterministic, in-memory Airchive reader: no accounts, network, or Firebase.
 *
 * Given a fixed clock, [observations] always contain exactly the same 288
 * five-minute samples spanning the latest 24 hours, [dailyTotals] hold the
 * latest 30 finalized LG totals, and collector health is healthy. Paging is
 * deterministic and durable: cursors encode the next index into the fixture, so
 * a caller that persists a cursor can resume exactly where it stopped. Values
 * are realistic typed numbers (Wh observations, kWh totals) and deliberately
 * demo-branded (`demo-*` IDs) so nothing looks like production data. Set
 * [failWith] to exercise denied/failure states deterministically.
 */
class FakeAirchiveTelemetry(
    private val nowEpochMillis: Long = FAKE_EPOCH_MILLIS,
    private val observations: List<Observation> = defaultObservations(nowEpochMillis),
    private val dailyTotals: List<DailyTotal> = defaultTotals(nowEpochMillis),
    private val health: CollectorHealth = defaultHealth(nowEpochMillis),
) : AirchiveTelemetryPort {

    /** When non-null, every read throws it (deterministic failure injection). */
    var failWith: Throwable? = null

    override suspend fun latestObservations(
        sinceEpochMillis: Long,
        pageSize: Int,
        cursor: String?,
    ): TelemetryPage<Observation> {
        failWith?.let { throw it }
        AirchiveTelemetryPort.requireValidPageSize(pageSize)
        val view = observations
            .filter { it.intervalEndEpochMillis >= sinceEpochMillis }
            .sortedByDescending { it.intervalStartEpochMillis }
        return paginate(view, pageSize, cursor)
    }

    override suspend fun observations(
        fromEpochMillis: Long,
        toEpochMillis: Long,
        pageSize: Int,
        cursor: String?,
    ): TelemetryPage<Observation> {
        failWith?.let { throw it }
        AirchiveTelemetryPort.requireValidPageSize(pageSize)
        val view = observations
            .filter { o -> o.intervalEndEpochMillis > fromEpochMillis && o.intervalStartEpochMillis < toEpochMillis }
            .sortedBy { it.intervalStartEpochMillis }
        return paginate(view, pageSize, cursor)
    }

    override suspend fun latestFinalizedTotals(limit: Int): List<DailyTotal> {
        failWith?.let { throw it }
        AirchiveTelemetryPort.requireValidTotalsLimit(limit)
        return dailyTotals.take(limit)
    }

    override suspend fun collectorHealth(): CollectorHealth {
        failWith?.let { throw it }
        return health
    }

    override suspend fun deviceIdentity(): DeviceIdentity {
        failWith?.let { throw it }
        return DeviceIdentity(
            deviceId = DEMO_DEVICE_ID,
            alias = "Demo AC",
            modelName = "Demo Air Conditioner",
            deviceType = "air-conditioner",
            updatedAtEpochMillis = nowEpochMillis,
        )
    }

    private fun paginate(view: List<Observation>, pageSize: Int, cursor: String?): TelemetryPage<Observation> {
        val start = cursor?.let(::decodeCursorIndex) ?: 0
        val end = minOf(start + pageSize, view.size)
        val items = view.subList(start, end)
        val nextCursor = if (end < view.size) "index:$end" else null
        return TelemetryPage(items = items, nextCursor = nextCursor)
    }

    private fun decodeCursorIndex(cursor: String): Int {
        val index = cursor.removePrefix(CURSOR_PREFIX).toIntOrNull()
        return requireNotNull(index) { "Unknown fake telemetry cursor '$cursor'." }
    }

    companion object {
        /** Unmistakably non-production device identifier used across the fakes. */
        const val DEMO_DEVICE_ID = "demo-ac-id"

        const val INTERVAL_MILLIS = 5 * 60 * 1000L
        private const val SAMPLES_IN_24H = 288 // 24h / 5min
        private const val CURSOR_PREFIX = "index:"

        fun defaultObservations(now: Long): List<Observation> {
            // Align the latest sample to the most recent five-minute boundary.
            val latestEnd = (now / INTERVAL_MILLIS) * INTERVAL_MILLIS
            val firstStart = latestEnd - SAMPLES_IN_24H * INTERVAL_MILLIS
            return (0 until SAMPLES_IN_24H).map { i ->
                val start = firstStart + i * INTERVAL_MILLIS
                val missing = i % 97 == 0
                val sourceFailed = (i % 241 == 0) && !missing
                Observation(
                    sampleId = "demo-sample-$i",
                    intervalStartEpochMillis = start,
                    intervalEndEpochMillis = start + INTERVAL_MILLIS,
                    energyValue = if (missing) {
                        null
                    } else {
                        round1(30.5 + (i % 24) * 2.0 + ((i / 24) % 5) * 0.5)
                    },
                    recordedUnit = EnergyUnit.WH,
                    intervalStatus = when {
                        missing -> "MISSING"
                        sourceFailed -> "RECOVERED"
                        else -> "OK"
                    },
                    quality = when {
                        missing -> ObservationQuality.MISSING_INTERVAL
                        sourceFailed -> ObservationQuality.ANOMALOUS
                        else -> ObservationQuality.NORMAL
                    },
                    qualityFlags = when {
                        missing -> setOf(QualityFlag.MISSING_VALUE)
                        sourceFailed -> setOf(QualityFlag.SOURCE_FAILED)
                        else -> emptySet()
                    },
                    sourceFailure = if (sourceFailed) "lg-eu-api" else null,
                    persistedAtEpochMillis = now,
                    reconciledAtEpochMillis = now,
                )
            }
        }

        fun defaultTotals(now: Long): List<DailyTotal> {
            val dayMillis = 24 * 60 * 60 * 1000L
            val latestDay = (now / dayMillis) * dayMillis
            return (0 until 30).map { d ->
                val dayStart = latestDay - d * dayMillis
                DailyTotal(
                    totalId = "demo-total-${dayStart / dayMillis}",
                    dayStartEpochMillis = dayStart,
                    energyValue = round1(18.4 + (d % 12) * 1.35), // kWh per day
                    recordedUnit = EnergyUnit.KWH,
                    source = DailyTotalSource.LG_FINALIZED,
                    finalized = true,
                )
            }
        }

        fun defaultHealth(now: Long): CollectorHealth = CollectorHealth(
            status = CollectorStatus.HEALTHY,
            lastPollEpochMillis = now - 2 * 60 * 1000L,
            lastSuccessEpochMillis = now - 2 * 60 * 1000L,
            lastFailureEpochMillis = now - 27 * 60 * 60 * 1000L,
            lastFailureReason = "transient collector outage (already recovered)",
        )

        private fun round1(value: Double): Double = round(value * 10.0) / 10.0
    }
}
