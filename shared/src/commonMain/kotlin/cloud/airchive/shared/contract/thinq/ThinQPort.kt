package cloud.airchive.shared.contract.thinq

import kotlin.math.abs
import kotlin.math.round

/**
 * Narrow LG/ThinQ client for exactly the four everyday AC operations.
 *
 * Implements only device list, profile, live state, and command requests,
 * matching the official SDK's routing/headers/payload shapes in the real
 * adapter. The profile is the single source of truth for which controls are
 * representable (writable power, temperature bounds/steps/units, allowed modes,
 * and fan speeds); live state carries its own observation timestamp that is
 * deliberately distinct from any Firestore sample age.
 *
 * The in-memory [FakeThinQClient] is deterministic, applies commands to its own
 * live state, and can represent every command outcome — accepted, confirmed,
 * failed, and unconfirmed — without a live LG account.
 */
interface ThinQPort {

    /**
     * Captures an opaque, operation-bound adapter context before an eligible
     * request starts. Production implementations may return a facade that
     * privately pins the credential material used for the entire request
     * sequence (including bounded command confirmation); callers never receive
     * the token itself. Fakes/default adapters are already single-context and
     * safely use `this`.
     *
     * Lifecycle owners must recheck their captured non-secret context key after
     * this suspend point and before dispatch. Once a context has been captured,
     * later credential replacement cannot make this operation borrow the new
     * credential.
     */
    suspend fun captureOperationContext(): ThinQPort = this

    /** Devices visible to the configured token (used to verify the intended AC). */
    suspend fun listDevices(): List<ThinQDevice>

    /** Profile of one AC: model and writable control capability. */
    suspend fun profile(deviceId: String): ThinQDeviceProfile

    /** Current live state read straight from LG, with its own read timestamp. */
    suspend fun liveState(deviceId: String): ThinQLiveState

    /**
     * Submits one command and reports the outcome. The adapter performs a
     * bounded fresh state read after an accepted API response; only observed
     * matching state is [CommandStatus.CONFIRMED]. Ambiguous no-response
     * timeouts surface as [CommandStatus.UNCONFIRMED] and are never auto-resent.
     * A rejected command never writes anything back to telemetry.
     */
    suspend fun setState(command: AcCommand): CommandOutcome
}

/** One device as listed by LG. */
data class ThinQDevice(
    val deviceId: String,
    val alias: String,
    val modelName: String? = null,
    /** Nullable because some valid device-list responses omit online status. */
    val online: Boolean? = null,
    /** Raw LG product type, when returned; credential verification checks this. */
    val deviceType: String? = null,
)

/**
 * Temperature bounds, step, and unit for target-temperature control.
 *
 * [excludedValues] preserves the profile's `write_values.except` list: target
 * temperatures the device explicitly rejects even though they lie inside
 * [min]..[max] on the [step] grid. The parser guarantees every entry is finite.
 * [allows] is the single shared gate any adapter uses before sending a
 * temperature command; it rejects NaN/infinite values, out-of-bounds values,
 * off-step values, and excluded values.
 */
data class TemperatureCapability(
    val min: Double,
    val max: Double,
    val step: Double,
    val unit: TemperatureUnit,
    val excludedValues: Set<Double> = emptySet(),
) {
    /**
     * True only when [value] is a real, finite temperature this device accepts
     * as a written target: within [min]..[max] (with [epsilon] tolerance),
     * exactly on the [step] grid from [min], and not listed in
     * [excludedValues]. A capability that is not itself finite with a positive
     * [step] never admits any command.
     */
    fun allows(value: Double, epsilon: Double = 1e-9): Boolean {
        if (!value.isFinite()) return false
        if (!min.isFinite() || !max.isFinite() || !step.isFinite() || step <= 0.0) return false
        if (value < min - epsilon || value > max + epsilon) return false
        if (excludedValues.any { abs(it - value) <= epsilon }) return false
        val quotient = (value - min) / step
        return abs(quotient - round(quotient)) <= epsilon
    }
}

/**
 * Profile-derived control capability. Only `true`/non-empty entries are offered
 * in the Device UI; unsupported or absent properties never produce enabled
 * controls.
 *
 * [temperatureUnit] is exactly the unit of the surfaced [targetTemperature]
 * capability, so the two can never disagree. A profile that exposes several
 * units (for example Celsius and Fahrenheit) still surfaces a single preferred
 * unit here; the data layer retains the other units internally only to convert
 * a requested command into a unit the returned profile actually provides, and
 * never enables a unit absent from this profile.
 */
data class AcControls(
    val powerWritable: Boolean,
    val targetTemperature: TemperatureCapability? = null,
    val allowedModes: List<AcOperatingMode> = emptyList(),
    val allowedFanSpeeds: List<FanSpeed> = emptyList(),
    val temperatureUnit: TemperatureUnit = TemperatureUnit.CELSIUS,
)

data class ThinQDeviceProfile(
    val deviceId: String,
    val modelName: String? = null,
    val controls: AcControls,
)

/**
 * Live AC state. [observedAtEpochMillis] is the moment LG answered the read and
 * must be shown separately from any telemetry sync time; a stale or failed read
 * is never presented as current state.
 */
data class ThinQLiveState(
    val deviceId: String,
    val observedAtEpochMillis: Long,
    val powerOn: Boolean,
    val targetTemperature: Double? = null,
    val mode: AcOperatingMode? = null,
    val fanSpeed: FanSpeed? = null,
)

/** The four permitted command types, one-at-a-time per device. */
sealed interface AcCommand {
    /** Epoch millis when the owner submitted the command. */
    val requestedAtEpochMillis: Long

    data class SetPower(
        override val requestedAtEpochMillis: Long,
        val powerOn: Boolean,
    ) : AcCommand

    data class SetTargetTemperature(
        override val requestedAtEpochMillis: Long,
        val value: Double,
        val unit: TemperatureUnit = TemperatureUnit.CELSIUS,
    ) : AcCommand

    data class SetMode(
        override val requestedAtEpochMillis: Long,
        val mode: AcOperatingMode,
    ) : AcCommand

    data class SetFanSpeed(
        override val requestedAtEpochMillis: Long,
        val speed: FanSpeed,
    ) : AcCommand
}

enum class TemperatureUnit { CELSIUS, FAHRENHEIT }

enum class AcOperatingMode { COOL, HEAT, DRY, FAN, AUTO, POWER_SAVE }

enum class FanSpeed { AUTO, LOW, MEDIUM, HIGH, TURBO }

/**
 * Explicit command lifecycle. [SENDING] is the in-flight UI state before the
 * port answers; the port only ever returns [ACCEPTED], [CONFIRMED], [FAILED],
 * or [UNCONFIRMED]. Duplicate taps while pending must be ignored.
 */
enum class CommandStatus { SENDING, ACCEPTED, CONFIRMED, FAILED, UNCONFIRMED }

enum class CommandFailure {
    /** Token lacks control authority; a control-capable PAT is required. */
    NO_CONTROL_AUTHORITY,

    RATE_LIMITED,
    INVALID_REQUEST,
    TOKEN_REJECTED,

    /** LG unreachable. */
    NETWORK,

    /** Send timed out or no matching state observed. */
    TIMEOUT,

    DEVICE_UNAVAILABLE,
    UNKNOWN,
}

/** Result of one command submission, including the confirming state read when available. */
data class CommandOutcome(
    val commandId: String,
    val deviceId: String,
    val command: AcCommand,
    val status: CommandStatus,
    val submittedAtEpochMillis: Long,
    /** Time of the confirming live-state read, when it happened. */
    val observedAtEpochMillis: Long? = null,
    /** Fresh state read after an accepted command, when available. */
    val observedState: ThinQLiveState? = null,
    val failure: CommandFailure? = null,
    val message: String? = null,
)
