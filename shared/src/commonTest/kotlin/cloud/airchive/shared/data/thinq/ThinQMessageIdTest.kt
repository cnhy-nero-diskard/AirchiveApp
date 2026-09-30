package cloud.airchive.shared.data.thinq

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The per-request `x-message-id` encoder: base64url (RFC 4648 §5) of 16 random
 * v4-UUID bytes with **no** trailing padding, matching the SDK's
 * `_generate_message_id()` (`base64.urlsafe_b64encode(uuid4().bytes)[:-2]`).
 */
class ThinQMessageIdTest {

    @Test
    fun encoderMatchesBase64UrlWithoutPadding() {
        assertEquals("", ThinQMessageId.base64UrlNoPadding(ByteArray(0)))
        assertEquals("AA", ThinQMessageId.base64UrlNoPadding(byteArrayOf(0x00)))
        // 0xFB 0xFF → groups 111110/111111/111100 → indices 62/63/60 → "-_8".
        assertEquals("-_8", ThinQMessageId.base64UrlNoPadding(byteArrayOf(0xfb.toByte(), 0xff.toByte())))
    }

    @Test
    fun generatedMessageIdsAreUrlSafeAndPaddingFree() {
        val seen = mutableSetOf<String>()
        repeat(50) {
            val id = ThinQMessageId.next()
            assertTrue(id.matches(Regex("^[A-Za-z0-9_-]{22}$")), "unexpected message id format: $id")
            assertFalse("=" in id, "no trailing base64 padding may be present")
            assertTrue(seen.add(id), "message ids must be unique per request")
        }
        assertFalse(seen.isEmpty())
    }
}