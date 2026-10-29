package cloud.airchive.shared.fakes

import cloud.airchive.shared.contract.thinq.AcCommand
import cloud.airchive.shared.contract.thinq.AcControls
import cloud.airchive.shared.contract.thinq.AcOperatingMode
import cloud.airchive.shared.contract.thinq.CommandFailure
import cloud.airchive.shared.contract.thinq.CommandOutcome
import cloud.airchive.shared.contract.thinq.CommandStatus
import cloud.airchive.shared.contract.thinq.FanSpeed
import cloud.airchive.shared.contract.thinq.TemperatureCapability
import cloud.airchive.shared.contract.thinq.TemperatureUnit
import cloud.airchive.shared.contract.thinq.ThinQDevice
import cloud.airchive.shared.contract.thinq.ThinQDeviceProfile
import cloud.airchive.shared.contract.thinq.ThinQLiveState
import cloud.airchive.shared.contract.thinq.ThinQPort

/**
 * Deterministic, in-memory LG/ThinQ client for one fake AC. No live LG account
 * or network. It owns a mutable live state, derives controls from a fixed
 * profile (temperature bounds `18..30 °C`, modes, fan speeds), and applies
 * supported commands before confirming them with a fresh state read.
 *
 * Knobs (all deterministic):
 * - [failWith]: every command fails with that reason (e.g. no-control-authority).
 * - [verifyAfterAccept]: when `false`, an accepted command is reported as
 *   [CommandStatus.ACCEPTED] without a follow-up read (pending path).
 * - Unsupported commands always fail with [CommandFailure.INVALID_REQUEST].
 */
class FakeThinQClient(
    private val deviceId: String = FakeAirchiveTelemetry.DEMO_DEVICE_ID,
    private val nowEpochMillis: Long = FAKE_EPOCH_MILLIS,
) : ThinQPort {

    /** When set, [setState] returns [CommandStatus.FAILED] with this reason. */
    var failWith: CommandFailure? = null

    /** When `false`, markup the accepted command as pending without confirming. */
    var verifyAfterAccept: Boolean = true

    private var live = ThinQLiveState(
        deviceId = deviceId,
        observedAtEpochMillis = nowEpochMillis,
        powerOn = true,
        targetTemperature = 25.0,
        mode = AcOperatingMode.COOL,
        fanSpeed = FanSpeed.LOW,
    )
    private var liveReadTimestampEpochMillis = nowEpochMillis

    /** Test-only clock seam for proving device-read freshness independently. */
    fun setLiveReadTimestamp(epochMillis: Long) {
        liveReadTimestampEpochMillis = epochMillis
    }

    private val profile = ThinQDeviceProfile(
        deviceId = deviceId,
        modelName = "Fake AC-27TK",
        controls = AcControls(
            powerWritable = true,
            targetTemperature = TemperatureCapability(
                min = 18.0,
                max = 30.0,
                step = 1.0,
                unit = TemperatureUnit.CELSIUS,
            ),
            allowedModes = listOf(
                AcOperatingMode.COOL,
                AcOperatingMode.DRY,
                AcOperatingMode.FAN,
                AcOperatingMode.AUTO,
            ),
            allowedFanSpeeds = listOf(
                FanSpeed.LOW,
                FanSpeed.MEDIUM,
                FanSpeed.HIGH,
                FanSpeed.AUTO,
            ),
            temperatureUnit = TemperatureUnit.CELSIUS,
        ),
    )

    private var commandCounter = 0

    override suspend fun listDevices(): List<ThinQDevice> = listOf(
        ThinQDevice(
            deviceId = deviceId,
            alias = "Demo Room AC",
            modelName = profile.modelName,
            online = true,
        ),
    )

    override suspend fun profile(deviceId: String): ThinQDeviceProfile {
        requireDevice(deviceId)
        return profile
    }

    override suspend fun liveState(deviceId: String): ThinQLiveState {
        requireDevice(deviceId)
        return live.copy(observedAtEpochMillis = liveReadTimestampEpochMillis)
    }

    override suspend fun setState(command: AcCommand): CommandOutcome {
        commandCounter += 1
        val outcome = baseOutcome(command)

        failWith?.let { failure ->
            return outcome.copy(status = CommandStatus.FAILED, failure = failure, message = failure.name)
        }

        if (!supports(command)) {
            return outcome.copy(
                status = CommandStatus.FAILED,
                failure = CommandFailure.INVALID_REQUEST,
                message = "Profile does not permit this value.",
            )
        }

        apply(command)

        if (!verifyAfterAccept) return outcome.copy(status = CommandStatus.ACCEPTED)

        val fresh = live.copy(observedAtEpochMillis = nowEpochMillis)
        val confirmed = matches(command, fresh)
        return outcome.copy(
            status = if (confirmed) CommandStatus.CONFIRMED else CommandStatus.UNCONFIRMED,
            observedAtEpochMillis = nowEpochMillis,
            observedState = fresh,
            message = if (confirmed) null else "Accepted, but the AC did not report the requested setting.",
        )
    }

    private fun baseOutcome(command: AcCommand) = CommandOutcome(
        commandId = "fake-cmd-${commandCounter}",
        deviceId = deviceId,
        command = command,
        status = CommandStatus.SENDING,
        submittedAtEpochMillis = command.requestedAtEpochMillis.takeIf { it > 0L } ?: nowEpochMillis,
    )

    private fun supports(command: AcCommand): Boolean = when (command) {
        is AcCommand.SetPower -> profile.controls.powerWritable
        is AcCommand.SetTargetTemperature -> {
            val cap = profile.controls.targetTemperature ?: return false
            command.value >= cap.min - EPS && command.value <= cap.max + EPS &&
                ((command.value - cap.min) / cap.step).let { quotient ->
                    kotlin.math.abs(quotient - kotlin.math.round(quotient)) <= EPS
                }
        }

        is AcCommand.SetMode -> command.mode in profile.controls.allowedModes
        is AcCommand.SetFanSpeed -> command.speed in profile.controls.allowedFanSpeeds
    }

    private fun apply(command: AcCommand) {
        live = when (command) {
            is AcCommand.SetPower -> live.copy(powerOn = command.powerOn)
            is AcCommand.SetTargetTemperature -> live.copy(targetTemperature = command.value)
            is AcCommand.SetMode -> live.copy(mode = command.mode)
            is AcCommand.SetFanSpeed -> live.copy(fanSpeed = command.speed)
        }
    }

    private fun matches(command: AcCommand, fresh: ThinQLiveState): Boolean = when (command) {
        is AcCommand.SetPower -> fresh.powerOn == command.powerOn
        is AcCommand.SetTargetTemperature -> fresh.targetTemperature != null &&
            kotlin.math.abs(fresh.targetTemperature - command.value) <= EPS

        is AcCommand.SetMode -> fresh.mode == command.mode
        is AcCommand.SetFanSpeed -> fresh.fanSpeed == command.speed
    }

    private fun requireDevice(requestedDeviceId: String) {
        require(requestedDeviceId == deviceId) { "Fake ThinQ client has no device '$requestedDeviceId'." }
    }

    private companion object {
        const val EPS = 1e-9
    }
}
