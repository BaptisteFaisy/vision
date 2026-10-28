package dev.sam3vision.core.settings

/**
 * Endpoint Valhalla réellement appelé, dans cet ordre : saisie manuelle des
 * Réglages → URL injectée au build (`route.base.url`, défaut : backend Modal
 * CPU `valhalla-idf-cpu`) → vide (= routage in-app désactivé, le guidage
 * reste celui de Google Maps).
 *
 * Le preset est une URL publique non secrète (voir
 * `server/backend/valhalla_cpu.py` et `docs/VOICE_NAV_PROXY.md`).
 */
fun Settings.resolveRouteEndpoint(buildDefault: String = ""): String {
    val manual = routeBaseUrl.trim()
    if (manual.isNotEmpty()) return manual
    return buildDefault.trim()
}
