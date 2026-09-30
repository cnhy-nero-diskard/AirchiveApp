package cloud.airchive.shared.data.telemetry

import kotlin.time.Instant
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * **Whitelisted** typed view over one Firestore document's data map.
 *
 * This is where the read-only boundary is enforced: [TelemetryDoc] only exposes
 * accessors for the specific normalized fields the app may keep, so any call
 * site that touches a document goes through these typed readers. Unknown keys,
 * `raw` payloads, and whole unknown documents are never read here and therefore
 * never leak into the app's models.
 *
 * Firestore timestamps arrive here already normalized (task 2.3 adapter): either
 * an ISO-8601 string (parsed with `kotlinx.datetime.Instant.parse`) or plain
 * epoch milliseconds as a number / numeric string.
 */
internal class TelemetryDoc(private val data: Map<String, Any?>) {

    /** A non-blank string (numbers are stringified). Absent/blank values are `null`. */
    fun string(key: String): String? = when (val value = data[key]) {
        is String -> value.takeIf { it.isNotBlank() }
        is Number -> value.toString()
        else -> null
    }

    fun double(key: String): Double? = when (val value = data[key]) {
        is Number -> value.toDouble()
        is String -> value.toDoubleOrNull()
        else -> null
    }

    fun int(key: String): Int? = when (val value = data[key]) {
        is Number -> value.toInt()
        is String -> value.toIntOrNull()
        else -> null
    }

    /** `true`/`false` only; absent or non-boolean values are `null` (never assumed). */
    fun boolOrNull(key: String): Boolean? = when (val value = data[key]) {
        is Boolean -> value
        is String -> value.toBooleanStrictOrNull()
        else -> null
    }

    /** Nested document accessor, or `null` when the value is not a map. */
    fun doc(key: String): TelemetryDoc? {
        val value = data[key] ?: return null
        val map = value as? Map<*, *> ?: return null
        return TelemetryDoc(map.entries.associate { (k, v) -> k.toString() to v })
    }

    /** A list whose entries are preserved as strings (non-strings filtered out). */
    fun stringList(key: String): List<String> = when (val value = data[key]) {
        is List<*> -> value.mapNotNull { entry ->
            when (entry) {
                is String -> entry
                is Number -> entry.toString()
                else -> null
            }
        }
        else -> emptyList()
    }

    /**
     * A timestamp as epoch milliseconds. Accepts an epoch-millis number,
     * an epoch-millis numeric string, or an ISO-8601/offset string (e.g.
     * `"2026-01-15T00:05:00+08:00"`). Returns `null` when absent or unparsable.
     */
    fun timestampMillis(key: String): Long? = when (val value = data[key]) {
        is Number -> value.toLong()
        is String -> value.toLongOrNull() ?: parseIsoInstant(value)
        else -> null
    }

    companion object {
        private fun parseIsoInstant(text: String): Long? =
            runCatching { Instant.parse(text).toEpochMilliseconds() }.getOrNull()
    }
}

/**
 * Decodes a fixture/JSON document into the plain `Map<String, Any?>` shape the
 * parsers read, so the same whitelisted parse path serves both Firestore data
 * maps and JSON fixture strings. `raw`/`state` payloads decode as opaque maps
 * but are never read by the parsers.
 */
object TelemetryJson {

    private val decoder = Json { ignoreUnknownKeys = true }

    fun parse(text: String): Map<String, Any?> {
        val element = decoder.parseToJsonElement(text)
        return when (val plain = toPlain(element)) {
            is Map<*, *> -> plain.entries.associate { (k, v) -> k.toString() to v }
            else -> emptyMap()
        }
    }

    private fun toPlain(element: JsonElement): Any? = when (element) {
        is JsonNull -> null
        is JsonPrimitive -> when {
            element.isString -> element.content
            else -> element.content.toLongOrNull()
                ?: element.content.toDoubleOrNull()
                ?: element.content.toBooleanStrictOrNull()
                ?: element.content
        }
        is JsonObject -> element.mapValues { (_, value) -> toPlain(value) }
        is JsonArray -> element.map { toPlain(it) }
    }
}
