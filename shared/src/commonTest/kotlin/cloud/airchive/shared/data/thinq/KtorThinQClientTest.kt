package cloud.airchive.shared.data.thinq

import cloud.airchive.shared.contract.thinq.AcCommand
import cloud.airchive.shared.contract.thinq.AcOperatingMode
import cloud.airchive.shared.contract.thinq.CommandFailure
import cloud.airchive.shared.contract.thinq.CommandStatus
import cloud.airchive.shared.contract.thinq.FanSpeed
import cloud.airchive.shared.contract.thinq.TemperatureUnit
import cloud.airchive.shared.contract.thinq.ThinQCredentialVerificationRequest
import cloud.airchive.shared.contract.thinq.ThinQVerificationResult
import cloud.airchive.shared.fakes.FakeThinQCredentialStore
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockRequestHandleScope
import io.ktor.client.engine.mock.respond
import io.ktor.client.plugins.HttpRequestTimeoutException
import io.ktor.client.request.HttpRequestData
import io.ktor.client.request.HttpResponseData
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.TextContent
import io.ktor.http.headersOf
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking

/**
 * Deterministic wire tests for [KtorThinQClient] driven by Ktor's MockEngine
 * (OpenSpec task 4.1). Every token is a synthetic, `.example.invalid`-branded
 * fixture — never a real credential — and every LG response body is a local
 * fixture, so no live LG account or network is involved.
 *
 * Proves: exact per-country URLs and the full header set (stable client ID
 * included); device list/profile/live-state parsing; all four command JSON
 * bodies plus `x-conditional-control`; token-missing and invalid-token paths;
 * rate limiting; the distinct no-control-authority response on a command (and
 * that a verified read never claims it); transport failure; malformed JSON;
 * wrong-account/invalid/network verification outcomes; locally rejected
 * unsupported controls; and that no exception/request/outcome string ever
 * contains a raw PAT or raw server error text.
 */
class KtorThinQClientTest {

    private val pat = "thinq-pat.example.invalid-fixture-9f3a"
    private val stableClientId = "stable-install-9f3a"
    private val deviceId = "ac-device-1"
    private val fixedNow = 1_800_000_000_000L

    // ---------------------------------------------------------- HTTP helpers

    private fun client(
        requests: MutableList<HttpRequestData>,
        store: FakeThinQCredentialStore = FakeThinQCredentialStore(stableClientId),
        handler: suspend MockRequestHandleScope.(HttpRequestData) -> HttpResponseData,
    ): KtorThinQClient {
        val engine = MockEngine { request ->
            requests += request
            handler(request)
        }
        return KtorThinQClient(engine, store, deviceId) { fixedNow }
    }

    private fun MockRequestHandleScope.jsonOk(body: String): HttpResponseData =
        respond(body, HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json"))

    private fun MockRequestHandleScope.jsonError(
        code: String,
        message: String,
        status: HttpStatusCode = HttpStatusCode.BadRequest,
    ): HttpResponseData = respond(
        """{"error":{"code":"$code","message":"$message"}}""".trimIndent(),
        status,
        headersOf(HttpHeaders.ContentType, "application/json"),
    )

    private suspend fun stored(store: FakeThinQCredentialStore = FakeThinQCredentialStore(stableClientId)): FakeThinQCredentialStore {
        store.save(pat, "PH")
        return store
    }

    private fun requestBody(request: HttpRequestData): String =
        (request.body as? TextContent)?.text.orEmpty()

    // ------------------------------------------------------------- fixtures

    private val deviceListJson = """
        {"response": [
          {"deviceId":"ac-device-1","deviceInfo":{"deviceType":"DEVICE_AIR_CONDITIONER","modelName":"PC18SQ.NSD","alias":"Room AC","reportable":true,"online":true}},
          {"deviceId":"wash-9","deviceInfo":{"deviceType":"DEVICE_WASHER","alias":"Washer"}}
        ]}
    """.trimIndent()

    private val profileJson = """
        {"response":{"property":{
          "operation":{
            "airConOperationMode":{"type":"enum","unit":"bool_enum","readable":true,"writable":true,
              "read_values":["AI","POWER_ON","POWER_OFF"],"write_values":["POWER_ON","POWER_OFF"]},
            "airCleanOperationMode":{"type":"enum","readable":true,"writable":false,
              "read_values":["CLEAN","FILTER_MAINTENANCE"]}
          },
          "airConJobMode":{
            "currentJobMode":{"type":"enum","readable":true,"writable":true,
              "read_values":["COOL","HEAT","DRY","FAN","AUTO","ENERGY_SAVING"],
              "write_values":["COOL","HEAT","DRY","FAN","AUTO","ENERGY_SAVING"]}
          },
          "temperatureInUnits":[
            {"unit":"C",
             "currentTemperature":{"type":"range","unit":"degC","readable":true,"writable":false,"read_values":{"min":18,"max":30,"step":1}},
             "targetTemperature":{"type":"range","unit":"degC","readable":true,"writable":true,"read_values":{"min":18,"max":30,"step":1},"write_values":{"min":18,"max":30,"step":1}}},
            {"unit":"F",
             "currentTemperature":{"type":"range","unit":"degF","readable":true,"writable":false,"read_values":{"min":64,"max":86,"step":1}},
             "targetTemperature":{"type":"range","unit":"degF","readable":true,"writable":true,"read_values":{"min":64,"max":86,"step":1},"write_values":{"min":64,"max":86,"step":1}}}
          ],
          "airFlow":{
            "windStrength":{"type":"enum","readable":true,"writable":true,
              "read_values":["LOW","LOW_MID","MID","MID_HIGH","HIGH","POWER","AUTO"],
              "write_values":["LOW","LOW_MID","MID","MID_HIGH","HIGH","POWER","AUTO"]}
          }
        }}}
    """.trimIndent()

    private fun stateJson(
        power: String = "POWER_ON",
        mode: String = "COOL",
        wind: String = "MID",
        targetC: Int = 25,
    ): String = """
        {"response":{
          "operation":{"airConOperationMode":"$power","airCleanOperationMode":null},
          "airConJobMode":{"currentJobMode":"$mode"},
          "temperatureInUnits":[
            {"unit":"C","currentTemperature":24,"targetTemperature":$targetC},
            {"unit":"F","currentTemperature":75,"targetTemperature":77}
          ],
          "airFlow":{"windStrength":"$wind"}
        }}
    """.trimIndent()

    private val profileFOnlyJson = """
        {"response":{"property":{
          "operation":{
            "airConOperationMode":{"type":"enum","unit":"bool_enum","readable":true,"writable":true,
              "read_values":["POWER_ON","POWER_OFF"],"write_values":["POWER_ON","POWER_OFF"]}
          },
          "airConJobMode":{
            "currentJobMode":{"type":"enum","readable":true,"writable":true,
              "read_values":["COOL"],"write_values":["COOL"]}
          },
          "temperatureInUnits":[
            {"unit":"F",
             "targetTemperature":{"type":"range","unit":"degF","readable":true,"writable":true,
               "read_values":{"min":64,"max":86,"step":1},"write_values":{"min":64,"max":86,"step":1}}}
          ],
          "airFlow":{"windStrength":{"type":"enum","readable":true,"writable":true,
            "read_values":["LOW","MID","HIGH"],"write_values":["LOW","MID","HIGH"]}}
        }}}
    """.trimIndent()

    private val profileWithoutControlsJson = """
        {"response":{"property":{
          "operation":{
            "airConOperationMode":{"type":"enum","readable":true,"writable":false,"read_values":["AI","POWER_ON"]}
          },
          "airConJobMode":{"currentJobMode":{"type":"enum","readable":true,"writable":false,"read_values":["COOL"]}},
          "airFlow":{"windStrength":{"type":"enum","readable":true,"writable":false,"read_values":["MID"]}}
        }}}
    """.trimIndent()

    private val profileWithDetailFanJson = """
        {"response":{"property":{
          "airFlow":{"windStrengthDetail":{"type":"enum","readable":true,"writable":true,
            "read_values":["MID"],"write_values":["MID"]}}
        }}}
    """.trimIndent()

    private val stateFOnlyJson = """
        {"response":{
          "operation":{"airConOperationMode":"POWER_ON"},
          "airConJobMode":{"currentJobMode":"COOL"},
          "temperatureInUnits":[{"unit":"F","currentTemperature":75,"targetTemperature":77}],
          "airFlow":{"windStrength":"MID"}
        }}
    """.trimIndent()

    // --------------------------------------------- URL, region, and headers

    @Test
    fun deviceListHitsExactKicUrlAndFullHeaderSet() = runBlocking {
        val requests = mutableListOf<HttpRequestData>()
        val thinq = client(requests, stored()) {
            jsonOk(deviceListJson)
        }

        val devices = thinq.listDevices()

        assertEquals(1, requests.size)
        val request = requests.single()
        assertEquals("https://api-kic.lgthinq.com/devices", request.url.toString())
        assertEquals("/devices", request.url.encodedPath)
        assertEquals(HttpMethod.Get, request.method)

        val headers = request.headers
        assertEquals("Bearer $pat", headers[HttpHeaders.Authorization])
        assertEquals("PH", headers["x-country"])
        assertEquals(stableClientId, headers["x-client-id"])
        assertEquals(KtorThinQClient.THINQ_PUBLIC_SDK_API_KEY, headers["x-api-key"])
        assertEquals("OP", headers["x-service-phase"])
        assertNull(headers["x-conditional-control"], "reads must not carry the conditional-control header")

        assertEquals(listOf("ac-device-1", "wash-9"), devices.map { it.deviceId })
        assertEquals("Room AC", devices.first().alias)
        assertEquals("PC18SQ.NSD", devices.first().modelName)
        assertTrue(devices.first().online)
        assertEquals("DEVICE_AIR_CONDITIONER", devices.first().deviceType)
        assertFalse(devices.last().online, "missing online state is never treated as online")
        assertEquals("DEVICE_WASHER", devices.last().deviceType)
    }

    @Test
    fun regionRoutingFollowsCountryOnTheWire() = runBlocking {
        for ((country, expectedHost) in listOf("PH" to "api-kic.lgthinq.com", "US" to "api-aic.lgthinq.com", "DE" to "api-eic.lgthinq.com")) {
            val store = FakeThinQCredentialStore(stableClientId)
            store.save(pat, country)
            val requests = mutableListOf<HttpRequestData>()
            val thinq = client(requests, store) { jsonOk(deviceListJson) }
            thinq.listDevices()
            assertEquals("https://$expectedHost/devices", requests.single().url.toString(), "country $country")
        }
    }

    @Test
    fun messageIdIsBase64UrlUuidNoPaddingAndUniquePerRequest() = runBlocking {
        val requests = mutableListOf<HttpRequestData>()
        val thinq = client(requests, stored()) { jsonOk(deviceListJson) }
        repeat(2) { thinq.listDevices() }

        val ids = requests.map { assertNotNull(it.headers["x-message-id"]) }
        assertEquals(2, ids.toSet().size, "message ids must differ per request")
        for (id in ids) {
            assertTrue(id.matches(Regex("^[A-Za-z0-9_-]{22}$")), "must be 22-char base64url without padding: $id")
        }
    }

    // ------------------------------------------- successful read parsing

    @Test
    fun profileParsingDerivesWritableControlsOnly() = runBlocking {
        val requests = mutableListOf<HttpRequestData>()
        val thinq = client(requests, stored()) { jsonOk(profileJson) }

        val profile = thinq.profile(deviceId)

        assertEquals("https://api-kic.lgthinq.com/devices/ac-device-1/profile", requests.single().url.toString())
        assertTrue(profile.controls.powerWritable)
        val temp = assertNotNull(profile.controls.targetTemperature)
        assertEquals(18.0, temp.min)
        assertEquals(30.0, temp.max)
        assertEquals(1.0, temp.step)
        assertEquals(TemperatureUnit.CELSIUS, temp.unit)
        assertEquals(TemperatureUnit.CELSIUS, profile.controls.temperatureUnit)
        assertEquals(
            listOf(AcOperatingMode.COOL, AcOperatingMode.HEAT, AcOperatingMode.DRY, AcOperatingMode.FAN, AcOperatingMode.AUTO, AcOperatingMode.POWER_SAVE),
            profile.controls.allowedModes,
        )
        // LOW_MID / MID_HIGH have no contract slot yet and must be dropped.
        assertEquals(
            listOf(FanSpeed.LOW, FanSpeed.MEDIUM, FanSpeed.HIGH, FanSpeed.TURBO, FanSpeed.AUTO),
            profile.controls.allowedFanSpeeds,
        )
    }

    @Test
    fun liveStateParsesAndCarriesItsOwnObservationTime() = runBlocking {
        val requests = mutableListOf<HttpRequestData>()
        val thinq = client(requests, stored()) {
            when (requests.size) {
                1 -> jsonOk(profileJson)
                else -> jsonOk(stateJson())
            }
        }

        val state = thinq.liveState(deviceId)

        assertEquals(2, requests.size)
        assertEquals("/devices/ac-device-1/profile", requests[0].url.encodedPath)
        assertEquals("/devices/ac-device-1/state", requests[1].url.encodedPath)
        assertTrue(state.powerOn)
        assertEquals(25.0, state.targetTemperature, "F block (77°F) is normalized into the profile's °C")
        assertEquals(AcOperatingMode.COOL, state.mode)
        assertEquals(FanSpeed.MEDIUM, state.fanSpeed)
        assertEquals(fixedNow, state.observedAtEpochMillis)
    }

    // ------------------------------------------------ command JSON + confirm

    @Test
    fun setPowerSendsBodyAndConditionalHeaderAndConfirmsOnFreshState() = runBlocking {
        val requests = mutableListOf<HttpRequestData>()
        val thinq = client(requests, stored()) {
            when (requests.size) {
                1 -> jsonOk(profileJson)
                2 -> {
                    val post = requests.last()
                    assertEquals(HttpMethod.Post, post.method)
                    assertEquals("/devices/ac-device-1/control", post.url.encodedPath)
                    assertEquals("true", post.headers["x-conditional-control"])
                    assertTrue(requestBody(post).contains("\"operation\":{\"airConOperationMode\":\"POWER_ON\"}"))
                    assertFalse(requestBody(post).contains(pat), "the control body must never embed the PAT")
                    jsonOk("""{"message":"control accepted"}""")
                }

                else -> jsonOk(stateJson(power = "POWER_ON"))
            }
        }

        val outcome = thinq.setState(AcCommand.SetPower(REQUESTED_AT, powerOn = true))

        assertEquals(CommandStatus.CONFIRMED, outcome.status)
        assertNull(outcome.failure)
        assertEquals(fixedNow, outcome.observedAtEpochMillis)
        assertTrue(outcome.observedState?.powerOn == true)
        assertEquals(3, requests.size)
    }

    @Test
    fun setTargetTemperatureSendsValueAndUnit() = runBlocking {
        val requests = mutableListOf<HttpRequestData>()
        val thinq = client(requests, stored()) {
            when (requests.size) {
                1 -> jsonOk(profileJson)
                2 -> {
                    val body = requestBody(requests.last())
                    assertTrue(body.contains("\"targetTemperature\":26"), "body: $body")
                    assertTrue(body.contains("\"unit\":\"C\""), "body: $body")
                    jsonOk("""{"message":"ok"}""")
                }

                else -> jsonOk(stateJson(targetC = 26))
            }
        }

        val outcome = thinq.setState(AcCommand.SetTargetTemperature(REQUESTED_AT, value = 26.0, unit = TemperatureUnit.CELSIUS))
        assertEquals(CommandStatus.CONFIRMED, outcome.status)
        assertEquals(26.0, outcome.observedState?.targetTemperature)
    }

    @Test
    fun setModeAndFanUseProfileWriteStrings() = runBlocking {
        val requests = mutableListOf<HttpRequestData>()
        val thinq = client(requests, stored()) {
            when (requests.size) {
                1 -> jsonOk(profileJson)
                2 -> {
                    val body = requestBody(requests.last())
                    assertTrue(body.contains("\"airConJobMode\":{\"currentJobMode\":\"COOL\"}"), "mode body: $body")
                    jsonOk("""{"message":"ok"}""")
                }

                else -> jsonOk(stateJson())
            }
        }
        val mode = thinq.setState(AcCommand.SetMode(REQUESTED_AT, AcOperatingMode.COOL))
        assertEquals(CommandStatus.CONFIRMED, mode.status)

        val fanRequests = mutableListOf<HttpRequestData>()
        val fanClient = client(fanRequests, stored()) {
            when (fanRequests.size) {
                1 -> jsonOk(profileJson)
                2 -> {
                    val body = requestBody(fanRequests.last())
                    assertTrue(body.contains("\"airFlow\":{\"windStrength\":\"MID\"}"), "fan body: $body")
                    jsonOk("""{"message":"ok"}""")
                }

                else -> jsonOk(stateJson())
            }
        }
        val fan = fanClient.setState(AcCommand.SetFanSpeed(REQUESTED_AT, FanSpeed.MEDIUM))
        assertEquals(CommandStatus.CONFIRMED, fan.status)
    }

    @Test
    fun fanCommandUsesTheExactProfilePropertyKeySelectedByTheSdk() = runBlocking {
        val requests = mutableListOf<HttpRequestData>()
        val thinq = client(requests, stored()) {
            when (requests.size) {
                1 -> jsonOk(profileWithDetailFanJson)
                2 -> {
                    assertTrue(requestBody(requests.last()).contains("\"windStrengthDetail\":\"MID\""))
                    jsonOk("""{"message":"accepted"}""")
                }
                else -> jsonOk(
                    """{"response":{"operation":{"airConOperationMode":"POWER_ON"},"airFlow":{"windStrengthDetail":"MID"}}}""",
                )
            }
        }

        val outcome = thinq.setState(AcCommand.SetFanSpeed(REQUESTED_AT, FanSpeed.MEDIUM))

        assertEquals(CommandStatus.CONFIRMED, outcome.status)
        assertEquals(1, requests.count { it.method == HttpMethod.Post })
    }

    @Test
    fun acceptedCommandWithDifferentObservedStateIsUnconfirmedNeverResent() = runBlocking {
        val requests = mutableListOf<HttpRequestData>()
        val thinq = client(requests, stored()) {
            when (requests.size) {
                1 -> jsonOk(profileJson)
                2 -> jsonOk("""{"message":"accepted"}""")
                else -> jsonOk(stateJson(power = "POWER_OFF"))
            }
        }

        val outcome = thinq.setState(AcCommand.SetPower(REQUESTED_AT, powerOn = true))

        assertEquals(CommandStatus.UNCONFIRMED, outcome.status)
        assertNull(outcome.failure)
        assertEquals(3, requests.size, "exactly one control POST, no retry")
        assertTrue(requests.count { it.method == HttpMethod.Post } == 1, "an ambiguous command is never auto-resent")
    }

    @Test
    fun singleUnitFahrenheitProfileAcceptsConvertedCelsiusCommand() = runBlocking {
        val requests = mutableListOf<HttpRequestData>()
        val thinq = client(requests, stored()) {
            when (requests.size) {
                1 -> jsonOk(profileFOnlyJson)
                2 -> {
                    val body = requestBody(requests.last())
                    assertTrue(body.contains("\"targetTemperature\":77.0"), "converted to °F: $body")
                    assertTrue(body.contains("\"unit\":\"F\""), "body: $body")
                    jsonOk("""{"message":"ok"}""")
                }

                else -> jsonOk(stateFOnlyJson)
            }
        }

        val outcome = thinq.setState(AcCommand.SetTargetTemperature(REQUESTED_AT, value = 25.0, unit = TemperatureUnit.CELSIUS))
        assertEquals(CommandStatus.CONFIRMED, outcome.status)
        assertEquals(77.0, outcome.observedState?.targetTemperature)
    }

    // ---------------------------------------------- locally rejected controls

    @Test
    fun unsupportedControlIsRejectedLocallyWithoutAnyControlPost() = runBlocking {
        val requests = mutableListOf<HttpRequestData>()
        val thinq = client(requests, stored()) {
            if (requests.last().url.encodedPath == "/devices/ac-device-1/profile") {
                jsonOk(profileWithoutControlsJson)
            } else {
                error("no control request may be sent for an unsupported control")
            }
        }
        val fan = thinq.setState(AcCommand.SetFanSpeed(REQUESTED_AT, FanSpeed.TURBO))
        assertEquals(CommandStatus.FAILED, fan.status)
        assertEquals(CommandFailure.INVALID_REQUEST, fan.failure)
        assertEquals(1, requests.size, "only the profile read happened; an unsupported control never POSTs")

        val mode = thinq.setState(AcCommand.SetMode(REQUESTED_AT, AcOperatingMode.HEAT))
        assertEquals(CommandStatus.FAILED, mode.status)
        assertEquals(CommandFailure.INVALID_REQUEST, mode.failure)
        assertEquals(2, requests.size, "still only profile reads; no control request")
    }

    @Test
    fun temperatureOutsideProfileBoundsIsRejectedWithoutAnyControlPost() = runBlocking {
        val requests = mutableListOf<HttpRequestData>()
        val thinq = client(requests, stored()) {
            if (requests.size == 1) jsonOk(profileJson)
            else error("no control request may be sent for an out-of-bounds temperature")
        }

        val outcome = thinq.setState(AcCommand.SetTargetTemperature(REQUESTED_AT, value = 31.0, unit = TemperatureUnit.CELSIUS))
        assertEquals(CommandStatus.FAILED, outcome.status)
        assertEquals(CommandFailure.INVALID_REQUEST, outcome.failure)
        assertEquals(1, requests.size, "only the profile read happened; no POST")
    }

    // --------------------------------------------------- credential hygiene

    @Test
    fun missingTokenFailsWithoutAnyNetworkAndDistinctFailure() = runBlocking {
        val requests = mutableListOf<HttpRequestData>()
        val thinq = client(requests) { jsonOk(deviceListJson) } // store has no credential

        val outcome = thinq.setState(AcCommand.SetPower(REQUESTED_AT, powerOn = true))
        assertEquals(CommandStatus.FAILED, outcome.status)
        assertEquals(CommandFailure.TOKEN_REJECTED, outcome.failure)
        assertTrue(requests.isEmpty(), "no credential means no request")

        assertFailsWith<ThinQClientException.MissingCredential> { thinq.listDevices() }
        assertTrue(requests.isEmpty())
    }

    @Test
    fun blankAndHeaderInjectedCredentialsAreRejectedBeforeNetwork() = runBlocking {
        val requests = mutableListOf<HttpRequestData>()
        val corruptStore = FakeThinQCredentialStore(stableClientId).apply {
            save("synthetic-pat.example.invalid\r\nX-Injected: yes", "PH")
        }
        val thinq = client(requests, corruptStore) { jsonOk(deviceListJson) }

        assertFailsWith<ThinQClientException.InvalidCredential> { thinq.listDevices() }
        assertEquals(
            ThinQVerificationResult.InvalidToken,
            thinq.verify(verificationRequest(rawToken = "synthetic-pat.example.invalid\r\nX-Injected: yes")),
        )
        assertEquals(ThinQVerificationResult.InvalidToken, thinq.verify(verificationRequest(rawToken = "")))
        assertTrue(requests.isEmpty(), "unsafe or blank bearer values must never reach the engine")
    }

    @Test
    fun invalidTokenClassifiesOnReadsAndCommands() = runBlocking {
        val requests = mutableListOf<HttpRequestData>()
        val read = client(requests, stored()) {
            jsonError("1103", "invalid token", HttpStatusCode.Unauthorized)
        }
        val failure = assertFailsWith<ThinQClientException.RemoteFailure> { read.listDevices() }
        assertEquals(ThinQRemoteError.InvalidToken, failure.error)
        assertEquals(ThinQReadFailure.INVALID_TOKEN, failure.kind)

        val cmdRequests = mutableListOf<HttpRequestData>()
        val cmd = client(cmdRequests, stored()) {
            if (cmdRequests.size == 1) {
                jsonError("1218", "token rejected again", HttpStatusCode.Unauthorized)
            } else {
                error("must not reach control")
            }
        }
        val outcome = cmd.setState(AcCommand.SetPower(REQUESTED_AT, powerOn = true))
        assertEquals(CommandStatus.FAILED, outcome.status)
        assertEquals(CommandFailure.TOKEN_REJECTED, outcome.failure)
    }

    @Test
    fun rateLimitingClassifiesOnCommand() = runBlocking {
        val requests = mutableListOf<HttpRequestData>()
        val thinq = client(requests, stored()) {
            when (requests.size) {
                1 -> jsonOk(profileJson)
                else -> jsonError("1306", "exceeded api calls", HttpStatusCode.TooManyRequests)
            }
        }
        val outcome = thinq.setState(AcCommand.SetPower(REQUESTED_AT, powerOn = true))
        assertEquals(CommandStatus.FAILED, outcome.status)
        assertEquals(CommandFailure.RATE_LIMITED, outcome.failure)
    }

    @Test
    fun emptyHttp429StillClassifiesAsRateLimited() = runBlocking {
        val requests = mutableListOf<HttpRequestData>()
        val thinq = client(requests, stored()) {
            respond("", HttpStatusCode.TooManyRequests)
        }

        val failure = assertFailsWith<ThinQClientException.RemoteFailure> { thinq.listDevices() }
        assertEquals(ThinQRemoteError.RateLimited, failure.error)
        assertEquals(ThinQReadFailure.RATE_LIMITED, failure.kind)
    }

    @Test
    fun noControlAuthorityIsADistinctCommandResponseAndReadsNeverClaimIt() = runBlocking {
        val requests = mutableListOf<HttpRequestData>()
        val thinq = client(requests, stored()) {
            when (requests.size) {
                1 -> jsonOk(profileJson)
                else -> jsonError("1308", "no control authority", HttpStatusCode.Forbidden)
            }
        }
        val outcome = thinq.setState(AcCommand.SetMode(REQUESTED_AT, AcOperatingMode.COOL))
        assertEquals(CommandStatus.FAILED, outcome.status)
        assertEquals(CommandFailure.NO_CONTROL_AUTHORITY, outcome.failure)
        assertEquals("ThinQ denies control authority for this token.", outcome.message)
    }

    // --------------------------------------------------- transport + malformed

    @Test
    fun transportFailureClassifiesOnReadsCommandsAndVerification() = runBlocking {
        val requests = mutableListOf<HttpRequestData>()
        val thinq = client(requests, stored()) {
            throw IllegalStateException("synthetic network boom")
        }

        assertFailsWith<ThinQClientException.Network> { thinq.listDevices() }

        val commandRequests = mutableListOf<HttpRequestData>()
        val commandClient = client(commandRequests, stored()) {
            if (commandRequests.last().method == HttpMethod.Get) {
                jsonOk(profileJson)
            } else {
                throw IllegalStateException("synthetic connection dropped after control send")
            }
        }
        val outcome = commandClient.setState(AcCommand.SetPower(REQUESTED_AT, powerOn = true))
        assertEquals(CommandStatus.UNCONFIRMED, outcome.status, "POST transport failure has an ambiguous outcome")
        assertEquals(CommandFailure.NETWORK, outcome.failure)
        assertTrue(outcome.message.orEmpty().contains("may have been accepted"))
        assertEquals(1, commandRequests.count { it.method == HttpMethod.Post }, "an uncertain POST is never retried")

        val verify = client(mutableListOf()) { throw IllegalStateException("synthetic network boom") }
        assertEquals(
            ThinQVerificationResult.Network,
            verify.verify(verificationRequest()),
        )
    }

    @Test
    fun explicitRequestTimeoutMakesControlOutcomeUnconfirmedWithoutRetry() = runBlocking {
        val requests = mutableListOf<HttpRequestData>()
        val thinq = client(requests, stored()) {
            if (requests.last().method == HttpMethod.Get) {
                jsonOk(profileJson)
            } else {
                throw HttpRequestTimeoutException(requests.last().url.toString(), 1L, null)
            }
        }

        val outcome = thinq.setState(AcCommand.SetPower(REQUESTED_AT, powerOn = true))

        assertEquals(CommandStatus.UNCONFIRMED, outcome.status)
        assertEquals(CommandFailure.TIMEOUT, outcome.failure)
        assertTrue(outcome.message.orEmpty().contains("was not resent"))
        assertEquals(1, requests.count { it.method == HttpMethod.Post })
    }

    @Test
    fun malformedJsonClassifiesOnReadsCommandsAndVerification() = runBlocking {
        val requests = mutableListOf<HttpRequestData>()
        val thinq = client(requests, stored()) { jsonOk("this is not json") }
        assertFailsWith<ThinQClientException.MalformedResponse> { thinq.listDevices() }

        val cmdRequests = mutableListOf<HttpRequestData>()
        val cmd = client(cmdRequests, stored()) { jsonOk("this is not json") }
        val outcome = cmd.setState(AcCommand.SetPower(REQUESTED_AT, powerOn = true))
        assertEquals(CommandStatus.FAILED, outcome.status)
        assertEquals(CommandFailure.UNKNOWN, outcome.failure)

        val verify = client(mutableListOf(), stored()) { jsonOk("this is not json") }
        assertEquals(ThinQVerificationResult.RemoteFailure, verify.verify(verificationRequest()))
    }

    // ------------------------------------------------------- verification

    @Test
    fun verifyWrongAccountWhenExpectedDeviceIsMissing() = runBlocking {
        val requests = mutableListOf<HttpRequestData>()
        val thinq = client(requests, stored()) { jsonOk(deviceListJson) }
        assertEquals(
            ThinQVerificationResult.WrongAccount,
            thinq.verify(verificationRequest(expectedDeviceId = "some-other-ac")),
        )
    }

    @Test
    fun verificationRejectsMatchingIdThatIsNotAnAirConditioner() = runBlocking {
        val requests = mutableListOf<HttpRequestData>()
        val thinq = client(requests, stored()) { jsonOk(deviceListJson) }

        val result = thinq.verify(verificationRequest(expectedDeviceId = "wash-9"))

        assertEquals(ThinQVerificationResult.WrongAccount, result)
        assertEquals(1, requests.size, "non-AC identity must not be followed by a profile read")
    }

    @Test
    fun verifyWrongAccountOnProfileErrorCode() = runBlocking {
        val requests = mutableListOf<HttpRequestData>()
        val thinq = client(requests, stored()) {
            if (requests.size == 1) jsonOk(deviceListJson) else jsonError("1205", "no such device")
        }
        assertEquals(ThinQVerificationResult.WrongAccount, thinq.verify(verificationRequest()))
    }

    @Test
    fun verifyInvalidTokenOutcome() = runBlocking {
        val thinq = client(mutableListOf(), stored()) {
            jsonError("1103", "invalid token", HttpStatusCode.Unauthorized)
        }
        assertEquals(ThinQVerificationResult.InvalidToken, thinq.verify(verificationRequest()))
    }

    @Test
    fun verifyOfflineDeviceIsUnavailable() = runBlocking {
        val offlineList = """
            {"response":[{"deviceId":"ac-device-1","deviceInfo":{"deviceType":"DEVICE_AIR_CONDITIONER","alias":"Room AC","online":false}}]}
        """.trimIndent()
        val thinq = client(mutableListOf(), stored()) { jsonOk(offlineList) }
        assertEquals(ThinQVerificationResult.DeviceUnavailable, thinq.verify(verificationRequest()))
    }

    @Test
    fun verifyUnsupportedCountryIsRemoteFailureWithoutAnyRequest() = runBlocking {
        val requests = mutableListOf<HttpRequestData>()
        val thinq = client(requests, stored()) { jsonOk(deviceListJson) }
        assertEquals(
            ThinQVerificationResult.RemoteFailure,
            thinq.verify(verificationRequest(accountCountry = "ZZ")),
        )
        assertTrue(requests.isEmpty(), "an unroutable country must not trigger any request")
    }

    @Test
    fun verifiedReadCarriesExactlyDeviceAndProfileAndNothingElse() = runBlocking {
        val requests = mutableListOf<HttpRequestData>()
        val thinq = client(requests, stored()) {
            if (requests.size == 1) jsonOk(deviceListJson) else jsonOk(profileJson)
        }

        val result = thinq.verify(verificationRequest())
        val verified = assertIs<ThinQVerificationResult.Verified>(result)
        // Destructuring exposes exactly the two data slots the contract allows —
        // there is no field anywhere that claims command/control authority.
        val (device, profile) = verified
        assertEquals(deviceId, device.deviceId)
        assertEquals(deviceId, profile.deviceId)
        assertEquals("Room AC", device.alias)
        assertTrue(profile.controls.powerWritable)
    }

    // ------------------------------------------------- device-id restriction

    @Test
    fun deviceIdIsRestrictedToSingleConfiguredPathSegment() = runBlocking {
        val requests = mutableListOf<HttpRequestData>()
        val thinq = client(requests, stored()) { jsonOk(profileJson) }

        assertFailsWith<IllegalArgumentException> { thinq.profile("other-device") }
        assertFailsWith<IllegalArgumentException> { thinq.profile("a/b") }
        assertFailsWith<IllegalArgumentException> { thinq.profile("../escape") }
        assertFailsWith<IllegalArgumentException> { thinq.profile("..") }
        assertFailsWith<IllegalArgumentException> { thinq.profile("%2Fescape") }
        assertTrue(requests.isEmpty(), "restricted device ids must never reach the engine")

        // The configured device still works.
        assertEquals(deviceId, thinq.profile(deviceId).deviceId)
        assertEquals(1, requests.size)
    }

    @Test
    fun malformedConfiguredDeviceIdIsRejectedBeforeAnyOperation() {
        val engine = MockEngine { jsonOk(deviceListJson) }
        assertFailsWith<IllegalArgumentException> {
            KtorThinQClient(engine, FakeThinQCredentialStore(stableClientId), "../escape")
        }
    }

    @Test
    fun unknownPowerEnumCannotBeMistakenForPowerOffConfirmation() = runBlocking {
        val requests = mutableListOf<HttpRequestData>()
        val thinq = client(requests, stored()) {
            when (requests.size) {
                1 -> jsonOk(profileJson)
                2 -> jsonOk("""{"accepted":true}""")
                else -> jsonOk(stateJson(power = "AI"))
            }
        }

        val outcome = thinq.setState(AcCommand.SetPower(REQUESTED_AT, powerOn = false))

        assertEquals(CommandStatus.UNCONFIRMED, outcome.status)
        assertEquals(CommandFailure.UNKNOWN, outcome.failure)
        assertTrue(outcome.message.orEmpty().contains("fresh state check failed"))
        assertEquals(1, requests.count { it.method == HttpMethod.Post }, "unknown state is never retried")
    }

    @Test
    fun failedFreshStateReadIsUnconfirmedWithSafeActionableCategory() = runBlocking {
        val requests = mutableListOf<HttpRequestData>()
        val thinq = client(requests, stored()) {
            when (requests.size) {
                1 -> jsonOk(profileJson)
                2 -> jsonOk("""{"accepted":true}""")
                else -> jsonError("1103", "raw secret-referencing error", HttpStatusCode.Unauthorized)
            }
        }

        val outcome = thinq.setState(AcCommand.SetPower(REQUESTED_AT, powerOn = true))

        assertEquals(CommandStatus.UNCONFIRMED, outcome.status, "POST was accepted, so command outcome stays ambiguous")
        assertEquals(CommandFailure.TOKEN_REJECTED, outcome.failure)
        assertTrue(outcome.message.orEmpty().contains("token or account"))
        assertFalse(outcome.message.orEmpty().contains("raw secret-referencing error"))
        assertEquals(1, requests.count { it.method == HttpMethod.Post })
    }

    // ------------------------------------------ sanitization proof (task gate)

    @Test
    fun exceptionAndOutcomeStringsNeverContainPatOrRawServerText() = runBlocking {
        val secretPat = "synthetic-pat.example.invalid-7f3d"
        val serverText = "denied control for synthetic-pat.example.invalid-7f3d"
        val requests = mutableListOf<HttpRequestData>()
        val store = FakeThinQCredentialStore(stableClientId)
        store.save(secretPat, "PH")
        val thinq = client(requests, store) {
            jsonError("1308", serverText, HttpStatusCode.Forbidden)
        }

        val failure = assertFailsWith<ThinQClientException.RemoteFailure> { thinq.listDevices() }
        val failureText = failure.toString()
        assertFalse(secretPat in failureText, "exception toString must never contain the PAT")
        assertFalse(serverText in failureText, "exception toString must never contain raw server text")
        assertFalse("denied control" in failureText)

        val outcome = thinq.setState(AcCommand.SetPower(REQUESTED_AT, powerOn = true))
        val outcomeText = outcome.toString()
        assertFalse(secretPat in outcomeText)
        assertFalse(serverText in outcomeText)
        assertEquals("ThinQ denies control authority for this token.", outcome.message)

        // The verification request's own toString is deliberately masked too.
        val verification = ThinQCredentialVerificationRequest(
            rawToken = secretPat,
            accountCountry = "PH",
            clientId = stableClientId,
            expectedDeviceId = deviceId,
        ).toString()
        assertFalse(secretPat in verification)
        assertTrue(secretPat.takeLast(4) in verification)
    }

    @Test
    fun controlRequestBodyNeverEchoesThePat() = runBlocking {
        val requests = mutableListOf<HttpRequestData>()
        val thinq = client(requests, stored()) {
            when (requests.size) {
                1 -> jsonOk(profileJson)
                else -> jsonOk("""{"message":"accepted"}""")
            }
        }
        val outcome = thinq.setState(AcCommand.SetPower(REQUESTED_AT, powerOn = false))
        assertEquals(CommandStatus.UNCONFIRMED, outcome.status)
        val postRequests = requests.filter { it.method == HttpMethod.Post }
        assertEquals(1, postRequests.size)
        for (post in postRequests) {
            // The Authorization header necessarily carries the bearer; the body
            // and any visible payload must never echo it.
            assertFalse(pat in requestBody(post), "the JSON body must never embed the PAT")
        }
    }

    // ----------------------------------------------------------------- helpers

    private fun verificationRequest(
        accountCountry: String = "PH",
        expectedDeviceId: String = deviceId,
        rawToken: String = pat,
    ) = ThinQCredentialVerificationRequest(
        rawToken = rawToken,
        accountCountry = accountCountry,
        clientId = stableClientId,
        expectedDeviceId = expectedDeviceId,
    )

    private companion object {
        const val REQUESTED_AT = 1_799_000_000_000L
    }
}
