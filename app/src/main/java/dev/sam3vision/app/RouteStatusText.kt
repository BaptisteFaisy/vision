package dev.sam3vision.app

/**
 * Ligne d'état EN sous le champ URL de la section Navigation des Réglages :
 * guidage coupé (fiche lieu seule), URL Valhalla manquante (Maps seul) ou
 * guidage in-app (écrans + voix iSee, repli Maps). Le test de vacuité reprend
 * `routeConfigured` (`routeBaseUrl.isNotBlank()`) : même seuil, même sens.
 * Pure, testée JVM ([RouteStatusTextTest]).
 */
internal fun routeStatusText(routeBaseUrl: String, guidanceEnabled: Boolean): String = when {
    !guidanceEnabled -> "Guidance off: only the place page will show."
    routeBaseUrl.isBlank() -> "Valhalla URL missing: Google Maps guidance only."
    else -> "In-app guidance: iSee screens + voice, with Google Maps fallback."
}
