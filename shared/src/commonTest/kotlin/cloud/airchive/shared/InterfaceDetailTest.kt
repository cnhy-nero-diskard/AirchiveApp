package cloud.airchive.shared

import kotlin.test.Test
import kotlin.test.assertEquals

class InterfaceDetailTest {
    @Test fun absentOrUnrecognizedStoredChoiceUsesSimple() {
        assertEquals(InterfaceDetail.SIMPLE, InterfaceDetail.fromStoredValue(null))
        assertEquals(InterfaceDetail.SIMPLE, InterfaceDetail.fromStoredValue("future-format"))
        InterfaceDetail.entries.forEach { assertEquals(it, InterfaceDetail.fromStoredValue(it.storedValue)) }
    }

    @Test fun inMemoryFallbackDoesNotClaimCrossInstancePersistence() {
        val firstInstance = InMemoryInterfaceDetailPreferences()
        firstInstance.select(InterfaceDetail.DETAILED)
        assertEquals(InterfaceDetail.DETAILED, firstInstance.detail.value)

        val freshInstance = InMemoryInterfaceDetailPreferences()
        assertEquals(InterfaceDetail.SIMPLE, freshInstance.detail.value)
    }
}
