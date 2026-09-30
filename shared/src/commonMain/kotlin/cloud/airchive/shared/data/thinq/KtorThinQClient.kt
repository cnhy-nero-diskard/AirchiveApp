package cloud.airchive.shared.data.thinq

import cloud.airchive.shared.contract.credentials.ThinQCredentialStore
import cloud.airchive.shared.contract.thinq.AcCommand
import cloud.airchive.shared.contract.thinq.CommandFailure
import cloud.airchive.shared.contract.thinq.CommandOutcome
import cloud.airchive.shared.contract.thinq.CommandStatus
import cloud.airchive.shared.contract.thinq.TemperatureUnit
import cloud.airchive.shared.contract.thinq.ThinQCredentialVerificationRequest
import cloud.airchive.shared.contract.thinq.ThinQCredentialVerifier
import cloud.airchive.shared.contract.thinq.ThinQDevice
import cloud.airchive.shared.contract.thinq.ThinQDeviceProfile
import cloud.airchive.shared.contract.thinq.ThinQLiveState
import cloud.airchive.shared.contract.thinq.ThinQPort
import cloud.airchive.shared.contract.thinq.ThinQVerificationResult
import io.ktor.client.HttpClient
import io.ktor.client.engine.HttpClientEngine
import io.ktor.client.plugins.HttpRequestTimeoutException
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.request.headers
import io.ktor.client.request.request
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import kotlin.coroutines.cancellation.CancellationException
import kotlin.time.Clock
import kotlinx.coroutines.flow.first
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject

/**
 * Injected-engine Ktor implementation of the narrow [ThinQPort] and the
 * [ThinQCredentialVerifier] (OpenSpec task 4.1).
 *
 * ## Engine and lifecycle
 *
 * The platform [HttpClientEngine] is supplied by the caller (CIO on Android,
 * Darwin on iOS), and this class constructs a bare [HttpClient] around it. No
 * retry or logging plugin is installed, so a control POST cannot be silently
 * repeated and a bearer PAT cannot be printed by a Ktor logging plugin. JSON is
 * parsed directly with `kotlinx.serialization.json`, so no ContentNegotiation
 * plugin is required. The owner of this client must call [close] when its
 * dependency scope ends.
 *
 * ## One token, one country, one device
 *
 * Regular port calls never receive a token from the UI: they read it through
 * [ThinQCredentialStore.readToken], the account country through the store's
 * observable (masked) state, and the stable per-install `clientId` from the same
 * store. All paths are restricted to the single [configuredDeviceId]; a device
 * ID must be exactly one URL path segment.
 *
 * [verify] deliberately ignores the store instead: it uses only the candidate
 * token/country/clientId from its own [ThinQCredentialVerificationRequest].
 *
 * ## Wire contract (mirrors `thinqconnect` 1.0.13)
 *
 * Base URL `https://api-<region>.lgthinq.com` from [regionForCountry]; calls
 * `GET devices`, `GET devices/{id}/profile`, `GET devices/{id}/state`,
 * `POST devices/{id}/control`; headers `Authorization: Bearer <PAT>`,
 * `x-country`, `x-message-id` (base64url of a UUIDv4, no padding),
 * `x-client-id` (stable), `x-api-key` (the SDK's fixed public constant, see
 * [KtorThinQClient.THINQ_PUBLIC_SDK_API_KEY]), `x-service-phase: OP`; control
 * adds `x-conditional-control: true`. Successful reads unwrap
 * `payload.response`.
 *
 * ## Safety invariants
 *
 * - No secret or raw LG text ever appears in a request/exception `toString()`
 *   or command outcome; server error bodies are reduced to a fixed phrase.
 * - No retries are performed inside this client; an ambiguous send is reported
 *   [CommandStatus.UNCONFIRMED] and never auto-resent.
 * - A verified read never claims control authority; 1308 on a command is the
 *   distinct [CommandFailure.NO_CONTROL_AUTHORITY] response.
 */
class KtorThinQClient(
    engine: HttpClientEngine,
    private val credentialStore: ThinQCredentialStore,
    private val configuredDeviceId: String,
    private val nowEpochMillis: () -> Long = { Clock.System.now().toEpochMilliseconds() },
) : ThinQPort, ThinQCredentialVerifier {

    private val httpClient: HttpClient = HttpClient(engine) {
        install(HttpTimeout) {
            requestTimeoutMillis = REQUEST_TIMEOUT_MILLIS
            connectTimeoutMillis = CONNECT_TIMEOUT_MILLIS
            socketTimeoutMillis = SOCKET_TIMEOUT_MILLIS
        }
    }

    init {
        require(ThinQPayloads.isSinglePathSegment(configuredDeviceId)) {
            "Configured ThinQ device ID must be one safe URL path segment."
        }
    }

    // ------------------------------------------------------------ public API

    override suspend fun listDevices(): List<ThinQDevice> {
        val ctx = currentCredentialContext()
        val payload = requestRead(ctx, "devices")
        return ThinQPayloads.deviceList(payload)
    }

    override suspend fun profile(deviceId: String): ThinQDeviceProfile {
        requireConfiguredDevice(deviceId)
        val ctx = currentCredentialContext()
        val payload = requestRead(ctx, "devices/$deviceId/profile")
        return ThinQPayloads.acProfile(deviceId, payload).toContractProfile()
    }

    override suspend fun liveState(deviceId: String): ThinQLiveState {
        requireConfiguredDevice(deviceId)
        val ctx = currentCredentialContext()
        // The profile establishes the presentation unit; each live-state read
        // re-reads it so a stale profile can never mislabel a temperature.
        val profile = fetchProfile(ctx, deviceId)
        val payload = requestRead(ctx, "devices/$deviceId/state")
        return ThinQPayloads.liveState(deviceId, payload, profile.preferredTemperatureUnit, nowEpochMillis())
    }

    override suspend fun setState(command: AcCommand): CommandOutcome {
        val deviceId = configuredDeviceId
        val base = CommandOutcome(
            commandId = "thinq-${ThinQMessageId.next()}",
            deviceId = deviceId,
            command = command,
            status = CommandStatus.SENDING,
            submittedAtEpochMillis = command.requestedAtEpochMillis.takeIf { it > 0 } ?: nowEpochMillis(),
        )
        val ctx = try {
            currentCredentialContext()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: ThinQClientException.MissingCredential) {
            return base.failed(CommandFailure.TOKEN_REJECTED, "No ThinQ credential is stored.")
        } catch (_: ThinQClientException.InvalidCredential) {
            return base.failed(CommandFailure.TOKEN_REJECTED, "ThinQ credential or client identifier is invalid.")
        } catch (_: ThinQClientException.Timeout) {
            return base.failed(CommandFailure.TIMEOUT, "ThinQ credential/profile check timed out before sending the command.")
        } catch (_: Exception) {
            return base.failed(CommandFailure.UNKNOWN, "ThinQ credential state could not be read.")
        }

        // The fresh profile is the source of truth for which values are legal;
        // unsupported properties/values are rejected locally, before any POST.
        val profile = try {
            fetchProfile(ctx, deviceId)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (e: ThinQClientException) {
            return base.mapCommandError(e)
        }
        val body = controlBody(profile, command)
            ?: return base.failed(CommandFailure.INVALID_REQUEST, "Profile does not permit this value.")

        // POST control with x-conditional-control: true. A 2xx is "accepted",
        // nothing more; the confirm step below decides CONFIRMED vs UNCONFIRMED.
        try {
            request(
                method = HttpMethod.Post,
                path = "devices/$deviceId/control",
                body = body,
                conditional = true,
                ctx = ctx,
                successMode = SuccessMode.IGNORE_BODY,
            )
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: ThinQClientException.Timeout) {
            return base.unconfirmed(
                CommandFailure.TIMEOUT,
                "ThinQ did not answer the control request before the deadline; the command may have been accepted. It was not resent; refresh state before acting again.",
            )
        } catch (_: ThinQClientException.Network) {
            return base.unconfirmed(
                CommandFailure.NETWORK,
                "ThinQ did not answer the control request; the command may have been accepted. It was not resent; refresh state before acting again.",
            )
        } catch (e: ThinQClientException) {
            return base.mapCommandError(e)
        }

        // Bounded fresh state read; only an observed matching state is CONFIRMED.
        // A failed confirm read is UNCONFIRMED (ambiguous), never auto-resent.
        var confirmationFailure: CommandFailure? = null
        val fresh = try {
            val payload = requestRead(ctx, "devices/$deviceId/state")
            ThinQPayloads.liveState(deviceId, payload, profile.preferredTemperatureUnit, nowEpochMillis())
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (e: ThinQClientException) {
            confirmationFailure = base.mapCommandError(e).failure
            null
        } catch (_: Exception) {
            confirmationFailure = CommandFailure.UNKNOWN
            null
        }
        val confirmed = fresh != null && matches(command, fresh, profile.preferredTemperatureUnit)
        return base.copy(
            status = if (confirmed) CommandStatus.CONFIRMED else CommandStatus.UNCONFIRMED,
            observedAtEpochMillis = fresh?.observedAtEpochMillis,
            observedState = fresh,
            failure = if (confirmed) null else confirmationFailure,
            message = when {
                confirmed -> null
                confirmationFailure != null ->
                    "Command was accepted, but the fresh state check failed (${failureLabel(requireNotNull(confirmationFailure))}); it was not resent."
                else -> "Accepted, but the AC did not report the requested setting. Refresh state; the command was not resent."
            },
        )
    }

    // ------------------------------------------------------------- verifier

    override suspend fun verify(request: ThinQCredentialVerificationRequest): ThinQVerificationResult {
        val country = request.accountCountry.trim().uppercase()
        if (regionForCountry(country) == null || !ThinQPayloads.isSinglePathSegment(request.expectedDeviceId)) {
            // Nothing can be concluded about a token that cannot be routed.
            return ThinQVerificationResult.RemoteFailure
        }
        if (request.rawToken.isBlank() || !isSafeHeaderValue(request.rawToken)) {
            return ThinQVerificationResult.InvalidToken
        }
        if (!isSafeHeaderValue(request.clientId)) return ThinQVerificationResult.RemoteFailure

        // 1) GET the device list and confirm the exact expected AC is visible.
        val devices = try {
            val payload = requestRead(
                ctx = CredentialContext(request.rawToken, country, request.clientId),
                path = "devices",
            )
            ThinQPayloads.deviceList(payload)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (e: ThinQClientException) {
            return e.mapVerificationError()
        }
        val device = devices.firstOrNull { it.deviceId == request.expectedDeviceId }
            ?: return ThinQVerificationResult.WrongAccount
        if (device.deviceType != null && device.deviceType != DEVICE_TYPE_AIR_CONDITIONER) {
            // A matching id alone is not sufficient to enroll a different LG
            // appliance under the configured air-conditioner account.
            return ThinQVerificationResult.WrongAccount
        }
        if (!device.online) return ThinQVerificationResult.DeviceUnavailable

        // 2) GET the profile of exactly that device.
        val profile = try {
            val payload = requestRead(
                ctx = CredentialContext(request.rawToken, country, request.clientId),
                path = "devices/${device.deviceId}/profile",
            )
            ThinQPayloads.acProfile(device.deviceId, payload).toContractProfile()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (e: ThinQClientException) {
            return e.mapVerificationError()
        }
        // A Verified outcome is exactly device + profile; it explicitly does NOT
        // claim command/control authority (the contract forbids that).
        return ThinQVerificationResult.Verified(device = device, profile = profile)
    }

    /** Closes the privately-created Ktor client and its supplied platform engine. */
    fun close() = httpClient.close()

    // ------------------------------------------------------ internal plumbing

    private data class CredentialContext(val token: String, val country: String, val clientId: String)

    private enum class SuccessMode { REQUIRE_RESPONSE, IGNORE_BODY }

    private suspend fun currentCredentialContext(): CredentialContext {
        val token = credentialStore.readToken()
        val state = try {
            credentialStore.observe().first()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            null
        }
        val country = state?.credential?.accountCountry?.trim()?.uppercase().orEmpty()
        if (token.isNullOrBlank() || country.isBlank()) throw ThinQClientException.MissingCredential
        val clientId = credentialStore.clientId
        if (!isSafeHeaderValue(token) || !isSafeHeaderValue(clientId)) {
            throw ThinQClientException.InvalidCredential
        }
        return CredentialContext(token = token, country = country, clientId = clientId)
    }

    private suspend fun requestRead(ctx: CredentialContext, path: String): JsonElement =
        request(
            method = HttpMethod.Get,
            path = path,
            body = null,
            conditional = false,
            ctx = ctx,
            successMode = SuccessMode.REQUIRE_RESPONSE,
        )

    private suspend fun fetchProfile(ctx: CredentialContext, deviceId: String): ParsedAcProfile {
        val payload = requestRead(ctx, "devices/$deviceId/profile")
        return ThinQPayloads.acProfile(deviceId, payload)
    }

    private suspend fun request(
        method: HttpMethod,
        path: String,
        body: String?,
        conditional: Boolean,
        ctx: CredentialContext,
        successMode: SuccessMode,
    ): JsonElement {
        val baseUrl = regionForCountry(ctx.country)?.baseUrl()
            ?: throw ThinQClientException.UnsupportedCountry
        val url = "$baseUrl/$path"
        val response = try {
            httpClient.request(url) {
                this.method = method
                headers {
                    append(HttpHeaders.Authorization, "Bearer ${ctx.token}")
                    append("x-country", ctx.country)
                    append("x-message-id", ThinQMessageId.next())
                    append("x-client-id", ctx.clientId)
                    append("x-api-key", THINQ_PUBLIC_SDK_API_KEY)
                    append("x-service-phase", SERVICE_PHASE)
                    if (conditional) append("x-conditional-control", "true")
                }
                if (body != null) {
                    contentType(ContentType.Application.Json)
                    setBody(body)
                }
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: HttpRequestTimeoutException) {
            throw ThinQClientException.Timeout
        } catch (_: Exception) {
            throw ThinQClientException.Network
        }

        val text = try {
            response.bodyAsText()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: HttpRequestTimeoutException) {
            throw ThinQClientException.Timeout
        } catch (_: Exception) {
            throw ThinQClientException.Network
        }

        if (response.status.isSuccess()) {
            if (successMode == SuccessMode.IGNORE_BODY) {
                // A 2xx control response is accepted regardless of its body.
                return JsonNull
            }
            val payload = try {
                Json.parseToJsonElement(text)
            } catch (_: Exception) {
                throw ThinQClientException.MalformedResponse
            }
            val unwrapped = (payload as? JsonObject)?.get("response")
                ?: throw ThinQClientException.MalformedResponse
            return unwrapped
        }
        throw ThinQClientException.RemoteFailure(
            parseRemoteError(text, isCommand = conditional, httpStatus = response.status.value),
        )
    }

    // -------------------------------------------------------- command mapping

    private fun controlBody(profile: ParsedAcProfile, command: AcCommand): String? = when (command) {
        is AcCommand.SetPower -> {
            if (!profile.powerWritable) null
            else jsonBody {
                putJsonObject("operation") {
                    put("airConOperationMode", if (command.powerOn) "POWER_ON" else "POWER_OFF")
                }
            }
        }

        is AcCommand.SetTargetTemperature -> temperatureBody(profile, command)

        is AcCommand.SetMode -> profile.modeWriteStrings[command.mode]?.let { write ->
            jsonBody { putJsonObject("airConJobMode") { put("currentJobMode", write) } }
        }

        is AcCommand.SetFanSpeed -> profile.fanWriteStrings[command.speed]?.let { write ->
            val propertyKey = profile.fanPropertyKey ?: return null
            jsonBody { putJsonObject("airFlow") { put(propertyKey, write) } }
        }
    }

    private fun temperatureBody(profile: ParsedAcProfile, command: AcCommand.SetTargetTemperature): String? {
        val requestedUnit = command.unit
        val direct = profile.temperatureByUnit[requestedUnit]
        if (direct != null && command.value.inCapability(direct)) {
            return temperatureJson(command.value, requestedUnit)
        }
        // Fallback: a profile that supports exactly one other unit accepts a
        // converted value and reports it in that unit.
        if (profile.temperatureByUnit.size == 1) {
            val (onlyUnit, onlyCap) = profile.temperatureByUnit.entries.first()
            val converted = convertTemperature(command.value, requestedUnit, onlyUnit)
            if (converted.inCapability(onlyCap)) return temperatureJson(converted, onlyUnit)
        }
        return null
    }

    private fun temperatureJson(value: Double, unit: TemperatureUnit): String = jsonBody {
        putJsonObject("temperatureInUnits") {
            put("targetTemperature", value)
            put("unit", unit.symbol())
        }
    }

    private fun matches(command: AcCommand, fresh: ThinQLiveState, preferredUnit: TemperatureUnit): Boolean =
        when (command) {
            is AcCommand.SetPower -> fresh.powerOn == command.powerOn

            is AcCommand.SetTargetTemperature -> {
                val freshValue = fresh.targetTemperature ?: return false
                val wanted = convertTemperature(command.value, command.unit, preferredUnit)
                kotlin.math.abs(freshValue - wanted) <= 1e-9
            }

            is AcCommand.SetMode -> fresh.mode == command.mode
            is AcCommand.SetFanSpeed -> fresh.fanSpeed == command.speed
        }

    // ------------------------------------------------------ error mapping

    /** Projection of a classified remote error onto a secret-free command outcome. */
    private fun CommandOutcome.mapCommandError(e: ThinQClientException): CommandOutcome = when (e) {
        is ThinQClientException.MissingCredential ->
            failed(CommandFailure.TOKEN_REJECTED, "No ThinQ credential is stored.")

        is ThinQClientException.InvalidCredential ->
            failed(CommandFailure.TOKEN_REJECTED, "ThinQ credential or client identifier is invalid.")

        is ThinQClientException.Timeout ->
            failed(CommandFailure.TIMEOUT, "ThinQ request timed out.")

        is ThinQClientException.Network ->
            failed(CommandFailure.NETWORK, "ThinQ could not be reached.")

        is ThinQClientException.UnsupportedCountry ->
            failed(CommandFailure.UNKNOWN, "ThinQ account country is unsupported for routing.")

        is ThinQClientException.MalformedResponse ->
            failed(CommandFailure.UNKNOWN, "ThinQ returned a response that could not be parsed.")

        is ThinQClientException.RemoteFailure -> {
            val failure = e.error.toCommandFailure()
            failed(failure, failureLabel(failure))
        }
    }

    /** Projection of a classified remote error onto a typed verification outcome. */
    private fun ThinQClientException.mapVerificationError(): ThinQVerificationResult = when (this) {
        is ThinQClientException.Network, is ThinQClientException.Timeout -> ThinQVerificationResult.Network
        is ThinQClientException.RemoteFailure -> error.toVerificationResult()
        // A read that cannot be parsed or routed concludes nothing about the token.
        is ThinQClientException.MalformedResponse,
        is ThinQClientException.UnsupportedCountry,
        is ThinQClientException.InvalidCredential,
        is ThinQClientException.MissingCredential,
        -> ThinQVerificationResult.RemoteFailure
    }

    // ----------------------------------------------------------------- helpers

    private fun CommandOutcome.failed(failure: CommandFailure, message: String): CommandOutcome =
        copy(status = CommandStatus.FAILED, failure = failure, message = message)

    private fun CommandOutcome.unconfirmed(failure: CommandFailure, message: String): CommandOutcome =
        copy(status = CommandStatus.UNCONFIRMED, failure = failure, message = message)

    private fun failureLabel(failure: CommandFailure): String = "ThinQ " + when (failure) {
        CommandFailure.NO_CONTROL_AUTHORITY -> "denies control authority for this token."
        CommandFailure.RATE_LIMITED -> "rejected the request as rate-limited."
        CommandFailure.INVALID_REQUEST -> "rejected the request as invalid."
        CommandFailure.TOKEN_REJECTED -> "rejected the token or account."
        CommandFailure.NETWORK -> "could not be reached."
        CommandFailure.DEVICE_UNAVAILABLE -> "reports the device unavailable."
        CommandFailure.TIMEOUT -> "did not answer in time."
        CommandFailure.UNKNOWN -> "returned an unexpected error."
    }

    private fun jsonBody(build: JsonObjectBuilder.() -> Unit): String = buildJsonObject(build).toString()

    private fun requireConfiguredDevice(deviceId: String) {
        require(deviceId == configuredDeviceId) {
            "KtorThinQClient is configured for a single device; '$deviceId' is not it."
        }
        require(ThinQPayloads.isSinglePathSegment(deviceId)) {
            "ThinQ device ID must be a single URL path segment."
        }
    }

    private fun isSafeHeaderValue(value: String): Boolean =
        value.isNotBlank() && value.all { it.code in 0x20..0x7e }

    companion object {
        /** SDK `const.py` `x-service-phase` value. */
        private const val SERVICE_PHASE = "OP"
        private const val DEVICE_TYPE_AIR_CONDITIONER = "DEVICE_AIR_CONDITIONER"
        private const val REQUEST_TIMEOUT_MILLIS = 15_000L
        private const val CONNECT_TIMEOUT_MILLIS = 10_000L
        private const val SOCKET_TIMEOUT_MILLIS = 15_000L

        /**
         * The LG ThinQ Connect **public** API key shipped inside the official
         * `thinqconnect` SDK (`const.API_KEY = "v6GFvkweNo7DK7yD3ylIZ9w52aKBU0eJ7wLXkSR3"`).
         *
         * This is a fixed, published constant of the SDK — NOT a Personal Access
         * Token, NOT a service secret, and not private to this app. It is kept
         * under an explicit, documented name so the parent's secret scan can
         * review exactly this one constant and approve it.
         */
        const val THINQ_PUBLIC_SDK_API_KEY: String = "v6GFvkweNo7DK7yD3ylIZ9w52aKBU0eJ7wLXkSR3"
    }
}
