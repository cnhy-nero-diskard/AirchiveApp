package cloud.airchive.shared.data.update

import io.ktor.http.Url
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.longOrNull

/** Version-1 update feed understood by the installed application. */
data class UpdateManifest(
    val schemaVersion: Int,
    val channel: String,
    val packageName: String,
    val versionCode: Long,
    val versionName: String,
    val commit: String,
    val publishedAt: String,
    /** Kept as plain text. UI code must render this with Text, never HTML. */
    val releaseNotes: String,
    val apk: UpdateApk,
)

data class UpdateApk(
    val url: String,
    val sha256: String,
    val size: Long,
    val signingCertSha256: String,
)

sealed class UpdateManifestParseException(message: String) : IllegalArgumentException(message) {
    class InvalidJson : UpdateManifestParseException("The update manifest is not valid JSON.")
    class InvalidField(field: String) : UpdateManifestParseException("The update manifest field '$field' is missing or invalid.")
    class UnsupportedSchemaVersion(version: Long) : UpdateManifestParseException("Unsupported update manifest schema version: $version.")
}

/** Parses the release-pipeline v1 format, rejecting malformed required fields and ignoring extras. */
fun parseUpdateManifest(jsonText: String): UpdateManifest {
    val root = try {
        Json.parseToJsonElement(jsonText) as? JsonObject ?: throw UpdateManifestParseException.InvalidJson()
    } catch (error: UpdateManifestParseException) {
        throw error
    } catch (_: SerializationException) {
        throw UpdateManifestParseException.InvalidJson()
    } catch (_: IllegalArgumentException) {
        throw UpdateManifestParseException.InvalidJson()
    }

    val schemaVersion = root.requiredLong("schemaVersion")
    if (schemaVersion != SUPPORTED_SCHEMA_VERSION.toLong()) {
        throw UpdateManifestParseException.UnsupportedSchemaVersion(schemaVersion)
    }

    val apk = root["apk"] as? JsonObject ?: throw UpdateManifestParseException.InvalidField("apk")
    val notes = root.requiredString("releaseNotes")
    if (notes.length > MAX_RELEASE_NOTES_LENGTH) {
        throw UpdateManifestParseException.InvalidField("releaseNotes")
    }

    return UpdateManifest(
        schemaVersion = schemaVersion.toInt(),
        channel = root.requiredString("channel"),
        packageName = root.requiredString("packageName"),
        versionCode = root.requiredPositiveLong("versionCode"),
        versionName = root.requiredNonBlankString("versionName"),
        commit = root.requiredString("commit"),
        publishedAt = root.requiredString("publishedAt"),
        releaseNotes = notes,
        apk = UpdateApk(
            url = apk.requiredHttpsUrl("url"),
            sha256 = apk.requiredSha256("sha256"),
            size = apk.requiredPositiveLong("size"),
            signingCertSha256 = apk.requiredSha256("signingCertSha256"),
        ),
    )
}

private const val SUPPORTED_SCHEMA_VERSION = 1
const val MAX_RELEASE_NOTES_LENGTH = 4_000

private fun JsonObject.requiredString(name: String): String =
    (this[name] as? JsonPrimitive)
        ?.takeIf { it.isString }
        ?.content
        ?: throw UpdateManifestParseException.InvalidField(name)

private fun JsonObject.requiredNonBlankString(name: String): String =
    requiredString(name).takeIf(String::isNotBlank)
        ?: throw UpdateManifestParseException.InvalidField(name)

private fun JsonObject.requiredLong(name: String): Long {
    val primitive = this[name] as? JsonPrimitive ?: throw UpdateManifestParseException.InvalidField(name)
    if (primitive.isString) throw UpdateManifestParseException.InvalidField(name)
    return primitive.longOrNull ?: throw UpdateManifestParseException.InvalidField(name)
}

private fun JsonObject.requiredPositiveLong(name: String): Long =
    requiredLong(name).takeIf { it > 0L } ?: throw UpdateManifestParseException.InvalidField(name)

private fun JsonObject.requiredHttpsUrl(name: String): String {
    val value = requiredString(name)
    val parsed = try {
        Url(value)
    } catch (_: IllegalArgumentException) {
        throw UpdateManifestParseException.InvalidField(name)
    }
    if (value != value.trim() || parsed.protocol.name != "https" || parsed.host.isBlank()) {
        throw UpdateManifestParseException.InvalidField(name)
    }
    return value
}

private fun JsonObject.requiredSha256(name: String): String {
    val value = requiredString(name)
    if (!SHA256_PATTERN.matches(value)) throw UpdateManifestParseException.InvalidField(name)
    return value.lowercase()
}

private val SHA256_PATTERN = Regex("[0-9a-fA-F]{64}")
