package cloud.airchive.shared

import kotlin.test.Test
import kotlin.test.assertTrue

class MainGateFailingHostTest {
    @Test
    fun requiredCheckMustRejectAFailingHostTest() {
        assertTrue("Intentional failure for the main gate verification", false)
    }
}
