package cloud.airchive.shared.data.thinq

import cloud.airchive.shared.contract.thinq.AcCommand
import cloud.airchive.shared.contract.thinq.AcOperatingMode
import cloud.airchive.shared.contract.thinq.CommandFailure
import cloud.airchive.shared.contract.thinq.CommandStatus
import cloud.airchive.shared.contract.thinq.FanSpeed
import cloud.airchive.shared.contract.thinq.TemperatureCapability
import cloud.airchive.shared.contract.thinq.TemperatureUnit
import cloud.airchive.shared.fakes.FakeThinQCredentialStore
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.HttpRequestData
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.TextContent
import io.ktor.http.headersOf
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject

/**
 * Deterministic profile-capability checks for OpenSpec task 4.2. Everything is a
 * synthetic local JSON fixture — no live LG account, no PAT, no network.
 *
 * Proves: power is writable only when the enum lists BOTH POWER_ON and
 * POWER_OFF; read-only/absent power is never enabled; modes and fan speeds keep
 * only contract-known values that round-trip to the profile's exact LG write
 * strings (with the SDK-selected `airFlow` key); absent properties never
 * produce enabled controls; target temperature keeps finite min/max/step with
 * min <= max and step > 0, preserves `write_values.except` exclusions, and is
 * omitted for reversed/non-finite/zero-step/unknown-unit ranges; the shared
 * [TemperatureCapability.allows] gate rejects out-of-bounds, off-step,
 * excluded, NaN, and infinite commands; and the client rejects all of those
 * before any POST while still emitting a body only for values the profile
 * explicitly permits — never reporting a unit absent from the returned profile.
 */
class ThinQProfileCapabilityTest {

    private val deviceId = "ac-device-1"
    private val pat = "thinq-pat.example.invalid-9f3a"
    private val stableClientId = "stable-install-9f3a"

    // ------------------------------------------------------------ fixtures

    /** Wraps a `property` object in the unwrapped `payload.response` body [ThinQPayloads.acProfile] reads. */
    private fun response(property: String): String = """{"response":{"property":$property}}"""

    /** Wraps `key:value` bodies into one `property` object body. */
    private fun propertyOf(vararg resources: String): String = "{" + resources.joinToString(",") + "}"

    private fun powerProperty(writeValues: String?, writable: Boolean = true): String = buildString {
        append(""""operation":{"airConOperationMode":{"type":"enum","unit":"bool_enum","readable":true,"writable":$writable,""")
        append(""" "read_values":["AI","POWER_ON","POWER_OFF"]""")
        if (writeValues != null) append(", \"write_values\":$writeValues")
        append("}}")
    }

    private fun modeProperty(writeValues: String, writable: Boolean = true): String =
        """"airConJobMode":{"currentJobMode":{"type":"enum","readable":true,"writable":$writable,"read_values":["COOL","HEAT","DRY","FAN","AUTO","ENERGY_SAVING","SUPER_CHILL"],"write_values":$writeValues}}"""

    private fun fanProperty(detailWrite: String? = null, windWrite: String? = null): String {
        val entries = buildList {
            if (detailWrite != null) {
                add(""""windStrengthDetail":{"type":"enum","readable":true,"writable":true,"write_values":$detailWrite}""")
            }
            if (windWrite != null) {
                add(""""windStrength":{"type":"enum","readable":true,"writable":true,"write_values":$windWrite}""")
            }
        }
        return """"airFlow":{${entries.joinToString(",")}}"""
    }

    private fun tempBlock(unit: String, writeValues: String, writable: Boolean = true): String =
        """{"unit":"$unit","targetTemperature":{"type":"range","unit":"degC","readable":true,"writable":$writable,"write_values":$writeValues}}"""

    private fun temperatureProperty(block: String, vararg more: String): String =
        """"temperatureInUnits":[${(listOf(block) + more).joinToString(",")}]"""

    private fun parsed(body: String): ParsedAcProfile {
        val payload = Json.parseToJsonElement(body).jsonObject["response"]
        return ThinQPayloads.acProfile(deviceId, payload)
    }

    private fun stateJson(
        power: String = "POWER_ON",
        mode: String = "COOL",
        wind: String = "MID",
        windKey: String = "windStrength",
        targetC: Int = 25,
    ): String = """
        {"response":{
          "operation":{"airConOperationMode":"$power"},
          "airConJobMode":{"currentJobMode":"$mode"},
          "temperatureInUnits":[{"unit":"C","targetTemperature":$targetC}],
          "airFlow":{"$windKey":"$wind"}
        }}
    """.trimIndent()

    private fun stateJsonFOnly(targetF: Int = 77): String = """
        {"response":{
          "operation":{"airConOperationMode":"POWER_ON"},
          "airConJobMode":{"currentJobMode":"COOL"},
          "temperatureInUnits":[{"unit":"F","targetTemperature":$targetF}],
          "airFlow":{"windStrength":"LOW"}
        }}
    """.trimIndent()

    private suspend fun gatingClient(
        requests: MutableList<HttpRequestData>,
        profileBody: String,
        stateBody: String,
    ): KtorThinQClient {
        val store = FakeThinQCredentialStore(stableClientId).apply { save(pat, "PH") }
        val engine = MockEngine { request ->
            requests += request
            when {
                request.method == HttpMethod.Post ->
                    respond("""{"message":"accepted"}""", HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json"))
                request.url.encodedPath.endsWith("/profile") ->
                    respond(profileBody, HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json"))
                else ->
                    respond(stateBody, HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json"))
            }
        }
        return KtorThinQClient(engine, store, deviceId) { 1_800_000_000_000L }
    }

    private fun requestBody(request: HttpRequestData): String =
        (request.body as? TextContent)?.text.orEmpty()

    // ------------------------------------------------------------ power

    @Test
    fun powerWritableOnlyWhenBothDirectionsAreExplicitWriteValues() {
        assertTrue(parsed(response(propertyOf(powerProperty("""["POWER_ON","POWER_OFF"]""")))).powerWritable)
        // Extra unknown write values are ignored; both known directions are what matter.
        assertTrue(parsed(response(propertyOf(powerProperty("""["AI","POWER_ON","POWER_OFF"]""")))).powerWritable)

        // A single direction can never expose the power control.
        assertFalse(parsed(response(propertyOf(powerProperty("""["POWER_ON"]""")))).powerWritable)
        assertFalse(parsed(response(propertyOf(powerProperty("""["POWER_OFF"]""")))).powerWritable)
        assertFalse(parsed(response(propertyOf(powerProperty("""["AI"]""")))).powerWritable)
        // No write_values at all.
        assertFalse(parsed(response(propertyOf(powerProperty(null)))).powerWritable)
    }

    @Test
    fun readOnlyOrAbsentPowerIsNeverWritable() {
        assertFalse(
            parsed(response(propertyOf(powerProperty("""["POWER_ON","POWER_OFF"]""", writable = false)))).powerWritable,
        )
        // operation resource absent entirely.
        assertFalse(parsed("""{"response":{"property":{"airConJobMode":{}}}}""").powerWritable)
        // operation present but without the airConOperationMode property.
        assertFalse(parsed("""{"response":{"property":{"operation":{}}}}""").powerWritable)
    }

    // ------------------------------------------------------ modes and fans

    @Test
    fun modesAndFansKeepOnlyKnownValuesRoundedBackToExactWriteStrings() {
        val body = response(
            propertyOf(
                modeProperty("""["COOL","SUPER_CHILL","DRY"]"""),
                fanProperty(windWrite = """["LOW","ZEPHYR","HIGH","AUTO"]"""),
            ),
        )
        val controls = parsed(body).toContractProfile().controls
        assertEquals(listOf(AcOperatingMode.COOL, AcOperatingMode.DRY), controls.allowedModes)
        assertEquals(listOf(FanSpeed.LOW, FanSpeed.HIGH, FanSpeed.AUTO), controls.allowedFanSpeeds)
    }

    @Test
    fun fanCapabilityUsesTheSdkSelectedAirFlowPropertyKey() = runBlocking {
        val body = response(propertyOf(fanProperty(detailWrite = """["MID"]""", windWrite = """["HIGH"]""")))
        val controls = parsed(body).toContractProfile().controls
        assertEquals(listOf(FanSpeed.MEDIUM), controls.allowedFanSpeeds, "windStrengthDetail wins before windStrength")

        val requests = mutableListOf<HttpRequestData>()
        val thinq = gatingClient(requests, body, stateJson(windKey = "windStrengthDetail", wind = "MID"))
        val outcome = thinq.setState(AcCommand.SetFanSpeed(REQUESTED_AT, FanSpeed.MEDIUM))
        assertEquals(1, requests.count { it.method == HttpMethod.Post })
        assertTrue(
            requestBody(requests.last { it.method == HttpMethod.Post }).contains("\"windStrengthDetail\":\"MID\""),
        )
        assertEquals(CommandStatus.CONFIRMED, outcome.status)
    }

    // --------------------------------------------------- absent properties

    @Test
    fun absentPropertiesNeverEnableAnyControl() = runBlocking {
        val profile = parsed(response("{}")).toContractProfile()
        val controls = profile.controls
        assertFalse(controls.powerWritable)
        assertNull(controls.targetTemperature)
        assertTrue(controls.allowedModes.isEmpty())
        assertTrue(controls.allowedFanSpeeds.isEmpty())

        // The client must not POST a control the profile does not offer.
        val requests = mutableListOf<HttpRequestData>()
        val thinq = gatingClient(requests, response("{}"), stateJson())
        for (command in listOf(
            AcCommand.SetPower(REQUESTED_AT, powerOn = true),
            AcCommand.SetTargetTemperature(REQUESTED_AT, 25.0, TemperatureUnit.CELSIUS),
            AcCommand.SetMode(REQUESTED_AT, AcOperatingMode.COOL),
            AcCommand.SetFanSpeed(REQUESTED_AT, FanSpeed.LOW),
        )) {
            val outcome = thinq.setState(command)
            assertEquals(CommandStatus.FAILED, outcome.status, "$command must fail locally")
            assertEquals(CommandFailure.INVALID_REQUEST, outcome.failure, "$command")
        }
        assertEquals(4, requests.size, "only one profile read per command")
        assertEquals(0, requests.count { it.method == HttpMethod.Post }, "absent capabilities never POST")
    }

    // -------------------------------------------------------- temperature

    @Test
    fun temperatureCapabilityPreservesWriteValuesExcept() {
        val body = response(
            propertyOf(temperatureProperty(tempBlock("C", """{"min":18,"max":30,"step":1,"except":[26,28]}"""))),
        )
        val profile = parsed(body)
        val cap = assertNotNull(profile.temperatureByUnit[TemperatureUnit.CELSIUS])
        assertEquals(setOf(26.0, 28.0), cap.excludedValues)
        assertTrue(cap.allows(25.0))
        assertTrue(cap.allows(27.0))
        assertTrue(cap.allows(30.0))
        assertFalse(cap.allows(26.0))
        assertFalse(cap.allows(28.0))

        // Exclusions survive into the surfaced contract capability unchanged.
        val contractCap = assertNotNull(profile.toContractProfile().controls.targetTemperature)
        assertEquals(setOf(26.0, 28.0), contractCap.excludedValues)
    }

    @Test
    fun malformedExclusionListDisablesTheCapabilityInsteadOfAllowingUncertainValues() {
        val malformedExceptValues = listOf(
            "\"NaN\"",
            "\"Infinity\"",
            "true",
            "{}",
        )
        for (except in malformedExceptValues) {
            val body = response(
                propertyOf(
                    temperatureProperty(
                        tempBlock("C", """{"min":18,"max":30,"step":1,"except":[26,$except,28]}"""),
                    ),
                ),
            )
            assertNull(
                parsed(body).temperatureByUnit[TemperatureUnit.CELSIUS],
                "malformed except entry $except must not enable a temperature capability",
            )
        }

        val malformedField = response(
            propertyOf(
                temperatureProperty(
                    tempBlock("C", """{"min":18,"max":30,"step":1,"except":"26"}"""),
                ),
            ),
        )
        assertTrue(parsed(malformedField).temperatureByUnit.isEmpty(), "non-array except must disable the range")
    }

    @Test
    fun reversedNonFiniteZeroStepAndUnknownUnitTemperaturesAreOmitted() {
        val badWriteValues = listOf(
            """{"min":30,"max":18,"step":1}""", // reversed range
            """{"min":"NaN","max":30,"step":1}""", // non-finite min
            """{"min":18,"max":"NaN","step":1}""", // non-finite max
            """{"min":18,"max":30,"step":0}""", // zero step
            """{"min":18,"max":30,"step":-1}""", // negative step
            """{"min":18,"max":30,"step":"NaN"}""", // non-finite step
            """{"min":18,"max":30,"step":"Infinity"}""", // infinite step
        )
        for (writeValues in badWriteValues) {
            val body = response(propertyOf(temperatureProperty(tempBlock("C", writeValues))))
            assertTrue(parsed(body).temperatureByUnit.isEmpty(), "range $writeValues must not produce a capability")
        }

        // An unknown unit is never conflated with C or F.
        val kelvin = """"temperatureInUnits":[{"unit":"K","targetTemperature":{"type":"range","writable":true,"write_values":{"min":293,"max":303,"step":1}}}]"""
        assertTrue(parsed(response(propertyOf(kelvin))).temperatureByUnit.isEmpty())

        // A read-only target temperature is not surfaced.
        val readOnly = response(propertyOf(temperatureProperty(tempBlock("C", """{"min":18,"max":30,"step":1}""", writable = false))))
        assertTrue(parsed(readOnly).temperatureByUnit.isEmpty())

        // Missing or absent write_values range is not surfaced.
        val writeLess = """"temperatureInUnits":[{"unit":"C","targetTemperature":{"type":"range","readable":true,"writable":true}}]"""
        assertTrue(parsed(response(propertyOf(writeLess))).temperatureByUnit.isEmpty())
    }

    @Test
    fun allowsRejectsUnsafeAndExcludedValuesDirectly() {
        val cap = TemperatureCapability(18.0, 30.0, 1.0, TemperatureUnit.CELSIUS, setOf(26.0))
        assertFalse(cap.allows(Double.NaN))
        assertFalse(cap.allows(Double.POSITIVE_INFINITY))
        assertFalse(cap.allows(Double.NEGATIVE_INFINITY))
        assertFalse(cap.allows(17.0), "below min")
        assertFalse(cap.allows(31.0), "above max")
        assertFalse(cap.allows(18.5), "off step grid")
        assertFalse(cap.allows(26.0), "excluded")
        assertTrue(cap.allows(18.0))
        assertTrue(cap.allows(25.0))
        assertTrue(cap.allows(30.0))

        // A capability that is itself malformed admits nothing.
        assertFalse(TemperatureCapability(Double.NaN, 30.0, 1.0, TemperatureUnit.CELSIUS).allows(25.0))
        assertFalse(TemperatureCapability(18.0, 30.0, Double.POSITIVE_INFINITY, TemperatureUnit.CELSIUS).allows(25.0))
        assertFalse(TemperatureCapability(18.0, 30.0, 0.0, TemperatureUnit.CELSIUS).allows(25.0))
    }

    @Test
    fun surfacedTemperatureUnitAlwaysAgreesWithItsCapability() {
        val cBlock = tempBlock("C", """{"min":18,"max":30,"step":1}""")
        val fBlock = tempBlock("F", """{"min":64,"max":86,"step":1}""")

        // C+F: the contract surfaces the preferred unit only; the data layer keeps both.
        val both = parsed(response(propertyOf(temperatureProperty(cBlock, fBlock))))
        assertEquals(setOf(TemperatureUnit.CELSIUS, TemperatureUnit.FAHRENHEIT), both.temperatureByUnit.keys)
        val bothContract = both.toContractProfile()
        val cCap = assertNotNull(bothContract.controls.targetTemperature)
        assertEquals(TemperatureUnit.CELSIUS, bothContract.controls.temperatureUnit, "C is preferred when offered")
        assertEquals(bothContract.controls.temperatureUnit, cCap.unit, "temperatureUnit must equal the surfaced capability's unit")

        // F-only surfaces Fahrenheit and agrees.
        val fOnly = parsed(response(propertyOf(temperatureProperty(fBlock))))
        val fContract = fOnly.toContractProfile()
        val fCap = assertNotNull(fContract.controls.targetTemperature)
        assertEquals(TemperatureUnit.FAHRENHEIT, fContract.controls.temperatureUnit)
        assertEquals(fContract.controls.temperatureUnit, fCap.unit)
    }

    // ----------------------------------------------------- command gating

    @Test
    fun excludedTemperatureIsRejectedBeforeAnyPostButPermittedValueSends() = runBlocking {
        val profileBody = response(
            propertyOf(temperatureProperty(tempBlock("C", """{"min":18,"max":30,"step":1,"except":[26,28]}"""))),
        )
        val requests = mutableListOf<HttpRequestData>()
        val thinq = gatingClient(requests, profileBody, stateJson(targetC = 25))

        for (excluded in listOf(26.0, 28.0)) {
            val outcome = thinq.setState(AcCommand.SetTargetTemperature(REQUESTED_AT, excluded, TemperatureUnit.CELSIUS))
            assertEquals(CommandStatus.FAILED, outcome.status, "excluded $excluded")
            assertEquals(CommandFailure.INVALID_REQUEST, outcome.failure, "excluded $excluded")
            assertEquals(0, requests.count { it.method == HttpMethod.Post }, "excluded $excluded must never POST")
        }

        val accepted = thinq.setState(AcCommand.SetTargetTemperature(REQUESTED_AT, 25.0, TemperatureUnit.CELSIUS))
        assertEquals(1, requests.count { it.method == HttpMethod.Post }, "the permitted on-step value is the only POST")
        assertTrue(requestBody(requests.last { it.method == HttpMethod.Post }).contains("\"targetTemperature\":25"))
        assertEquals(CommandStatus.CONFIRMED, accepted.status)
    }

    @Test
    fun offStepOutOfBoundsAndNonFiniteTemperaturesAreRejectedWithoutPost() = runBlocking {
        val profileBody = response(propertyOf(temperatureProperty(tempBlock("C", """{"min":18,"max":30,"step":1}"""))))
        val requests = mutableListOf<HttpRequestData>()
        val thinq = gatingClient(requests, profileBody, stateJson(targetC = 25))

        val bad = listOf(17.0, 31.0, 18.5, 26.5, Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY)
        for (value in bad) {
            val postsBefore = requests.count { it.method == HttpMethod.Post }
            val outcome = thinq.setState(AcCommand.SetTargetTemperature(REQUESTED_AT, value, TemperatureUnit.CELSIUS))
            assertEquals(CommandStatus.FAILED, outcome.status, "value $value must be rejected")
            assertEquals(CommandFailure.INVALID_REQUEST, outcome.failure, "value $value")
            assertEquals(postsBefore, requests.count { it.method == HttpMethod.Post }, "value $value must never POST")
        }
    }

    @Test
    fun temperatureBodyNeverReportsAUnitAbsentFromTheProfile() = runBlocking {
        val fOnlyBody = response(
            propertyOf(temperatureProperty(tempBlock("F", """{"min":64,"max":86,"step":1}"""))),
        )
        val requests = mutableListOf<HttpRequestData>()
        val thinq = gatingClient(requests, fOnlyBody, stateJsonFOnly(targetF = 77))

        // A Celsius request converts into the profile's F block and is reported in F.
        val outcome = thinq.setState(AcCommand.SetTargetTemperature(REQUESTED_AT, 25.0, TemperatureUnit.CELSIUS))
        val body = requestBody(requests.last { it.method == HttpMethod.Post })
        assertTrue(body.contains("\"unit\":\"F\""), "converted body reports in the profile's unit: $body")
        assertFalse(body.contains("\"unit\":\"C\""), "a unit absent from the profile must never be enabled: $body")
        assertEquals(CommandStatus.CONFIRMED, outcome.status)
    }

    @Test
    fun powerCommandsAreGatedOnBothDirectionsBeingWritable() = runBlocking {
        // One-direction-only power never lets any command through.
        val oneSidedBody = response(propertyOf(powerProperty("""["POWER_ON"]""")))
        val oneSidedRequests = mutableListOf<HttpRequestData>()
        val oneSided = gatingClient(oneSidedRequests, oneSidedBody, stateJson())
        assertEquals(
            CommandFailure.INVALID_REQUEST,
            oneSided.setState(AcCommand.SetPower(REQUESTED_AT, powerOn = true)).failure,
        )
        assertEquals(
            CommandFailure.INVALID_REQUEST,
            oneSided.setState(AcCommand.SetPower(REQUESTED_AT, powerOn = false)).failure,
        )
        assertEquals(0, oneSidedRequests.count { it.method == HttpMethod.Post })

        // Both directions writable: exact wire strings round-trip.
        val fullBody = response(propertyOf(powerProperty("""["POWER_ON","POWER_OFF"]""")))
        val requests = mutableListOf<HttpRequestData>()
        val full = gatingClient(requests, fullBody, stateJson())
        val on = full.setState(AcCommand.SetPower(REQUESTED_AT, powerOn = true))
        assertTrue(on.status == CommandStatus.CONFIRMED || on.status == CommandStatus.UNCONFIRMED)
        assertTrue(requestBody(requests.last { it.method == HttpMethod.Post }).contains("\"airConOperationMode\":\"POWER_ON\""))
        val off = full.setState(AcCommand.SetPower(REQUESTED_AT, powerOn = false))
        assertTrue(off.status == CommandStatus.CONFIRMED || off.status == CommandStatus.UNCONFIRMED)
        assertTrue(requestBody(requests.last { it.method == HttpMethod.Post }).contains("\"airConOperationMode\":\"POWER_OFF\""))
    }

    @Test
    fun modeAndFanCommandsWriteOnlyExplicitProfileStrings() = runBlocking {
        val profileBody = response(
            propertyOf(
                modeProperty("""["COOL","SUPER_CHILL","DRY"]"""),
                fanProperty(windWrite = """["LOW","ZEPHYR","HIGH","AUTO"]"""),
            ),
        )
        val requests = mutableListOf<HttpRequestData>()
        val thinq = gatingClient(requests, profileBody, stateJson())

        // Never sent for values the profile did not list.
        assertEquals(CommandFailure.INVALID_REQUEST, thinq.setState(AcCommand.SetMode(REQUESTED_AT, AcOperatingMode.HEAT)).failure)
        assertEquals(CommandFailure.INVALID_REQUEST, thinq.setState(AcCommand.SetFanSpeed(REQUESTED_AT, FanSpeed.TURBO)).failure)
        assertEquals(0, requests.count { it.method == HttpMethod.Post })

        // Sent values round-trip the profile's exact LG strings under the exact keys.
        val mode = thinq.setState(AcCommand.SetMode(REQUESTED_AT, AcOperatingMode.COOL))
        assertEquals(CommandStatus.CONFIRMED, mode.status)
        assertTrue(requestBody(requests.last { it.method == HttpMethod.Post }).contains("\"airConJobMode\":{\"currentJobMode\":\"COOL\"}"))

        val fan = thinq.setState(AcCommand.SetFanSpeed(REQUESTED_AT, FanSpeed.LOW))
        assertTrue(fan.status == CommandStatus.CONFIRMED || fan.status == CommandStatus.UNCONFIRMED)
        assertTrue(requestBody(requests.last { it.method == HttpMethod.Post }).contains("\"airFlow\":{\"windStrength\":\"LOW\"}"))
    }

    private companion object {
        const val REQUESTED_AT = 1_799_000_000_000L
    }
}
