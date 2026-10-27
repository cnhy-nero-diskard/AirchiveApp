package cloud.airchive.shared

import kotlin.test.Test
import kotlin.test.assertEquals

class InterfaceDetailTest {
    @Test fun absentOrUnrecognizedStoredChoiceUsesSimple() {
        assertEquals(InterfaceDetail.SIMPLE, InterfaceDetail.fromStoredValue(null))
        assertEquals(InterfaceDetail.SIMPLE, InterfaceDetail.fromStoredValue("future-format"))
        InterfaceDetail.entries.forEach { assertEquals(it, InterfaceDetail.fromStoredValue(it.storedValue)) }
    }
}
