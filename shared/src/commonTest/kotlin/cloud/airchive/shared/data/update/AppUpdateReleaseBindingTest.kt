package cloud.airchive.shared.data.update

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class AppUpdateReleaseBindingTest {
    @Test
    fun debugBuildDoesNotConstructUpdateBindingOrClient() {
        var constructed = 0

        val bindings = releaseOnlyAppUpdateBinding(isReleaseBuild = false) {
            constructed += 1
            "update binding"
        }

        assertNull(bindings)
        assertEquals(0, constructed)
    }
}
