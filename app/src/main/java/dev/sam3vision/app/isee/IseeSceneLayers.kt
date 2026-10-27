package dev.sam3vision.app.isee

/**
 * Couches affichées de la scène iSee : décision pure, testée en JVM.
 * Tiré de `IseeScenePane.kt`.
 */
/**
 * Ce que la scène montre en plus du décor fixe : la caméra (la rue en haut) et les dots de
 * pagination. Pur, testable sur JVM.
 */
internal data class IseeSceneLayers(val cameraCard: Boolean, val pageDots: Boolean) {
    /** La boule est au repos (grande, au centre) chaque fois que la caméra est fermée. */
    val bubble: Boolean get() = !cameraCard
}

/**
 * La caméra est montrée tant qu'une capture est vivante (`captureLive`), quelle que soit la
 * scène : tenir le micro pendant la vidéo ne doit pas cacher ce qu'on filme. `dissolving` est
 * gardé pour le contrat : le design Portrait n'a plus de dissolution en pixels.
 */
internal fun iseeSceneLayers(bubbles: Int, dissolving: Boolean, captureLive: Boolean): IseeSceneLayers {
    val cameraCard = captureLive && !dissolving
    return IseeSceneLayers(cameraCard = cameraCard, pageDots = !captureLive && bubbles > 1)
}
