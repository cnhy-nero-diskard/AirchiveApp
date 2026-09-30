package cloud.airchive.shared.data.thinq

import cloud.airchive.shared.contract.thinq.AcControls
import cloud.airchive.shared.contract.thinq.AcOperatingMode
import cloud.airchive.shared.contract.thinq.FanSpeed
import cloud.airchive.shared.contract.thinq.TemperatureCapability
import cloud.airchive.shared.contract.thinq.TemperatureUnit
import cloud.airchive.shared.contract.thinq.ThinQDevice
import cloud.airchive.shared.contract.thinq.ThinQDeviceProfile
import cloud.airchive.shared.contract.thinq.ThinQLiveState
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.contentOrNull

/**
 * Defensive parsers for the unwrapped `payload.response` bodies of the four
 * ThinQ Connect endpoints, shaped after the official SDK's profile/status
 * handling in `thinqconnect`.
 *
 * Every parser is conservative: unexpected shapes throw
 * [ThinQClientException.MalformedResponse] rather than inventing defaults, and
 * unknown enum/string values are dropped (never guessed). Range capabilities
 * additionally require finite min/max/step with min <= max and step > 0, and
 * preserve the profile's `write_values.except` exclusions. This layer is built
 * for extension — task 4.2 deepens the capability/state model, so the parsers
 * stay isolated here and take their input as raw [JsonElement] nodes.
 *
 * The wired payloads:
 * - `devices`                      → `response: [ {deviceId, deviceInfo:{alias,modelName,online}} ]`
 * - `devices/{id}/profile`         → `response.property.*`
 * - `devices/{id}/state`           → `response.{operation, airConJobMode, temperatureInUnits, airFlow}`
 * - `devices/{id}/control`         → POST body built by [KtorThinQClient]
 *
 * @see [KtorThinQClient]
 */
internal object ThinQPayloads {

    // ------------------------------------------------------------------ lists

    /**
     * Parses the device list response. Entries without a `deviceId`, entries
     * whose ID is not a single URL path segment (defensive: the ID becomes part
     * of the request path), and non-object entries are dropped; a response that
     * is not a JSON array is malformed, never an empty list.
     */
    fun deviceList(response: JsonElement?): List<ThinQDevice> {
        val array = response as? JsonArray ?: throw ThinQClientException.MalformedResponse
        return array.mapNotNull { entry ->
            val obj = entry as? JsonObject ?: return@mapNotNull null
            val deviceId = obj.string("deviceId") ?: return@mapNotNull null
            if (!isSinglePathSegment(deviceId)) return@mapNotNull null
            val info = obj["deviceInfo"] as? JsonObject
            ThinQDevice(
                deviceId = deviceId,
                alias = info?.string("alias") ?: "",
                modelName = info?.string("modelName"),
                online = info?.boolean("online") ?: false,
                deviceType = info?.string("deviceType") ?: obj.string("deviceType"),
            )
        }
    }

    /** Only IDs that are a safe, exact single path segment may be used in URLs. */
    fun isSinglePathSegment(id: String): Boolean =
        id.isNotBlank() &&
            id.all { char ->
                char in 'A'..'Z' || char in 'a'..'z' || char in '0'..'9' || char == '-' || char == '_'
            }

    // ---------------------------------------------------------------- profile

    /**
     * Parses one AC profile into a writable-capability snapshot. Only entries
     * the SDK marks writable are offered, and only the four everyday controls
     * are read out; everything else is ignored. Power is writable only when the
     * enum explicitly lists both POWER_ON and POWER_OFF; modes/fans keep only
     * contract-known values mapped to their exact LG write strings; temperature
     * requires a finite, ordered range with a positive step and a known unit.
     */
    fun acProfile(deviceId: String, response: JsonElement?): ParsedAcProfile {
        val root = response as? JsonObject ?: throw ThinQClientException.MalformedResponse
        val property = root["property"] as? JsonObject ?: throw ThinQClientException.MalformedResponse

        val operation = property["operation"] as? JsonObject
        val powerCap = enumCapability(operation?.get("airConOperationMode"))
        val powerWritable = powerCap != null &&
            powerCap.writable &&
            "POWER_ON" in powerCap.writeValues &&
            "POWER_OFF" in powerCap.writeValues

        val modeWriteStrings = linkedMapOf<AcOperatingMode, String>()
        val jobMode = property["airConJobMode"] as? JsonObject
        val modeCap = enumCapability(jobMode?.get("currentJobMode"))
        if (modeCap != null && modeCap.writable) {
            for (raw in modeCap.writeValues) {
                val mode = modeFromWire(raw) ?: continue
                if (mode !in modeWriteStrings) modeWriteStrings[mode] = raw
            }
        }

        val fanWriteStrings = linkedMapOf<FanSpeed, String>()
        val airFlow = property["airFlow"] as? JsonObject
        // The official SDK selects the preferred key by profile presence, in
        // this order. Keep that key with the capability so control writes the
        // exact property it parsed rather than always using `windStrength`.
        val windKey = when {
            airFlow?.containsKey("windStrengthDetail") == true -> "windStrengthDetail"
            airFlow?.containsKey("windStrength") == true -> "windStrength"
            else -> null
        }
        val wind = windKey?.let { airFlow?.get(it) }
        val fanCap = enumCapability(wind)
        if (fanCap != null && fanCap.writable) {
            for (raw in fanCap.writeValues) {
                val speed = fanFromWire(raw) ?: continue
                if (speed !in fanWriteStrings) fanWriteStrings[speed] = raw
            }
        }

        return ParsedAcProfile(
            deviceId = deviceId,
            modelName = root.string("modelName"),
            powerWritable = powerWritable,
            temperatureByUnit = parseTemperatures(property["temperatureInUnits"]),
            modeWriteStrings = modeWriteStrings,
            fanPropertyKey = windKey.takeIf { fanWriteStrings.isNotEmpty() },
            fanWriteStrings = fanWriteStrings,
        )
    }

    // ------------------------------------------------------------------ state

    /**
     * Parses one live-state response. [preferredUnit] is the profile's
     * temperature unit; any reported temperature from a different unit is
     * converted into it so [ThinQLiveState] and the profile always agree.
     * Unknown mode/fan strings and missing temperatures stay `null`.
     */
    fun liveState(
        deviceId: String,
        response: JsonElement?,
        preferredUnit: TemperatureUnit,
        observedAtEpochMillis: Long,
    ): ThinQLiveState {
        val root = response as? JsonObject ?: throw ThinQClientException.MalformedResponse
        val operation = root["operation"] as? JsonObject
        val jobMode = root["airConJobMode"] as? JsonObject
        val airFlow = root["airFlow"] as? JsonObject
        val wind = airFlow?.string("windStrengthDetail") ?: airFlow?.string("windStrength")
        val powerOn = when (operation?.string("airConOperationMode")) {
            "POWER_ON" -> true
            "POWER_OFF" -> false
            // LG also reports `AI`/future enum values. Mapping an unknown value
            // to `false` would falsely confirm a requested power-off command.
            else -> throw ThinQClientException.MalformedResponse
        }
        return ThinQLiveState(
            deviceId = deviceId,
            observedAtEpochMillis = observedAtEpochMillis,
            powerOn = powerOn,
            targetTemperature = targetTemperature(root["temperatureInUnits"], preferredUnit),
            mode = jobMode?.string("currentJobMode")?.let { modeFromWire(it) },
            fanSpeed = wind?.let { fanFromWire(it) },
        )
    }

    // ------------------------------------------------------------ temperature

    private fun parseTemperatures(node: JsonElement?): Map<TemperatureUnit, TemperatureCapability> {
        val result = linkedMapOf<TemperatureUnit, TemperatureCapability>()
        val blocks = when (node) {
            is JsonArray -> node.mapNotNull { it as? JsonObject }
            is JsonObject -> listOf(node)
            else -> return result
        }
        for (block in blocks) {
            val unit = unitFromSymbol(block.string("unit")) ?: continue
            val cap = rangeCapability(block["targetTemperature"]) ?: continue
            if (cap.writable) {
                result[unit] = TemperatureCapability(
                    min = cap.min,
                    max = cap.max,
                    step = cap.step,
                    unit = unit,
                    excludedValues = cap.excluded,
                )
            }
        }
        return result
    }

    /** Numeric target temperature (in its block's unit) converted to [preferredUnit]. */
    private fun targetTemperature(node: JsonElement?, preferredUnit: TemperatureUnit): Double? {
        val blocks = when (node) {
            is JsonArray -> node.mapNotNull { it as? JsonObject }
            is JsonObject -> listOf(node)
            else -> return null
        }
        val symbol = preferredUnit.symbol()
        val preferred = blocks.firstOrNull {
            it.string("unit")?.trim()?.uppercase() == symbol && number(it, "targetTemperature") != null
        }
        val chosen = preferred ?: blocks.firstOrNull { number(it, "targetTemperature") != null } ?: return null
        val raw = number(chosen, "targetTemperature") ?: return null
        val blockUnit = unitFromSymbol(chosen.string("unit"))
        if (blockUnit == null || blockUnit == preferredUnit) return raw
        return convertTemperature(raw, blockUnit, preferredUnit)
    }

    // ------------------------------------------------------------- capability

    private data class EnumCapability(val writable: Boolean, val writeValues: List<String>)

    private data class RangeCapability(
        val min: Double,
        val max: Double,
        val step: Double,
        val excluded: Set<Double> = emptySet(),
    ) {
        val writable: Boolean get() = true
    }

    /** A writable `enum` property, or `null` when absent/not an enum. */
    private fun enumCapability(prop: JsonElement?): EnumCapability? {
        val obj = prop as? JsonObject ?: return null
        if (obj.string("type") != "enum") return null
        val write = obj["write_values"] as? JsonArray
        val values = write.orEmpty().mapNotNull { entry ->
            (entry as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull
        }
        return EnumCapability(writable = obj.boolean("writable"), writeValues = values)
    }

    /**
     * A readable/writable `range` property with a finite, ordered min/max and a
     * finite positive step, or `null` when absent/malformed. Non-finite bounds
     * or steps, reversed ranges, and zero/negative steps are all omitted: a
     * capability the parser cannot trust never produces a control. The
     * profile's `write_values.except` exclusions are preserved (finite entries
     * only).
     */
    private fun rangeCapability(prop: JsonElement?): RangeCapability? {
        val obj = prop as? JsonObject ?: return null
        if (obj.string("type") != "range") return null
        if (!obj.boolean("writable")) return null
        val values = obj["write_values"] as? JsonObject ?: return null
        val min = number(values, "min") ?: return null
        val max = number(values, "max") ?: return null
        if (!min.isFinite() || !max.isFinite() || min > max) return null
        val step = number(values, "step") ?: 1.0
        if (!step.isFinite() || step <= 0.0) return null
        val excluded = parseExclusions(values["except"]) ?: return null
        return RangeCapability(min = min, max = max, step = step, excluded = excluded)
    }

    /**
     * Parses `write_values.except` into normalized exclusions. Only finite
     * numbers are kept; if the exclusion field is present but malformed, return
     * `null` so the caller disables this temperature capability rather than
     * silently allowing a value the profile may have intended to exclude.
     */
    private fun parseExclusions(node: JsonElement?): Set<Double>? {
        if (node == null) return emptySet()
        if (node == JsonNull) return null
        val array = node as? JsonArray ?: return null
        val values = mutableSetOf<Double>()
        for (entry in array) {
            val primitive = entry as? JsonPrimitive ?: return null
            if (primitive.isString) return null
            val value = number(primitive) ?: return null
            if (!value.isFinite()) return null
            values += value
        }
        return values
    }

    // ------------------------------------------------------------ value maps

    // Pure wire <-> contract conversions live at top level (see below) so both
    // this object and KtorThinQClient can resolve them directly.

    // ---------------------------------------------------------------- helpers

    private fun JsonObject.string(key: String): String? =
        (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull

    private fun JsonObject.boolean(key: String): Boolean =
        (this[key] as? JsonPrimitive)?.boolean ?: false

    private fun number(obj: JsonObject, key: String): Double? {
        val value = obj[key] as? JsonPrimitive ?: return null
        return number(value)
    }

    private fun number(value: JsonPrimitive): Double? {
        if (value.isString) return value.contentOrNull?.toDoubleOrNull()
        // "true"/"false" parse to null; numbers parse fine.
        return value.content.toDoubleOrNull()
    }
}

// ------------------------------------------------------------ value maps

/** Wire → contract operating-mode conversion for values the app understands. */
internal fun modeFromWire(raw: String): AcOperatingMode? = when (raw) {
    "COOL" -> AcOperatingMode.COOL
    "HEAT" -> AcOperatingMode.HEAT
    "DRY" -> AcOperatingMode.DRY
    "FAN", "FAN_ONLY" -> AcOperatingMode.FAN
    "AUTO" -> AcOperatingMode.AUTO
    "ENERGY_SAVING", "POWER_SAVE" -> AcOperatingMode.POWER_SAVE
    else -> null
}

/** Wire → contract fan-speed conversion for values the app understands. */
internal fun fanFromWire(raw: String): FanSpeed? = when (raw) {
    "AUTO" -> FanSpeed.AUTO
    "LOW" -> FanSpeed.LOW
    "MID", "NORMAL", "MEDIUM" -> FanSpeed.MEDIUM
    "HIGH" -> FanSpeed.HIGH
    "POWER" -> FanSpeed.TURBO
    else -> null
}

internal fun TemperatureUnit.symbol(): String = if (this == TemperatureUnit.CELSIUS) "C" else "F"

internal fun unitFromSymbol(symbol: String?): TemperatureUnit? = when (symbol?.trim()?.uppercase()) {
    "C" -> TemperatureUnit.CELSIUS
    "F" -> TemperatureUnit.FAHRENHEIT
    else -> null
}

internal fun convertTemperature(value: Double, from: TemperatureUnit, to: TemperatureUnit): Double =
    when {
        from == to -> value
        to == TemperatureUnit.CELSIUS -> (value - 32.0) * 5.0 / 9.0
        else -> value * 9.0 / 5.0 + 32.0
    }

/**
 * Parsed AC profile snapshot used by the client to validate commands locally and
 * build exact wire bodies. It keeps the profile's own LG write strings so a
 * round-trip never invents a value the device did not list.
 *
 * [toContractProfile] surfaces exactly one preferred temperature unit and its
 * capability, so `controls.temperatureUnit` always equals the surfaced
 * `controls.targetTemperature.unit`. Additional units stay on [temperatureByUnit]
 * only so [KtorThinQClient] can convert a requested command into a unit the
 * returned profile actually provides — never the reverse.
 */
internal data class ParsedAcProfile(
    val deviceId: String,
    val modelName: String?,
    val powerWritable: Boolean,
    /** Writable temperature capability per unit the device offers; values are in that unit. */
    val temperatureByUnit: Map<TemperatureUnit, TemperatureCapability>,
    /** LG write string for each operating mode the profile permits (round-trip). */
    val modeWriteStrings: Map<AcOperatingMode, String>,
    /** Exact writable `airFlow` property selected by the official SDK profile map. */
    val fanPropertyKey: String?,
    /** LG write string for each fan speed the profile permits (round-trip). */
    val fanWriteStrings: Map<FanSpeed, String>,
) {
    /** The app presents Celsius when the device offers it, otherwise any unit it does. */
    val preferredTemperatureUnit: TemperatureUnit =
        temperatureByUnit.keys.firstOrNull { it == TemperatureUnit.CELSIUS }
            ?: temperatureByUnit.keys.firstOrNull()
            ?: TemperatureUnit.CELSIUS

    fun toContractProfile(): ThinQDeviceProfile = ThinQDeviceProfile(
        deviceId = deviceId,
        modelName = modelName,
        controls = AcControls(
            powerWritable = powerWritable,
            targetTemperature = temperatureByUnit[preferredTemperatureUnit],
            allowedModes = modeWriteStrings.keys.toList(),
            allowedFanSpeeds = fanWriteStrings.keys.toList(),
            temperatureUnit = preferredTemperatureUnit,
        ),
    )
}
