package cloud.airchive.shared.data.thinq

import kotlin.random.Random

/**
 * Per-request `x-message-id`, matching the SDK's `_generate_message_id()`:
 * a random UUIDv4 whose 16 raw bytes are base64url-encoded with no trailing
 * padding (the SDK strips the `==` suffix).
 *
 * `kotlin.uuid`/`kotlin.io.encoding` are still marked experimental in the
 * project's Kotlin stdlib, so the tiny encoder is hand-rolled here; it is
 * deterministic and unit-tested in `ThinQMessageIdTest`.
 */
internal object ThinQMessageId {
    private val random = Random.Default

    /** A fresh base64url message ID, 22 chars, no padding, unique per request. */
    fun next(): String {
        val bytes = ByteArray(16)
        random.nextBytes(bytes)
        // RFC 4122 v4 layout so LG always sees a well-formed UUID when it parses.
        bytes[6] = ((bytes[6].toInt() and 0x0f) or 0x40).toByte()
        bytes[8] = ((bytes[8].toInt() and 0x3f) or 0x80).toByte()
        return base64UrlNoPadding(bytes)
    }

    /** base64url (RFC 4648 §5) with no trailing `=` padding. */
    internal fun base64UrlNoPadding(bytes: ByteArray): String {
        if (bytes.isEmpty()) return ""
        val alphabet = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-_"
        val out = StringBuilder(((bytes.size + 2) / 3) * 4)
        var i = 0
        while (i < bytes.size) {
            val b0 = bytes[i].toInt() and 0xff
            val hasB1 = i + 1 < bytes.size
            val hasB2 = i + 2 < bytes.size
            val b1 = if (hasB1) bytes[i + 1].toInt() and 0xff else 0
            val b2 = if (hasB2) bytes[i + 2].toInt() and 0xff else 0
            out.append(alphabet[b0 ushr 2])
            out.append(alphabet[((b0 shl 4) or (b1 ushr 4)) and 0x3f])
            if (hasB1) out.append(alphabet[((b1 shl 2) or (b2 ushr 6)) and 0x3f])
            if (hasB2) out.append(alphabet[b2 and 0x3f])
            i += 3
        }
        return out.toString()
    }
}