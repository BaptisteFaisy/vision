package dev.sam3vision.app

import dev.sam3vision.core.settings.Settings
import dev.sam3vision.core.settings.resolveRouteEndpoint

/**
 * Endpoint Valhalla réellement appelé par l'app : saisie manuelle des Réglages >
 * URL injectée au build (`route.base.url`, défaut backend Modal CPU) > vide
 * (= routage in-app désactivé).
 */
fun Settings.routeEndpoint(): String =
    resolveRouteEndpoint(buildDefault = BuildConfig.ROUTE_BASE_URL)

/** Vrai si un itinéraire piéton Valhalla peut être demandé. */
fun Settings.routeReady(): Boolean = routeEndpoint().isNotBlank()
