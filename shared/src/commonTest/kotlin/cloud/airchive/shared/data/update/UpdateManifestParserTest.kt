package cloud.airchive.shared.data.update

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class UpdateManifestParserTest {

    @Test
    fun parsesVersionOneManifestAndIgnoresUnknownFields() {
        val manifest = parseUpdateManifest(validJson().replace("\"schemaVersion\": 1", "\"schemaVersion\": 1, \"futureField\": true"))

        assertEquals(1, manifest.schemaVersion)
        assertEquals("stable", manifest.channel)
        assertEquals("cloud.airchive.personal", manifest.packageName)
        assertEquals(9L, manifest.versionCode)
        assertEquals("0.3.0", manifest.versionName)
        assertEquals("a".repeat(40), manifest.commit)
        assertEquals("2026-11-07T00:00:00Z", manifest.publishedAt)
        assertEquals("<b>Fixes</b>\nSafer updates", manifest.releaseNotes)
        assertEquals("https://github.com/example/app/releases/download/v0.3.0/app.apk", manifest.apk.url)
        assertEquals("b".repeat(64), manifest.apk.sha256)
        assertEquals(1_234L, manifest.apk.size)
        assertEquals("c".repeat(64), manifest.apk.signingCertSha256)
    }

    @Test
    fun rejectsUnknownSchemaVersion() {
        assertFailsWith<UpdateManifestParseException.UnsupportedSchemaVersion> {
            parseUpdateManifest(validJson().replace("\"schemaVersion\": 1", "\"schemaVersion\": 2"))
        }
    }

    @Test
    fun rejectsMissingRequiredField() {
        assertFailsWith<UpdateManifestParseException.InvalidField> {
            parseUpdateManifest(validJson().replace("\"channel\": \"stable\",", ""))
        }
    }

    @Test
    fun rejectsMistypedRequiredField() {
        assertFailsWith<UpdateManifestParseException.InvalidField> {
            parseUpdateManifest(validJson().replace("\"versionCode\": 9", "\"versionCode\": \"9\""))
        }
    }

    @Test
    fun rejectsNonHttpsApkUrl() {
        assertFailsWith<UpdateManifestParseException.InvalidField> {
            parseUpdateManifest(validJson().replace("https://github.com", "http://github.com"))
        }
    }

    @Test
    fun rejectsMalformedHashes() {
        assertFailsWith<UpdateManifestParseException.InvalidField> {
            parseUpdateManifest(validJson().replace("b".repeat(64), "not-a-hash"))
        }
    }

    @Test
    fun rejectsNotesLongerThanFourThousandCharacters() {
        val tooLong = "x".repeat(MAX_RELEASE_NOTES_LENGTH + 1)
        assertFailsWith<UpdateManifestParseException.InvalidField> {
            parseUpdateManifest(validJson().replace("<b>Fixes</b>\\nSafer updates", tooLong))
        }
    }

    private fun validJson(): String = """
        {
          "schemaVersion": 1,
          "channel": "stable",
          "packageName": "cloud.airchive.personal",
          "versionCode": 9,
          "versionName": "0.3.0",
          "commit": "${"a".repeat(40)}",
          "publishedAt": "2026-11-07T00:00:00Z",
          "releaseNotes": "<b>Fixes</b>\nSafer updates",
          "apk": {
            "url": "https://github.com/example/app/releases/download/v0.3.0/app.apk",
            "sha256": "${"b".repeat(64)}",
            "size": 1234,
            "signingCertSha256": "${"c".repeat(64)}"
          }
        }
    """.trimIndent()
}
