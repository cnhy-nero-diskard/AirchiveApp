package cloud.airchive.shared.data.thinq

import cloud.airchive.shared.contract.thinq.TemperatureUnit
import kotlinx.serialization.json.Json
import kotlin.test.*

class ThinQCurrentTemperatureTest {
    private fun state(temperatures: String, unit: TemperatureUnit = TemperatureUnit.CELSIUS) =
        ThinQPayloads.liveState("ac", Json.parseToJsonElement(
            """{"operation":{"airConOperationMode":"POWER_ON"},$temperatures}"""), unit, 123L)

    @Test fun restSensorAndTargetAreIndependent() {
        val live = state(""""temperature":{"unit":"C","currentTemperature":28.5,"targetTemperature":24}""")
        assertEquals(28.5, live.currentTemperature)
        assertEquals(24.0, live.targetTemperature)
        assertEquals(123L, live.observedAtEpochMillis)
    }

    @Test fun eachReadingSelectsItsOwnPreferredLegacyBlock() {
        val live = state(""""temperatureInUnits":[
            {"unit":"F","currentTemperature":82.4,"targetTemperature":75.2},
            {"unit":"C","currentTemperature":28,"targetTemperature":24}]""")
        assertEquals(28.0, live.currentTemperature)
        assertEquals(24.0, live.targetTemperature)
        val independent = state(""""temperatureInUnits":[
            {"unit":"C","targetTemperature":24},{"unit":"F","currentTemperature":82.4}]""")
        assertEquals(28.0, assertNotNull(independent.currentTemperature), 0.000001)
        assertEquals(24.0, independent.targetTemperature)
    }

    @Test fun restSensorIsConvertedToProfileUnitWithoutRequiringTarget() {
        val live = state(""""temperature":{"unit":"C","currentTemperature":28}""", TemperatureUnit.FAHRENHEIT)
        assertEquals(82.4, assertNotNull(live.currentTemperature), 0.000001)
        assertNull(live.targetTemperature)
    }

    @Test fun sensorFallsBackToRestWhenLegacyOnlyHasTarget() {
        val live = state(""""temperatureInUnits":[{"unit":"C","targetTemperature":24}],
            "temperature":{"unit":"C","currentTemperature":28}""")
        assertEquals(28.0, live.currentTemperature)
        assertEquals(24.0, live.targetTemperature)
    }

    @Test fun missingMalformedAndUnknownUnitSensorsStayUnavailable() {
        listOf(
            """"temperature":{"unit":"C","targetTemperature":24}""",
            """"temperature":{"unit":"C","currentTemperature":null}""",
            """"temperature":{"unit":"C","currentTemperature":"bad"}""",
            """"temperature":{"unit":"C","currentTemperature":"NaN"}""",
            """"temperature":{"unit":"C","currentTemperature":"Infinity"}""",
            """"temperature":{"unit":"K","currentTemperature":300}""",
            """"temperature":{"currentTemperature":28}""",
            """"temperatureInUnits":[{"unit":"K","currentTemperature":300}]""",
        ).forEach { assertNull(state(it).currentTemperature, it) }
        assertEquals(0.0, state(""""temperature":{"unit":"C","currentTemperature":0}""").currentTemperature)
    }
}
