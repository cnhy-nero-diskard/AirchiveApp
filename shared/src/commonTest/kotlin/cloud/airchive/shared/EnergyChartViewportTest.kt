package cloud.airchive.shared

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class EnergyChartViewportTest {
    @Test fun pinchPreservesAnchorAndPanMovesInBucketCoordinates() {
        val full = EnergyViewport(0f, 288f)
        val zoomed = full.transform(288, .25f, 4f, 0f)
        assertEquals(72f, zoomed.count)
        assertEquals(full.start + .25f * full.count, zoomed.start + .25f * zoomed.count)
        assertEquals(zoomed.start + 36f, zoomed.transform(288, 0f, 1f, -.5f).start)
    }

    @Test fun viewportClampsAtBothEdgesAndLimitsMagnification() {
        for (total in listOf(48, 96, 288)) {
            assertEquals(EnergyViewport(0f, 5f), EnergyViewport(-2f, 5f).constrained(total))
            assertEquals(EnergyViewport(total - 5f, 5f), EnergyViewport(total - 3f, 5f).constrained(total))
            assertEquals(5f, EnergyViewport(0f, total.toFloat()).transform(total, .5f, 1000f, 0f).count)
            assertEquals(EnergyViewport(0f, total.toFloat()), EnergyViewport(10f, 5f).transform(total, .5f, .001f, 0f))
        }
    }

    @Test fun autoFocusIncludesFourNeighborsAndClampsAtDayEdges() {
        for (total in listOf(48, 96, 288)) {
            val full = EnergyViewport(0f, total.toFloat())
            assertEquals(EnergyViewport(16f, 9f), full.autoFocus(total, 20, null))
            assertEquals(EnergyViewport(0f, 9f), full.autoFocus(total, 0, null))
            assertEquals(EnergyViewport(total - 9f, 9f), full.autoFocus(total, total - 1, null))
        }
    }

    @Test fun tappingSelectedPointTogglesFullRangeAndAnotherPointRefocuses() {
        for (total in listOf(48, 96, 288)) {
            val full = EnergyViewport(0f, total.toFloat())
            val focused = full.autoFocus(total, 20, null)
            assertEquals(full, focused.autoFocus(total, 20, 20))
            assertEquals(focused, full.autoFocus(total, 20, 20))
            assertEquals(EnergyViewport(17f, 9f), focused.autoFocus(total, 21, 20))
        }
    }

    @Test fun timeTicksBecomeMoreFrequentAsZoomIncreases() {
        assertEquals(72, energyTimeTickStride(288f, 4))
        assertEquals(18, energyTimeTickStride(72f, 4))
        assertEquals(2, energyTimeTickStride(5f, 4))
        assertEquals(1, energyTimeTickStride(5f, 8))
    }

    @Test fun whTicksCoverTheMaximumWithoutRoundingSmallReadingsToZero() {
        for (maximum in listOf(.01, .7, 1.0, 11.0, 180.0, 9900.0)) {
            val step = energyAxisStep(maximum)
            assertTrue(step > 0)
            assertTrue(step * 4 >= maximum)
            assertTrue(step * 4 <= maximum * 2.5)
        }
    }
}
