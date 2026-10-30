package dev.sam3vision.app.isee

/**
 * Couches affichées de la scène iSee : décision pure, testée en JVM.
 * Tiré de `IseeScenePane.kt`.
 */
/**
 * Ce que la scène montre en plus du décor fixe : la caméra (la rue en haut, téléphone ou
 * lunettes) et les dots de pagination. Pur, testable sur JVM.
 */
internal data class IseeSceneLayers(
    val cameraCard: Boolean,
    val pageDots: Boolean,
    val glassesFeed: Boolean,
    val glassesGhost: Boolean,
    /** Photo des lunettes affichée : elle occupe la rue à la place de tout direct. */
    val photoFeed: Boolean,
) {
    /** La boule est au repos (grande, au centre) chaque fois que la caméra est fermée. */
    val bubble: Boolean get() = !cameraCard
}

/**
 * La caméra est montrée tant qu'une capture est vivante (`captureLive`), quelle que soit la
 * scène : tenir le micro pendant la vidéo ne doit pas cacher ce qu'on filme. `dissolving` est
 * gardé pour le contrat : le design Portrait n'a plus de dissolution en pixels.
 *
 * Photo affichée ([photoDisplayed]) : la rue s'ouvre aussi, sans capture ni
 * direct — la photo figée y prend la place de toute image vivante.
 *
 * Lunettes ([glassesLive]) : la rue s'ouvre aussi, sans capture — dès que les lunettes
 * montrent quelque chose — et c'est leur direct qui l'occupe, pas la caméra du téléphone.
 * Entre le démarrage du flux ([glassesStreaming]) et la première image, la rue existe en
 * fantôme invisible ([glassesGhost]) : sa surface reçoit les frames, sans elle `live` ne
 * passerait jamais à vrai. Même pendant une capture téléphone : l'enregistrement continue
 * en fond, mais la rue montre les lunettes dès qu'elles streament — sinon, avec une capture
 * active, le fantôme n'existerait jamais et `live` resterait faux (impasse constatée sur
 * téléphone).
 */
internal fun iseeSceneLayers(
    bubbles: Int,
    dissolving: Boolean,
    captureLive: Boolean,
    glassesLive: Boolean = false,
    glassesStreaming: Boolean = false,
    photoDisplayed: Boolean = false,
): IseeSceneLayers {
    val cameraCard = captureLive && !dissolving
    return IseeSceneLayers(
        cameraCard = cameraCard,
        pageDots = !captureLive && !glassesLive && !photoDisplayed && bubbles > 1,
        glassesFeed = glassesLive,
        glassesGhost = glassesStreaming && !glassesLive,
        photoFeed = photoDisplayed,
    )
}

/**
 * Quelle source occupe la rue : les lunettes dès qu'elles vivent ([live]) ou naissent
 * ([ghost]), le téléphone sinon. Quand le direct s'éteint en pleine fermeture, la dernière
 * image des lunettes reste figée le temps du fondu ([lastGlasses]) — sauf si le téléphone
 * filme ([cameraCard]) : la capture en cours reprend la rue aussitôt. Pur, testé en JVM.
 */
internal fun showGlassesFeed(
    live: Boolean,
    ghost: Boolean,
    cameraCard: Boolean,
    open: Float,
    lastGlasses: Boolean,
): Boolean {
    if (live || ghost) return true
    if (cameraCard || open <= 0.001f) return false
    return lastGlasses
}
