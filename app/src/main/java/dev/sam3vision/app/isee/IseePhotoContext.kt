package dev.sam3vision.app.isee

import java.io.File

/**
 * Le contexte image des questions vocales : une seule photo retenue, deux origines.
 * L'appui long capte en silence la frame vivante et la rafraîchit à chaque tenue ;
 * le double tap **gèle** sa photo, qui reste prioritaire tant que son fichier vit.
 * Décision pure (aucun import Android) : la caméra est injectée par l'appelant.
 */
class IseePhotoContext {

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

    /**
     * Fin de vie du contexte : plus de photo et plus de gel. Appelée quand la photo
     * quitte l'écran, au démarrage d'une vidéo et à la sortie précoce du ViewModel —
     * sans elle, le gel d'un double tap restait armé à vie.
     */
    fun release() {
        frozen = false
        file = null
    }

    /**
     * Appui long : capte la frame courante, sauf si une photo gelée vit encore sur
     * disque (elle reste prioritaire). Un gel dont le fichier a été purgé est mort :
     * on le relâche, et la frame filmée redevient le contexte de la question.
     * Muet : ni bulle ni flash.
     */
    fun captureForHold(camera: IseeCameraActions?, newTarget: () -> File) {
        if (camera == null) return
        if (frozen && file?.isFile == true) return
        if (frozen) release()
        val target = newTarget()
        camera.takePhoto(target) { saved -> if (saved) setLiveIfStillLive(target) }
    }
}