package dev.sam3vision.app.ihear

import java.io.File

/**
 * Le contexte image des questions vocales : une seule photo retenue, deux origines.
 * L'appui long capte en silence la frame vivante et la rafraîchit à chaque tenue ;
 * le double tap **gèle** sa photo, qui reste prioritaire jusqu'à la question suivante.
 * Décision pure (aucun import Android) : la caméra est injectée par l'appelant.
 */
class IhearPhotoContext {

    /** Photo jointe à la prochaine question ; `null` = question sans image. */
    var file: File? = null
        private set

    /** Vrai tant qu'une photo du double tap attend sa question. */
    var frozen: Boolean = false
        private set

    /** Double tap : la photo gèle et devient le contexte, elle ignore les frames vivantes. */
    fun freeze(target: File) {
        file = target
        frozen = true
    }

    /** Frame vivante : appliquée seulement si rien n'est gelé entre-temps. */
    fun setLiveIfStillLive(target: File) {
        if (!frozen) file = target
    }

    /** Question envoyée : le gel est consommé, l'appui long reprend la frame vivante. */
    fun releaseFreeze() {
        frozen = false
    }

    /** Appui long : capte la frame courante s'il n'y a pas de photo gelée. Muet : ni bulle ni flash. */
    fun captureForHold(camera: IhearCameraActions?, newTarget: () -> File) {
        if (frozen || camera == null) return
        val target = newTarget()
        camera.takePhoto(target) { saved -> if (saved) setLiveIfStillLive(target) }
    }
}
