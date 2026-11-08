package cloud.airchive.shared

import kotlin.test.Test
import kotlin.test.assertTrue

class RequiredGateFailureProbeTest {
    @Test
    fun temporaryFailureMakesTheRequiredCheckFail() {
        assertTrue(false, "Temporary required-gate failure probe")
    }
}
