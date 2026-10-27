package dev.sam3vision.app

import android.content.Context
import dev.sam3vision.core.haptics.HapticPattern

/**
 * Haptique iSee : il ne reste qu'un retour, l'appui long (micro) — une salve franche
 * au seuil, puis une nappe légère tant que le doigt reste posé
 * ([HapticLoop.startHold]) ; le relâchement laisse une queue de 200 ms.
 *
 * Le moteur (vibreur, envoi des motifs, garde `runCatching`) vit dans [HapticLoop].
 * Le créneau du balayage vertical a été supprimé avec la navigation entre messages.
 */
class IseeHaptics(context: Context) {

    private val motor = HapticLoop(context)

    /** Appui long : salve franche puis nappe continue pendant toute la tenue. */
    fun holdStart() = motor.startHold()

    /** Relâchement : nappe coupée, queue de 200 ms. */
    fun holdEnd() = motor.release()

    /** Traversée : le nouvel état du feu se sent (vert = double salve). */
    fun crossing(state: dev.sam3vision.core.crossing.CrossingState) {
        val wave = when (state) {
            dev.sam3vision.core.crossing.CrossingState.Green -> HapticPattern.crossingGreen()
            dev.sam3vision.core.crossing.CrossingState.Red -> HapticPattern.crossingRed()
            dev.sam3vision.core.crossing.CrossingState.Unknown -> return
        }
        motor.play(wave)
    }

    /** Coupe tout motif en cours, tenue comprise (arrêt de l'app, geste perdu). */
    fun cancelAll() {
        motor.stop()
    }
}