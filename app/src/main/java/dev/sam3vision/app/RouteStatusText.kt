package dev.sam3vision.app

/**
 * Ligne d'état FR sous le champ URL de la section Navigation des Réglages :
 * guidage coupé (fiche lieu seule), URL Valhalla manquante (Maps seul) ou
 * guidage in-app (écrans + voix iSee, repli Maps). Le test de vacuité reprend
 * `routeConfigured` (`routeBaseUrl.isNotBlank()`) : même seuil, même sens.
 * Pure, testée JVM ([RouteStatusTextTest]).
 */
internal fun routeStatusText(routeBaseUrl: String, guidanceEnabled: Boolean): String = when {
    !guidanceEnabled -> "Guidage coupé : seule la fiche du lieu s'affichera."
    routeBaseUrl.isBlank() -> "URL Valhalla manquante : guidage Google Maps uniquement."
    else -> "Guidage in-app : écrans + voix iSee, avec repli Google Maps."
}
