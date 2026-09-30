package cloud.airchive.shared.fakes

import cloud.airchive.shared.contract.credentials.ThinQCredentialStore
import cloud.airchive.shared.contract.history.LocalHistoryPort
import cloud.airchive.shared.contract.owner.OwnerIdentityPort
import cloud.airchive.shared.contract.telemetry.AirchiveTelemetryPort
import cloud.airchive.shared.contract.thinq.ThinQPort

/**
 * One convenient wiring point for the 1.3 fake providers: construct all five
 * in-memory contracts with a shared deterministic clock and one fake device, so
 * the app runs end to end without live accounts, network, or Firebase.
 */
class FakeProviders(
    nowEpochMillis: Long = FAKE_EPOCH_MILLIS,
    deviceId: String = FakeAirchiveTelemetry.DEMO_DEVICE_ID,
) {
    val ownerIdentity: OwnerIdentityPort = FakeOwnerIdentity()

    val telemetry: AirchiveTelemetryPort = FakeAirchiveTelemetry(nowEpochMillis = nowEpochMillis)

    val localHistory: LocalHistoryPort = FakeLocalHistory()

    val thinQCredentialStore: ThinQCredentialStore = FakeThinQCredentialStore()

    val thinQ: ThinQPort = FakeThinQClient(deviceId = deviceId, nowEpochMillis = nowEpochMillis)
}