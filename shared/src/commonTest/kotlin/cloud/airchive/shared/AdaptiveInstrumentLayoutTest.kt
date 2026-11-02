package cloud.airchive.shared

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AdaptiveInstrumentLayoutTest {
    @Test fun railRequiresWideLandscapeWindow() {
        assertFalse(useInstrumentRail(599f, 320f))
        assertTrue(useInstrumentRail(600f, 320f))
        assertFalse(useInstrumentRail(700f, 900f))
        assertFalse(useInstrumentRail(600f, 600f))
    }

    @Test fun columnsRequireSpaceAndNormalTextScale() {
        assertFalse(useInstrumentColumns(639f, 1f))
        assertTrue(useInstrumentColumns(640f, 1.2f))
        assertFalse(useInstrumentColumns(1000f, 2f))
    }
}
