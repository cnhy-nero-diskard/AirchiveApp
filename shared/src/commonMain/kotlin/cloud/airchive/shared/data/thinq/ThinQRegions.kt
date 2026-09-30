package cloud.airchive.shared.data.thinq

/**
 * LG ThinQ Connect region routing, mirroring the official `thinqconnect` SDK's
 * `country.py` (verified against version 1.0.13 in the sibling Airchive repo).
 *
 * The SDK maps every supported 2-letter account country onto one of three
 * region domain prefixes and calls `https://api-<prefix>.lgthinq.com`:
 * - [KIC]: Asia/Pacific incl. `PH` (the app's configured preset).
 * - [AIC]: the Americas.
 * - [EIC]: Europe / Middle East / Africa.
 *
 * The mapping is a public, non-secret routing table copied from the SDK; the
 * country itself is not secret. Unknown countries cannot be routed and are
 * rejected ([KtorThinQClient] never guesses a region).
 */
enum class ThinQRegion(
    val countryCodes: Set<String>,
) {
    KIC(
        countryCodes(
            "AU", "BD", "CN", "HK", "ID", "IN", "JP", "KH", "KR",
            "LA", "LK", "MM", "MY", "NP", "NZ", "PH", "SG", "TH", "TW", "VN",
        ),
    ),
    AIC(
        countryCodes(
            "AG", "AR", "AW", "BB", "BO", "BR", "BS", "BZ", "CA", "CL",
            "CO", "CR", "CU", "DM", "DO", "EC", "GD", "GT", "GY", "HN",
            "HT", "JM", "KN", "LC", "MX", "NI", "PA", "PE", "PR", "PY",
            "SR", "SV", "TT", "US", "UY", "VC", "VE",
        ),
    ),
    EIC(
        countryCodes(
            "AE", "AF", "AL", "AM", "AO", "AT", "AZ", "BA", "BE", "BF",
            "BG", "BH", "BJ", "BY", "CD", "CF", "CG", "CH", "CI", "CM",
            "CV", "CY", "CZ", "DE", "DJ", "DK", "DZ", "EE", "EG", "ES",
            "ET", "FI", "FR", "GA", "GB", "GE", "GH", "GM", "GN", "GQ",
            "GR", "HR", "HU", "IE", "IL", "IQ", "IR", "IS", "IT", "JO",
            "KE", "KG", "KW", "KZ", "LB", "LR", "LT", "LU", "LV", "LY",
            "MA", "MD", "ME", "MK", "ML", "MR", "MT", "MU", "MW", "NE",
            "NG", "NL", "NO", "OM", "PK", "PL", "PS", "PT", "QA", "RO",
            "RS", "RU", "RW", "SA", "SD", "SE", "SI", "SK", "SL", "SN",
            "SO", "ST", "SY", "TD", "TG", "TN", "TR", "TZ", "UA", "UG",
            "UZ", "XK", "YE", "ZA", "ZM",
        ),
    ),
    ;

    /** `https://api-kic.lgthinq.com` etc., exactly as the SDK builds URLs. */
    fun baseUrl(): String = "https://api-${name.lowercase()}.lgthinq.com"
}

private fun countryCodes(vararg countries: String): Set<String> = setOf(*countries)

/**
 * Region for a two-letter account country (case-insensitive), or `null` when
 * the SDK's `country.py` table does not list it. The caller decides what an
 * unroutable country means; [KtorThinQClient] never defaults to a region.
 */
fun regionForCountry(country: String): ThinQRegion? {
    val normalized = country.trim().uppercase()
    if (normalized.length != 2 || normalized.any { !it.isLetter() }) return null
    return ThinQRegion.entries.firstOrNull { normalized in it.countryCodes }
}