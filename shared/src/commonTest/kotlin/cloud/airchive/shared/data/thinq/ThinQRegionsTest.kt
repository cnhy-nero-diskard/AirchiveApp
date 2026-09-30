package cloud.airchive.shared.data.thinq

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * Pure routing-table checks: the SDK's `country.py` mapping and the exact
 * `https://api-<region>.lgthinq.com` base URLs the client must hit.
 */
class ThinQRegionsTest {

    @Test
    fun phRoutesToKicExactlyAsTheSdkDoes() {
        assertEquals(ThinQRegion.KIC, regionForCountry("PH"))
        assertEquals(ThinQRegion.KIC, regionForCountry("ph"))
        assertEquals("https://api-kic.lgthinq.com", ThinQRegion.KIC.baseUrl())
    }

    @Test
    fun americasRouteToAicAndEmeaToEic() {
        assertEquals(ThinQRegion.AIC, regionForCountry("US"))
        assertEquals(ThinQRegion.AIC, regionForCountry("CA"))
        assertEquals(ThinQRegion.AIC, regionForCountry("BR"))
        assertEquals("https://api-aic.lgthinq.com", ThinQRegion.AIC.baseUrl())

        assertEquals(ThinQRegion.EIC, regionForCountry("DE"))
        assertEquals(ThinQRegion.EIC, regionForCountry("AE"))
        assertEquals(ThinQRegion.EIC, regionForCountry("ZA"))
        assertEquals("https://api-eic.lgthinq.com", ThinQRegion.EIC.baseUrl())
    }

    @Test
    fun kicAsiaPacificRoutesToKic() {
        for (country in listOf("AU", "JP", "KR", "SG", "TH", "TW", "VN", "HK")) {
            assertEquals(ThinQRegion.KIC, regionForCountry(country), "$country must route to KIC")
        }
    }

    @Test
    fun unknownOrMalformedCountryCannotRoute() {
        assertNull(regionForCountry("ZZ"))
        assertNull(regionForCountry(""))
        assertNull(regionForCountry("  "))
        assertNull(regionForCountry("PHL"))
        assertNull(regionForCountry("P1"))
        assertNull(regionForCountry("philippines"))
    }

    @Test
    fun everyRegionIsLowercaseAndExactlyTheSdkShape() {
        for (region in ThinQRegion.entries) {
            assertEquals(
                "https://api-${region.name.lowercase()}.lgthinq.com",
                region.baseUrl(),
            )
        }
    }
}