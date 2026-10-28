package dev.sam3vision.core.settings

/**
 * Endpoint Valhalla réellement appelé, dans cet ordre : saisie manuelle des
 * Réglages → URL injectée au build (`route.base.url`) → vide (= routage in-app
 * désactivé, le guidage reste celui de Google Maps).
 *
 * Pas de preset en dur : l'URL publique du tunnel n'est connue qu'après
 * `tailscale funnel --bg 8002` sur le r630 (voir `docs/VOICE_NAV_PROXY.md`).
 */
fun Settings.resolveRouteEndpoint(buildDefault: String = ""): String {
    val manual = routeBaseUrl.trim()
    if (manual.isNotEmpty()) return manual
    return buildDefault.trim()
}
