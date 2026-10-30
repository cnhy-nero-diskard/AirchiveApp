package cloud.airchive.shared.fakes

import cloud.airchive.shared.contract.thinq.AcControls
import cloud.airchive.shared.contract.thinq.AcOperatingMode
import cloud.airchive.shared.contract.thinq.FanSpeed
import cloud.airchive.shared.contract.thinq.TemperatureCapability
import cloud.airchive.shared.contract.thinq.TemperatureUnit
import cloud.airchive.shared.contract.thinq.ThinQCredentialVerificationRequest
import cloud.airchive.shared.contract.thinq.ThinQCredentialVerifier
import cloud.airchive.shared.contract.thinq.ThinQDevice
import cloud.airchive.shared.contract.thinq.ThinQDeviceProfile
import cloud.airchive.shared.contract.thinq.ThinQVerificationResult

/**
 * In-memory ThinQ read-verification fake. It accepts synthetic demo inputs and
 * returns only its clearly `demo-*` AC identity/profile; it never contacts LG,
 * checks a real PAT, or proves control authority. Tests can replace [result] or
 * inject [failWith] to exercise replacement outcomes deterministically.
 */
class FakeThinQCredentialVerifier(
    private val deviceId: String = FakeAirchiveTelemetry.DEMO_DEVICE_ID,
) : ThinQCredentialVerifier {

    var result: ThinQVerificationResult? = null
    var failWith: Throwable? = null
    var lastRequest: ThinQCredentialVerificationRequest? = null
        private set

    override suspend fun verify(request: ThinQCredentialVerificationRequest): ThinQVerificationResult {
        lastRequest = request
        failWith?.let { throw it }
        return result ?: if (request.expectedDeviceId == deviceId) {
            ThinQVerificationResult.Verified(
                device = ThinQDevice(
                    deviceId = deviceId,
                    alias = "Demo Room AC",
                    modelName = "Demo Air Conditioner",
                    online = true,
                ),
                profile = ThinQDeviceProfile(
                    deviceId = deviceId,
                    modelName = "Demo Air Conditioner",
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
                        allowedFanSpeeds = listOf(FanSpeed.LOW, FanSpeed.MEDIUM, FanSpeed.HIGH, FanSpeed.AUTO),
                        temperatureUnit = TemperatureUnit.CELSIUS,
                    ),
                ),
            )
        } else {
            ThinQVerificationResult.WrongAccount
        }
    }
}
